param(
    [string]$BaseUrl = "http://localhost:8081",
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskCasesPath = Join-Path $taskRoot "datasets\evaluation\model-recognition-v1\cases.json"
$taskDataset = Get-Content -LiteralPath $taskCasesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$taskBaseUri = [Uri]$BaseUrl
if (-not $taskBaseUri.IsAbsoluteUri -or
    $taskBaseUri.Scheme -notin @("http", "https") -or $taskBaseUri.UserInfo) {
    throw "BaseUrl必须是没有内嵌凭证的HTTP或HTTPS地址。"
}
$taskBaseUrl = $BaseUrl.TrimEnd('/')
$taskAllowedStatuses = @("RESOLVED", "MISSING", "UNKNOWN", "MULTIPLE")
if (@($taskDataset.cases).Count -eq 0) { throw "评测用例不能为空。" }
if (@($taskDataset.cases.caseId | Sort-Object -Unique).Count -ne @($taskDataset.cases).Count) {
    throw "用例编号重复。"
}
foreach ($taskCase in $taskDataset.cases) {
    if ($taskCase.expectedStatus -notin $taskAllowedStatuses) { throw "用例状态无效。" }
    if ($null -eq $taskCase.expectedMentions) { throw "缺少原文提及预期，请使用v1.1用例。" }
    if ([string]::IsNullOrWhiteSpace($taskCase.question) -or $taskCase.question.Length -gt 1000) {
        throw "正常用例问题无效；非法请求单独验证。"
    }
    if ($taskCase.expectedStatus -eq "RESOLVED") {
        if (@($taskCase.expectedDetectedModels).Count -ne 1 -or
            @($taskCase.expectedUnknownMentions).Count -ne 0 -or
            $taskCase.expectedProductModel -cne $taskCase.expectedDetectedModels[0]) {
            throw "RESOLVED用例标签矛盾。"
        }
    } elseif ($null -ne $taskCase.expectedProductModel) {
        throw "非RESOLVED用例不能包含单一规范型号。"
    }
}
if ($ValidateOnly) {
    [PSCustomObject]@{ datasetVersion=$taskDataset.datasetVersion; cases=@($taskDataset.cases).Count; valid=$true } | ConvertTo-Json
    return
}

function Invoke-TaskJson {
    param([string]$Path, [string]$Method="GET", [object]$Body=$null)
    $taskParameters = @{
        Uri = $taskBaseUrl + $Path
        Method = $Method
        UseBasicParsing = $true
        TimeoutSec = 30
    }
    if ($Method -eq "POST") {
        # 显式UTF-8请求与响应解码，避免Windows PowerShell5.1中文乱码。
        $taskParameters.ContentType = "application/json; charset=utf-8"
        $taskParameters.Body = [System.Text.Encoding]::UTF8.GetBytes(
            ($Body | ConvertTo-Json -Depth 8 -Compress))
    }
    $taskHttp = Invoke-WebRequest @taskParameters
    return ([System.Text.Encoding]::UTF8.GetString($taskHttp.RawContentStream.ToArray()) |
        ConvertFrom-Json)
}

function Test-TaskSameSequence {
    param([object[]]$Actual, [object[]]$Expected)
    if ($Actual.Count -ne $Expected.Count) { return $false }
    for ($taskI=0; $taskI -lt $Expected.Count; $taskI++) {
        if ($Actual[$taskI] -cne $Expected[$taskI]) { return $false }
    }
    return $true
}

if ((Invoke-TaskJson -Path "/actuator/health").status -ne "UP") {
    throw "服务未就绪，请先启动后端。"
}
$taskRunDirectory = Join-Path $taskRoot ("artifacts\evaluation\model-recognition-{0}-{1}" -f
    (Get-Date -Format "yyyyMMdd-HHmmss"), $PID)
New-Item -ItemType Directory -Path $taskRunDirectory -Force | Out-Null
$taskRawPath = Join-Path $taskRunDirectory "raw-results.jsonl"
$taskRows = [System.Collections.Generic.List[object]]::new()
foreach ($taskCase in $taskDataset.cases) {
    $taskError = ""
    $taskResponse = $null
    $taskStatusMatch = $false
    $taskModelMatch = $false
    $taskKnownMatch = $false
    $taskUnknownMatch = $false
    $taskMentionsMatch = $false
    $taskInvariantMatch = $false
    $taskTimer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $taskResponse = Invoke-TaskJson -Path "/api/v1/routing/model" -Method "POST" -Body @{ question=$taskCase.question }
        if ($taskResponse.status -notin $taskAllowedStatuses -or
            $null -eq $taskResponse.detectedModels -or
            $null -eq $taskResponse.unknownMentions -or $null -eq $taskResponse.mentions) {
            throw "响应结构不完整。"
        }
        $taskStatusMatch = $taskResponse.status -ceq $taskCase.expectedStatus
        $taskModelMatch = $taskResponse.productModel -ceq $taskCase.expectedProductModel
        $taskKnownMatch = Test-TaskSameSequence -Actual @($taskResponse.detectedModels) -Expected @($taskCase.expectedDetectedModels)
        $taskUnknownMatch = Test-TaskSameSequence -Actual @($taskResponse.unknownMentions) -Expected @($taskCase.expectedUnknownMentions)
        $taskMentionsMatch = Test-TaskSameSequence -Actual @($taskResponse.mentions) -Expected @($taskCase.expectedMentions)
        # 不只检查状态；非RESOLVED不能泄露一个被擅自选中的型号。
        $taskInvariantMatch = if ($taskResponse.status -eq "RESOLVED") {
            @($taskResponse.detectedModels).Count -eq 1 -and
                $taskResponse.productModel -ceq $taskResponse.detectedModels[0] -and
                @($taskResponse.unknownMentions).Count -eq 0
        } else { $null -eq $taskResponse.productModel }
    } catch { $taskError = $_.Exception.Message } finally { $taskTimer.Stop() }
    $taskPassed = $taskError -eq "" -and $taskStatusMatch -and $taskModelMatch -and
        $taskKnownMatch -and $taskUnknownMatch -and $taskMentionsMatch -and $taskInvariantMatch
    $taskRows.Add([PSCustomObject]@{
        caseId=$taskCase.caseId; question=$taskCase.question
        expectedStatus=$taskCase.expectedStatus; actualStatus=$taskResponse.status
        statusMatch=$taskStatusMatch; modelMatch=$taskModelMatch
        knownSequenceMatch=$taskKnownMatch; unknownSequenceMatch=$taskUnknownMatch
        mentionsSequenceMatch=$taskMentionsMatch
        invariantMatch=$taskInvariantMatch; passed=$taskPassed
        elapsedMs=$taskTimer.ElapsedMilliseconds; error=$taskError
    })
    $taskRaw = @{ caseId=$taskCase.caseId; expected=$taskCase; actual=$taskResponse; error=$taskError }
    [System.IO.File]::AppendAllText($taskRawPath, ($taskRaw | ConvertTo-Json -Depth 12 -Compress) + "`n",
        [System.Text.UTF8Encoding]::new($false))
    Write-Host "$($taskCase.caseId): $($taskResponse.status) passed=$taskPassed"
}
$taskRows | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "cases.csv") -NoTypeInformation -Encoding UTF8

# 参数校验与业务状态分开：以下五项应返回HTTP400。
$taskInvalidCases = @(
    @{ name="空字符串"; body=@{ question="" } },
    @{ name="空白"; body=@{ question="   " } },
    @{ name="显式null"; body=@{ question=$null } },
    @{ name="字段缺失"; body=@{} },
    @{ name="超过1000字符"; body=@{ question=("a" * 1001) } }
)
$taskValidationRows = [System.Collections.Generic.List[object]]::new()
foreach ($taskInvalid in $taskInvalidCases) {
    $taskCode = 0
    $taskError = ""
    try {
        $taskHttp = Invoke-WebRequest -Uri ($taskBaseUrl + "/api/v1/routing/model") -Method POST -UseBasicParsing -TimeoutSec 30 -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes(($taskInvalid.body | ConvertTo-Json -Compress)))
        $taskCode = [int]$taskHttp.StatusCode
    } catch {
        if ($_.Exception.Response) { $taskCode = [int]$_.Exception.Response.StatusCode }
        else { $taskError = $_.Exception.Message }
    }
    $taskValidationRows.Add([PSCustomObject]@{
        scenario=$taskInvalid.name; httpStatus=$taskCode
        passed=$taskCode -eq 400 -and $taskError -eq ""; error=$taskError
    })
}
$taskValidationRows | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "validation.csv") -NoTypeInformation -Encoding UTF8
$taskSummary = [PSCustomObject]@{
    datasetVersion=$taskDataset.datasetVersion
    cases=$taskRows.Count
    requestErrors=@($taskRows | Where-Object { $_.error -ne "" }).Count
    statusMatches=@($taskRows | Where-Object statusMatch).Count
    fullyMatched=@($taskRows | Where-Object passed).Count
    statusMatchRate=@($taskRows | Where-Object statusMatch).Count / $taskRows.Count
    falseResolved=@($taskRows | Where-Object { $_.actualStatus -eq "RESOLVED" -and $_.expectedStatus -ne "RESOLVED" }).Count
    invalidRequests=$taskValidationRows.Count
    invalidRequestsPassed=@($taskValidationRows | Where-Object passed).Count
}
$taskSourceHashes = @{}
foreach ($taskRelative in @(
    "dto/ModelRecognitionRequest.java", "dto/ModelRecognitionResponse.java",
    "service/ProductModelCatalog.java", "service/ModelRecognitionService.java",
    "controller/ModelRoutingController.java"
)) {
    $taskSourcePath = Join-Path $taskRoot ("backend/src/main/java/com/lzq/commercecare/routing/" + $taskRelative)
    $taskSourceHashes[$taskRelative] = (Get-FileHash -LiteralPath $taskSourcePath -Algorithm SHA256).Hash
}
$taskMetadata = @{
    runAt=(Get-Date -Format o); baseUrl=$taskBaseUrl; powershellVersion=$PSVersionTable.PSVersion.ToString()
    casesSha256=(Get-FileHash -LiteralPath $taskCasesPath -Algorithm SHA256).Hash
    scriptSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    sourceHashes=$taskSourceHashes
}
[System.IO.File]::WriteAllText((Join-Path $taskRunDirectory "summary.json"),
    ($taskSummary | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText((Join-Path $taskRunDirectory "metadata.json"),
    ($taskMetadata | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
$taskSummary | ConvertTo-Json
Write-Host "结果目录：$taskRunDirectory"
if ($taskSummary.fullyMatched -ne $taskSummary.cases -or
    $taskSummary.invalidRequestsPassed -ne $taskSummary.invalidRequests) {
    throw "识别回归或参数检查失败，详见结果目录。"
}

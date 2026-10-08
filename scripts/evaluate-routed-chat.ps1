param(
    [string]$BaseUrl = "http://localhost:8081",
    [switch]$SkipBaseline,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskCasesPath = Join-Path $taskRoot "datasets\evaluation\routed-chat-v1\cases.json"
$taskManifestPath = Join-Path $taskRoot "datasets\evaluation\expanded-v1\corpus-manifest.json"
$taskDataset = Get-Content -LiteralPath $taskCasesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$taskManifest = Get-Content -LiteralPath $taskManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$taskUri = [Uri]$BaseUrl
if (-not $taskUri.IsAbsoluteUri -or $taskUri.Scheme -notin @("http", "https") -or $taskUri.UserInfo) {
    throw "BaseUrl必须为不含凭证的HTTP或HTTPS地址。"
}
$taskBaseUrl = $BaseUrl.TrimEnd('/')
$taskSourceModels = @{}
foreach ($taskSource in $taskManifest.sources) { $taskSourceModels[$taskSource.sourceId] = $taskSource.model }
# 原有A1资料为明确人工映射，通用物流没有绑定型号。
foreach ($taskId in @("soundbee-return-policy-v1", "soundbee-a1-pairing-guide-v1", "soundbee-a1-invoice-guide-v1")) {
    $taskSourceModels[$taskId] = "SoundBee-A1"
}
if (@($taskDataset.cases).Count -eq 0 -or
    @($taskDataset.cases.caseId | Sort-Object -Unique).Count -ne @($taskDataset.cases).Count) {
    throw "用例为空或编号重复。"
}
foreach ($taskCase in $taskDataset.cases) {
    if ($taskCase.expectedStatus -notin @("ANSWERED","INSUFFICIENT_EVIDENCE","NEEDS_CLARIFICATION")) {
        throw "预期状态无效。"
    }
    if ($taskCase.expectedStatus -eq "NEEDS_CLARIFICATION") {
        if ($taskCase.expectedStrategy -ne "CLARIFY_MODEL" -or
            $null -ne $taskCase.expectedProductModel -or @($taskCase.expectedSourceIds).Count -ne 0) {
            throw "澄清标签矛盾。"
        }
    } else {
        if ($taskCase.expectedStrategy -ne "MODEL_FILTERED_VECTOR" -or
            $taskCase.expectedRecognitionStatus -ne "RESOLVED" -or
            [string]::IsNullOrWhiteSpace($taskCase.expectedProductModel)) {
            throw "检索标签矛盾。"
        }
    }
    foreach ($taskId in $taskCase.expectedSourceIds) {
        if (-not $taskSourceModels.ContainsKey($taskId) -or
            $taskSourceModels[$taskId] -cne $taskCase.expectedProductModel) {
            throw "预期来源与型号不一致。"
        }
    }
}
if ($ValidateOnly) {
    [PSCustomObject]@{ datasetVersion=$taskDataset.datasetVersion; cases=@($taskDataset.cases).Count; valid=$true } | ConvertTo-Json
    return
}

function Invoke-TaskJson {
    param([string]$Path, [string]$Method="GET", [object]$Body=$null)
    $taskParams = @{ Uri=$taskBaseUrl+$Path; Method=$Method; UseBasicParsing=$true; TimeoutSec=90 }
    if ($Method -eq "POST") {
        $taskParams.ContentType = "application/json; charset=utf-8"
        $taskParams.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }
    $taskHttp = Invoke-WebRequest @taskParams
    return ([System.Text.Encoding]::UTF8.GetString($taskHttp.RawContentStream.ToArray()) | ConvertFrom-Json)
}
function Test-TaskSequence {
    param([object[]]$Actual, [object[]]$Expected)
    if ($Actual.Count -ne $Expected.Count) { return $false }
    for ($taskI=0; $taskI -lt $Expected.Count; $taskI++) {
        if ($Actual[$taskI] -cne $Expected[$taskI]) { return $false }
    }
    return $true
}
if ((Invoke-TaskJson -Path "/actuator/health").status -ne "UP") { throw "服务未就绪。" }
$taskRunDirectory = Join-Path $taskRoot ("artifacts\evaluation\routed-chat-{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $PID)
New-Item -ItemType Directory -Path $taskRunDirectory -Force | Out-Null
$taskRows = [System.Collections.Generic.List[object]]::new()
$taskRawPath = Join-Path $taskRunDirectory "raw-results.jsonl"
$taskEndpoints = @(@{ name="routed"; path="/api/v1/chat/routed" })
if (-not $SkipBaseline) { $taskEndpoints += @{ name="baseline"; path="/api/v1/chat" } }

foreach ($taskCase in $taskDataset.cases) {
    foreach ($taskEndpoint in $taskEndpoints) {
        $taskError=""; $taskReply=$null; $taskModelMatch=$null; $taskStrategyMatch=$null
        # 适用性由标签和入口确定，失败请求也必须留在检查分母中。
        $taskStatusMatch=$null; $taskEvidenceMatch=$null
        $taskApplicable=$taskEndpoint.name -eq "routed" -or $taskCase.expectedStatus -ne "NEEDS_CLARIFICATION"
        $taskInvariantMatch=$false; $taskSourceScopeMatch=$null
        $taskTimer=[System.Diagnostics.Stopwatch]::StartNew()
        try {
            $taskReply=Invoke-TaskJson -Path $taskEndpoint.path -Method "POST" -Body @{ question=$taskCase.question; topK=$taskCase.topK }
            $taskAllowedStatuses = if ($taskEndpoint.name -eq "routed") {
                @("ANSWERED","INSUFFICIENT_EVIDENCE","NEEDS_CLARIFICATION")
            } else { @("ANSWERED","INSUFFICIENT_EVIDENCE") }
            if ($taskReply.status -notin $taskAllowedStatuses -or
                $null -eq $taskReply.sources -or [string]::IsNullOrWhiteSpace($taskReply.answer)) {
                throw "响应缺少状态、回答或来源。"
            }
            $taskSources=@($taskReply.sources)
            $taskActualIds=@($taskSources | ForEach-Object sourceId)
            $taskInvariantMatch = if ($taskReply.status -eq "ANSWERED") {
                $taskSources.Count -gt 0
            } else { $taskSources.Count -eq 0 }
            # 旧接口没有澄清状态；不能把这7题强行与新状态标签做同口径匹配。
            if ($taskApplicable) {
                $taskStatusMatch=$taskReply.status -ceq $taskCase.expectedStatus
                $taskEvidenceMatch = if ($taskCase.expectedStatus -eq "ANSWERED") {
                    @($taskCase.expectedSourceIds | Where-Object { $_ -notin $taskActualIds }).Count -eq 0
                } else { $taskSources.Count -eq 0 }
                $taskSourceScopeMatch=@($taskSources | Where-Object {
                    -not $taskSourceModels.ContainsKey($_.sourceId) -or
                    $taskSourceModels[$_.sourceId] -cne $taskCase.expectedProductModel
                }).Count -eq 0
            }
            if ($taskEndpoint.name -eq "routed") {
                if ($null -eq $taskReply.modelRecognition) { throw "响应缺少识别详情。" }
                $taskStrategyMatch=$taskReply.strategy -ceq $taskCase.expectedStrategy
                $taskModelMatch=$taskReply.modelRecognition.status -ceq $taskCase.expectedRecognitionStatus -and
                    $taskReply.modelRecognition.productModel -ceq $taskCase.expectedProductModel -and
                    (Test-TaskSequence -Actual @($taskReply.modelRecognition.detectedModels) -Expected @($taskCase.expectedDetectedModels)) -and
                    (Test-TaskSequence -Actual @($taskReply.modelRecognition.unknownMentions) -Expected @($taskCase.expectedUnknownMentions))
            }
        } catch { $taskError=$_.Exception.Message } finally { $taskTimer.Stop() }
        $taskAutoMatched = if ($taskApplicable) {
            $taskError -eq "" -and $taskStatusMatch -and $taskEvidenceMatch -and $taskSourceScopeMatch -and
                $taskInvariantMatch -and ($taskEndpoint.name -ne "routed" -or ($taskModelMatch -and $taskStrategyMatch))
        } else { $null }
        $taskRows.Add([PSCustomObject]@{
            caseId=$taskCase.caseId; endpoint=$taskEndpoint.name; question=$taskCase.question
            expectedStatus=$taskCase.expectedStatus; actualStatus=$taskReply.status
            checksApplicable=$taskApplicable; statusMatch=$taskStatusMatch
            strategyMatch=$taskStrategyMatch; modelMatch=$taskModelMatch
            evidenceMatch=$taskEvidenceMatch; sourceScopeMatch=$taskSourceScopeMatch
            invariantMatch=$taskInvariantMatch; autoMatched=$taskAutoMatched
            answer=$taskReply.answer; actualSourceIds=@($taskReply.sources | ForEach-Object sourceId) -join "|"
            expectedAnswerPoints=$taskCase.expectedAnswerPoints
            manualCorrect=""; reviewNotes=""; elapsedMs=$taskTimer.ElapsedMilliseconds; error=$taskError
        })
        [System.IO.File]::AppendAllText($taskRawPath,
            (@{caseId=$taskCase.caseId;endpoint=$taskEndpoint.name;expected=$taskCase;actual=$taskReply;error=$taskError} | ConvertTo-Json -Depth 14 -Compress)+"`n",
            [System.Text.UTF8Encoding]::new($false))
        Write-Host "$($taskCase.caseId) $($taskEndpoint.name): $($taskReply.status) autoMatched=$taskAutoMatched"
    }
}
$taskRows | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "cases.csv") -NoTypeInformation -Encoding UTF8

$taskSummary=[System.Collections.Generic.List[object]]::new()
foreach ($taskEndpoint in $taskEndpoints) {
    $taskEndpointRows=@($taskRows | Where-Object endpoint -eq $taskEndpoint.name)
    $taskApplicableRows=@($taskEndpointRows | Where-Object checksApplicable)
    $taskSummary.Add([PSCustomObject]@{
        endpoint=$taskEndpoint.name; requests=$taskEndpointRows.Count
        requestErrors=@($taskEndpointRows | Where-Object { $_.error -ne "" }).Count
        applicableChecks=$taskApplicableRows.Count
        autoMatched=@($taskApplicableRows | Where-Object autoMatched).Count
        statusMatched=@($taskApplicableRows | Where-Object statusMatch).Count
        actualAnswered=@($taskEndpointRows | Where-Object actualStatus -eq "ANSWERED").Count
        actualClarifications=@($taskEndpointRows | Where-Object actualStatus -eq "NEEDS_CLARIFICATION").Count
        meanElapsedMs=[Math]::Round(($taskEndpointRows | Measure-Object elapsedMs -Average).Average,1)
    })
}
$taskSummary | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "summary.csv") -NoTypeInformation -Encoding UTF8
$taskConfusion=@($taskRows | Group-Object endpoint,expectedStatus,actualStatus | ForEach-Object {
    [PSCustomObject]@{ endpoint=$_.Group[0].endpoint; expected=$_.Group[0].expectedStatus; actual=$_.Group[0].actualStatus; count=$_.Count }
})
$taskConfusion | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "status-counts.csv") -NoTypeInformation -Encoding UTF8
$taskHashes=@{}
foreach ($taskRelative in @(
    "assistant/dto/RoutedChatResponse.java", "assistant/service/RoutedChatService.java",
    "assistant/service/RagChatService.java", "assistant/controller/RoutedChatController.java",
    "routing/service/ModelRecognitionService.java", "knowledge/service/KnowledgeSearchService.java"
)) {
    $taskHashes[$taskRelative]=(Get-FileHash -LiteralPath (Join-Path $taskRoot ("backend/src/main/java/com/lzq/commercecare/"+$taskRelative)) -Algorithm SHA256).Hash
}
[System.IO.File]::WriteAllText((Join-Path $taskRunDirectory "metadata.json"),(@{
    runAt=(Get-Date -Format o);baseUrl=$taskBaseUrl;skipBaseline=[bool]$SkipBaseline
    datasetSha256=(Get-FileHash -LiteralPath $taskCasesPath -Algorithm SHA256).Hash
    manifestSha256=(Get-FileHash -LiteralPath $taskManifestPath -Algorithm SHA256).Hash
    scriptSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
    sourceHashes=$taskHashes
}|ConvertTo-Json -Depth 10),[System.Text.UTF8Encoding]::new($false))
$taskSummary | Format-Table -AutoSize
Write-Host "结果目录：$taskRunDirectory"
Write-Host "autoMatched只表示字段与来源标签匹配，答案需填写manualCorrect/reviewNotes另行审核。"
if (@($taskRows | Where-Object { $_.error -ne "" -or -not $_.invariantMatch }).Count -gt 0) {
    throw "存在接口错误或响应不变量失败，详见结果文件。"
}

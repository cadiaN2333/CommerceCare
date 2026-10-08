param(
    [string]$BaseUrl = "http://localhost:8081",
    [ValidateSet("dev", "validation", "all")]
    [string]$Split = "dev",
    [ValidateRange(1, 10)]
    [int]$TopK = 2,
    [switch]$SkipImport,
    [switch]$FilterByModel,
    [switch]$EvaluateChat,
    [switch]$ValidateOnly,
    [ValidateRange(1, 100)]
    [int]$Limit = 100
)

$ErrorActionPreference = "Stop"
if ($FilterByModel -and $EvaluateChat) {
    throw "聊天仍为无过滤基线；请分别运行聊天评测和型号过滤检索评测。"
}
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDataDirectory = Join-Path $taskRoot "datasets\evaluation\expanded-v1"
$taskManifestPath = Join-Path $taskDataDirectory "corpus-manifest.json"
$taskCasesPath = Join-Path $taskDataDirectory "cases.json"
$taskManifest = Get-Content -LiteralPath $taskManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$taskDataset = Get-Content -LiteralPath $taskCasesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$taskBaseUri = [Uri]$BaseUrl
if ($taskBaseUri.Scheme -notin @("http", "https") -or $taskBaseUri.UserInfo) {
    throw "BaseUrl 必须是没有内嵌凭证的 HTTP 或 HTTPS 地址。"
}
$taskBaseUrl = $BaseUrl.TrimEnd('/')
$taskManifestHash = (Get-FileHash -LiteralPath $taskManifestPath -Algorithm SHA256).Hash

function Invoke-TaskJson {
    param([string]$Path, [string]$Method = "GET", [object]$Body = $null)

    $taskParameters = @{
        Uri = $taskBaseUrl + $Path
        Method = $Method
        TimeoutSec = 60
        UseBasicParsing = $true
    }
    if ($Method -eq "POST") {
        # 请求和响应都显式使用 UTF-8，兼容 Windows PowerShell 5.1。
        $taskJson = $Body | ConvertTo-Json -Depth 10 -Compress
        $taskParameters.ContentType = "application/json; charset=utf-8"
        $taskParameters.Body = [System.Text.Encoding]::UTF8.GetBytes($taskJson)
    }
    $taskHttpResponse = Invoke-WebRequest @taskParameters
    $taskResponseText = [System.Text.Encoding]::UTF8.GetString(
        $taskHttpResponse.RawContentStream.ToArray()
    )
    return $taskResponseText | ConvertFrom-Json
}

function Get-TaskMetrics {
    param([object[]]$Rows, [string]$Route)

    $taskSuccessful = @($Rows | Where-Object { $_.error -eq "" })
    $taskTimes = @($taskSuccessful | ForEach-Object { [double]$_.elapsedMs } | Sort-Object)
    $taskMeanTime = $null
    $taskP95 = $null
    if ($taskTimes.Count -gt 0) {
        $taskMeanTime = [Math]::Round(($taskTimes | Measure-Object -Average).Average, 1)
        $taskP95 = $taskTimes[[Math]::Ceiling(0.95 * $taskTimes.Count) - 1]
    }
    # 失败请求也留在平均指标的分母中，避免只统计成功请求而抬高指标。
    return [PSCustomObject]@{
        route = $Route
        cases = $Rows.Count
        successfulRequests = $taskSuccessful.Count
        errors = $Rows.Count - $taskSuccessful.Count
        filterByModel = [bool]$FilterByModel
        missingModelResults = ($Rows | Measure-Object missingModelCount -Sum).Sum
        otherModelResults = ($Rows | Measure-Object otherModelCount -Sum).Sum
        sourceRecallAtK = [Math]::Round(($Rows | Measure-Object recallAtK -Average).Average, 4)
        sourceLabelPrecisionAtK = [Math]::Round(($Rows | Measure-Object precisionAtK -Average).Average, 4)
        hitAtK = [Math]::Round(@($Rows | Where-Object hit).Count / $Rows.Count, 4)
        allRequiredSourcesRate = [Math]::Round(@($Rows | Where-Object allRequiredSources).Count / $Rows.Count, 4)
        meanReciprocalRank = [Math]::Round(($Rows | Measure-Object reciprocalRank -Average).Average, 4)
        meanSuccessfulElapsedMs = $taskMeanTime
        p95SuccessfulElapsedMs = $taskP95
    }
}

# 入库前先检查编号、来源、型号分组及资料哈希，避免评测标签与语料漂移。
$taskSourcesById = @{}
$taskKnowledgeRoot = [System.IO.Path]::GetFullPath((Join-Path $taskRoot "datasets\knowledge\expanded-v1"))
foreach ($taskSource in $taskManifest.sources) {
    if ($taskSourcesById.ContainsKey($taskSource.sourceId)) { throw "知识来源编号重复。" }
    $taskContentPath = [System.IO.Path]::GetFullPath((Join-Path $taskRoot $taskSource.contentPath))
    if (-not $taskContentPath.StartsWith($taskKnowledgeRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "资料路径不在 expanded-v1 知识目录内。"
    }
    if (-not (Test-Path -LiteralPath $taskContentPath)) { throw "缺少资料：$taskContentPath" }
    if ((Get-FileHash -LiteralPath $taskContentPath -Algorithm SHA256).Hash -ne $taskSource.sha256) {
        throw "资料已修改但清单哈希未更新：$($taskSource.sourceId)"
    }
    $taskSourcesById[$taskSource.sourceId] = $taskSource
}
if ($taskSourcesById.Count -ne $taskManifest.sourceCount) { throw "清单来源数不一致。" }
$taskCaseIds = @{}
foreach ($taskCase in $taskDataset.cases) {
    if ($taskCaseIds.ContainsKey($taskCase.caseId)) { throw "问题编号重复。" }
    $taskCaseIds[$taskCase.caseId] = $true
    if (-not $taskCase.question.Contains($taskCase.model)) { throw "问题正文缺少标注的型号。" }
    if ($taskCase.split -notin @("dev", "validation")) { throw "问题集分组非法。" }
    if ($taskCase.kind -notin @("retrieval", "unanswerable")) { throw "问题类型非法。" }
    if ($taskCase.expectedStatus -notin @("ANSWERED", "INSUFFICIENT_EVIDENCE")) { throw "预期业务状态非法。" }
    if ($taskCase.kind -eq "retrieval" -and $taskCase.expectedStatus -ne "ANSWERED") { throw "检索问题的状态标签不一致。" }
    if ($taskCase.kind -eq "unanswerable" -and $taskCase.expectedStatus -ne "INSUFFICIENT_EVIDENCE") { throw "无答案问题的状态标签不一致。" }
    if ($taskCase.kind -eq "retrieval" -and @($taskCase.expectedSourceIds).Count -eq 0) { throw "可回答问题没有标注来源。" }
    if ($taskCase.kind -eq "unanswerable" -and @($taskCase.expectedSourceIds).Count -ne 0) { throw "无答案问题不应标注支持来源。" }
    foreach ($taskSourceId in $taskCase.expectedSourceIds) {
        if (-not $taskSourcesById.ContainsKey($taskSourceId)) { throw "问题引用未知来源：$taskSourceId" }
        if ($taskSourcesById[$taskSourceId].model -ne $taskCase.model) { throw "问题与支持资料的型号不一致。" }
    }
}
$taskDevModels = @($taskDataset.cases | Where-Object split -eq "dev" | Select-Object -ExpandProperty model -Unique)
$taskValidationModels = @($taskDataset.cases | Where-Object split -eq "validation" | Select-Object -ExpandProperty model -Unique)
if (@($taskDevModels | Where-Object { $_ -in $taskValidationModels }).Count -gt 0) { throw "开发与验证型号发生交叉。" }
if ($ValidateOnly) {
    [PSCustomObject]@{ sources=$taskSourcesById.Count; cases=$taskDataset.cases.Count; devModels=$taskDevModels.Count; validationModels=$taskValidationModels.Count; verified=$true } | ConvertTo-Json
    return
}

if ($Split -ne "dev") {
    Write-Warning "本次将使用保留验证问题；结果被用于调参后，不能继续把它当作未见数据。"
}
$taskHealth = Invoke-TaskJson -Path "/actuator/health"
if ($taskHealth.status -ne "UP") { throw "服务健康检查未通过，请先启动后端和数据库。" }
$taskOutputRoot = Join-Path $taskRoot "artifacts\evaluation"
$taskRunDirectory = Join-Path $taskOutputRoot ("expanded-v1-{0}-{1}-{2}" -f $Split, (Get-Date -Format "yyyyMMdd-HHmmss"), $PID)
New-Item -ItemType Directory -Path $taskRunDirectory -Force | Out-Null
$taskReceiptPath = Join-Path $taskOutputRoot "expanded-v1-import-receipt.json"
$taskImportRows = [System.Collections.Generic.List[object]]::new()
if (-not $SkipImport) {
    if (Test-Path -LiteralPath $taskReceiptPath) { Remove-Item -LiteralPath $taskReceiptPath }
    $taskImported = 0
    foreach ($taskSource in $taskManifest.sources) {
        $taskError = ""
        $taskChunkCount = 0
        try {
            $taskBody = @{
                sourceId = $taskSource.sourceId
                title = $taskSource.title
                productModel = $taskSource.model
                topic = $taskSource.topic
                content = [System.IO.File]::ReadAllText((Join-Path $taskRoot $taskSource.contentPath), [System.Text.Encoding]::UTF8)
            }
            $taskResult = Invoke-TaskJson -Path "/api/v1/knowledge/index" -Method "POST" -Body $taskBody
            if ($taskResult.sourceId -ne $taskSource.sourceId -or $taskResult.indexedChunks -lt 1) { throw "入库响应无效。" }
            $taskChunkCount = $taskResult.indexedChunks
        } catch { $taskError = $_.Exception.Message }
        $taskImportRows.Add([PSCustomObject]@{ sourceId=$taskSource.sourceId; indexedChunks=$taskChunkCount; error=$taskError })
        $taskImported++
        if ($taskImported % 10 -eq 0) { Write-Host "入库进度：$taskImported / $($taskManifest.sourceCount)" }
    }
    $taskImportRows | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "import.csv") -NoTypeInformation -Encoding UTF8
    if (@($taskImportRows | Where-Object { $_.error -ne "" }).Count -gt 0) {
        throw "部分资料入库失败，停止评测；详见 $taskRunDirectory\import.csv，修复后重新运行。"
    }
    [System.IO.File]::WriteAllText($taskReceiptPath, (@{ metadataSchemaVersion=2; manifestHash=$taskManifestHash; baseUrl=$taskBaseUrl; importedAt=(Get-Date -Format o); sourceCount=$taskSourcesById.Count } | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
} else {
    if (-not (Test-Path -LiteralPath $taskReceiptPath)) { throw "没有完整入库记录，请先不带 SkipImport 运行。" }
    $taskReceipt = Get-Content -LiteralPath $taskReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($taskReceipt.metadataSchemaVersion -ne 2) { throw "旧入库记录没有型号元数据，请不带 SkipImport 重新导入。" }
    if ($taskReceipt.manifestHash -ne $taskManifestHash -or $taskReceipt.baseUrl -ne $taskBaseUrl) { throw "入库记录与当前清单或服务不一致，请重新导入。" }
    Write-Warning "跳过入库只依据本地记录；若数据库或 Lucene 已重建，请移除 SkipImport 后运行。"
}

$taskSelected = @($taskDataset.cases | Where-Object { $Split -eq "all" -or $_.split -eq $Split } | Select-Object -First $Limit)
$taskRetrievalCases = @($taskSelected | Where-Object kind -eq "retrieval")
$taskRows = [System.Collections.Generic.List[object]]::new()
$taskChatRows = [System.Collections.Generic.List[object]]::new()
$taskRawPath = Join-Path $taskRunDirectory "raw-results.jsonl"
$taskRoutes = @("search", "keyword-search", "hybrid-search")
foreach ($taskCase in $taskRetrievalCases) {
    foreach ($taskRoute in $taskRoutes) {
        $taskError = ""
        $taskCandidates = @()
        $taskWatch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $taskSearchBody = @{ question=$taskCase.question; topK=$TopK }
            if ($FilterByModel) { $taskSearchBody.productModel = $taskCase.model }
            $taskResponse = Invoke-TaskJson -Path ("/api/v1/knowledge/" + $taskRoute) -Method "POST" -Body $taskSearchBody
            if ($null -eq $taskResponse.results) { throw "响应缺少 results 列表。" }
            $taskCandidates = @($taskResponse.results)
            if ($FilterByModel -and @($taskCandidates | Where-Object {
                $_.productModel -cne $taskCase.model
            }).Count -gt 0) {
                throw "过滤结果包含其他型号或缺失型号，请检查两路过滤与响应字段。"
            }
        } catch { $taskError = $_.Exception.Message } finally { $taskWatch.Stop() }
        $taskExpected = @($taskCase.expectedSourceIds)
        $taskActual = @($taskCandidates | ForEach-Object { $_.sourceId })
        $taskMatched = @($taskExpected | Where-Object { $_ -in $taskActual }).Count
        $taskRelevant = @($taskCandidates | Where-Object { $_.sourceId -in $taskExpected }).Count
        $taskFirstRelevantRank = 0
        for ($taskI=0; $taskI -lt $taskCandidates.Count; $taskI++) {
            if ($taskCandidates[$taskI].sourceId -in $taskExpected) { $taskFirstRelevantRank=$taskI+1; break }
        }
        $taskValid = $taskError -eq ""
        $taskRows.Add([PSCustomObject]@{
            datasetVersion=$taskDataset.datasetVersion; split=$taskCase.split; caseId=$taskCase.caseId; group=$taskCase.group; model=$taskCase.model; question=$taskCase.question; route=$taskRoute; topK=$TopK
            filterByModel=[bool]$FilterByModel
            missingModelCount=@($taskCandidates | Where-Object { -not $_.productModel }).Count
            otherModelCount=@($taskCandidates | Where-Object { $_.productModel -and $_.productModel -cne $taskCase.model }).Count
            expectedSourceIds=$taskExpected -join "|"; actualSourceIds=$taskActual -join "|"; returnedCount=$taskCandidates.Count
            recallAtK=if($taskValid){$taskMatched/$taskExpected.Count}else{0.0}
            precisionAtK=if($taskValid){$taskRelevant/$TopK}else{0.0}
            hit=$taskValid -and $taskMatched -gt 0
            allRequiredSources=$taskValid -and $taskMatched -eq $taskExpected.Count
            firstRelevantRank=$taskFirstRelevantRank
            reciprocalRank=if($taskValid -and $taskFirstRelevantRank -gt 0){1.0/$taskFirstRelevantRank}else{0.0}
            elapsedMs=$taskWatch.ElapsedMilliseconds; error=$taskError
        })
        $taskRawItem = @{ filterByModel=[bool]$FilterByModel; caseId=$taskCase.caseId; split=$taskCase.split; route=$taskRoute; question=$taskCase.question; expectedSourceIds=$taskExpected; results=$taskCandidates; error=$taskError }
        [System.IO.File]::AppendAllText($taskRawPath, ($taskRawItem | ConvertTo-Json -Depth 12 -Compress) + "`n", [System.Text.UTF8Encoding]::new($false))
    }
    Write-Host "三路检索完成：$($taskCase.caseId)"
}
$taskRows | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "retrieval.csv") -NoTypeInformation -Encoding UTF8

if ($EvaluateChat) {
    foreach ($taskCase in $taskSelected) {
        $taskAnswer = ""
        $taskStatus = "ERROR"
        $taskSourceIds = @()
        $taskError = ""
        $taskWatch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            # 当前 chat 端点使用自己的既有检索路径，这不等于三路生成质量对照。
            $taskChat = Invoke-TaskJson -Path "/api/v1/chat" -Method "POST" -Body @{ question=$taskCase.question; topK=$TopK }
            if ([string]::IsNullOrWhiteSpace($taskChat.status) -or $null -eq $taskChat.sources) { throw "聊天响应结构无效。" }
            $taskStatus=$taskChat.status; $taskAnswer=$taskChat.answer
            $taskSourceIds=@($taskChat.sources | ForEach-Object { $_.sourceId })
        } catch { $taskError=$_.Exception.Message } finally { $taskWatch.Stop() }
        $taskChatRows.Add([PSCustomObject]@{
            caseId=$taskCase.caseId; split=$taskCase.split; group=$taskCase.group; question=$taskCase.question
            expectedStatus=$taskCase.expectedStatus; actualStatus=$taskStatus
            statusMatch=$taskError -eq "" -and $taskStatus -eq $taskCase.expectedStatus
            sourceMatch=if($taskCase.kind -eq "unanswerable"){$taskError -eq "" -and $taskSourceIds.Count -eq 0}else{$taskError -eq "" -and @($taskCase.expectedSourceIds | Where-Object { $_ -notin $taskSourceIds }).Count -eq 0}
            answer=$taskAnswer; expectedAnswerPoints=$taskCase.expectedAnswerPoints; sources=$taskSourceIds -join "|"
            manualCorrect=""; reviewNotes=""; elapsedMs=$taskWatch.ElapsedMilliseconds; error=$taskError
        })
        Write-Host "聊天完成：$($taskCase.caseId) [$taskStatus]"
    }
    $taskChatRows | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "chat.csv") -NoTypeInformation -Encoding UTF8
}

$taskSummary = @(
    foreach ($taskRoute in $taskRoutes) {
        $taskGroup = @($taskRows | Where-Object route -eq $taskRoute)
        if ($taskGroup.Count -gt 0) { Get-TaskMetrics -Rows $taskGroup -Route $taskRoute }
    }
)
$taskGroups = @(
    foreach ($taskRoute in $taskRoutes) {
        foreach ($taskLabel in @($taskRetrievalCases.group | Sort-Object -Unique)) {
            $taskGroup = @($taskRows | Where-Object { $_.route -eq $taskRoute -and $_.group -eq $taskLabel })
            if ($taskGroup.Count -gt 0) {
                $taskGroupMetric = Get-TaskMetrics -Rows $taskGroup -Route $taskRoute
                $taskGroupMetric | Add-Member -NotePropertyName group -NotePropertyValue $taskLabel
                $taskGroupMetric
            }
        }
    }
)
$taskSummary | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "summary.csv") -NoTypeInformation -Encoding UTF8
$taskGroups | Export-Csv -LiteralPath (Join-Path $taskRunDirectory "by-group.csv") -NoTypeInformation -Encoding UTF8
$taskBackendSourceHashes = @{}
foreach ($taskSourceName in @("KnowledgeSearchService", "LuceneKeywordSearchService", "ReciprocalRankFusionService", "HybridSearchService")) {
    $taskSourcePath = Join-Path $taskRoot ("backend\src\main\java\com\lzq\commercecare\knowledge\service\" + $taskSourceName + ".java")
    $taskBackendSourceHashes[$taskSourceName] = (Get-FileHash -LiteralPath $taskSourcePath -Algorithm SHA256).Hash
}
$taskMetadata = @{
    corpusVersion=$taskManifest.corpusVersion; datasetVersion=$taskDataset.datasetVersion; split=$Split; topK=$TopK
    filterByModel=[bool]$FilterByModel; metadataSchemaVersion=2
    baseUrl=$taskBaseUrl; manifestSha256=$taskManifestHash; casesSha256=(Get-FileHash $taskCasesPath -Algorithm SHA256).Hash
    scriptSha256=(Get-FileHash $PSCommandPath -Algorithm SHA256).Hash; powerShellVersion=$PSVersionTable.PSVersion.ToString()
    backendSourceHashes=$taskBackendSourceHashes
    applicationConfigSha256=(Get-FileHash -LiteralPath (Join-Path $taskRoot "backend\src\main\resources\application.yml") -Algorithm SHA256).Hash
    sourceCount=$taskSourcesById.Count; selectedCases=$taskSelected.Count; retrievalCases=$taskRetrievalCases.Count
    unanswerableCases=@($taskSelected | Where-Object kind -eq "unanswerable").Count
    chatEvaluated=[bool]$EvaluateChat; importSkipped=[bool]$SkipImport; finishedAt=(Get-Date -Format o)
    routes=$taskRoutes; limitations="来源标签不是逐片段语义标签；默认不计算无答案检索Recall；chat仅评价当前聊天路径；合成公开验证集不是盲测。"
}
[System.IO.File]::WriteAllText((Join-Path $taskRunDirectory "metadata.json"), ($taskMetadata | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
$taskReport = [System.Text.StringBuilder]::new()
[void]$taskReport.AppendLine("# 扩展评测运行结果")
[void]$taskReport.AppendLine("")
[void]$taskReport.AppendLine("问题集：$($taskDataset.datasetVersion)，分组：$Split，TopK=$TopK，型号过滤=$FilterByModel。")
[void]$taskReport.AppendLine("")
[void]$taskReport.AppendLine("| 路径 | 请求 | 错误 | 来源Recall | 来源标签Precision | 全部证据召回率 | MRR |")
[void]$taskReport.AppendLine("|---|---:|---:|---:|---:|---:|---:|")
foreach ($taskMetric in $taskSummary) {
    [void]$taskReport.AppendLine("| $($taskMetric.route) | $($taskMetric.cases) | $($taskMetric.errors) | $($taskMetric.sourceRecallAtK) | $($taskMetric.sourceLabelPrecisionAtK) | $($taskMetric.allRequiredSourcesRate) | $($taskMetric.meanReciprocalRank) |")
}
[void]$taskReport.AppendLine("")
[void]$taskReport.AppendLine("来源级指标不等于答案正确率。MRR使用第一个相关来源排名；同来源不同片段在Precision中分别计数。运行错误留在平均指标分母。")
[void]$taskReport.AppendLine("人工审核需填写chat.csv的manualCorrect和reviewNotes。默认未调用聊天，未评估拒答或生成幻觉。")
[System.IO.File]::WriteAllText((Join-Path $taskRunDirectory "report.md"), $taskReport.ToString(), [System.Text.UTF8Encoding]::new($false))
$taskSummary | Format-Table route, cases, errors, sourceRecallAtK, sourceLabelPrecisionAtK, allRequiredSourcesRate, meanReciprocalRank
Write-Host "结果目录：$taskRunDirectory"
if (-not $EvaluateChat) { Write-Host "无答案问题未评测；需要时添加 -EvaluateChat。" }
$taskErrorCount = @($taskRows | Where-Object { $_.error -ne "" }).Count + @($taskChatRows | Where-Object { $_.error -ne "" }).Count
if ($taskErrorCount -gt 0) { throw "评测完成但有 $taskErrorCount 个接口错误，请查看结果文件，不要将其当作正常拒答。" }
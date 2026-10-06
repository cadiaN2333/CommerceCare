param(
    [string]$BaseUrl = "http://localhost:8081"
)

$ErrorActionPreference = "Stop"

$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskDataPath = Join-Path $taskProjectRoot "datasets\evaluation\multi-source-retrieval-cases.json"
$taskDataset = Get-Content -LiteralPath $taskDataPath -Raw -Encoding UTF8 |
    ConvertFrom-Json

$taskRows = [System.Collections.Generic.List[object]]::new()

foreach ($taskCase in $taskDataset.cases) {
    $taskBody = @{
        question = $taskCase.question
        topK = $taskDataset.topK
    } | ConvertTo-Json -Compress

    $taskCandidates = @()
    $taskError = ""
    $taskTimer = [System.Diagnostics.Stopwatch]::StartNew()

    try {
        $taskWebResponse = Invoke-WebRequest `
            -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/knowledge/search" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body $taskBody `
            -TimeoutSec 45 `
            -UseBasicParsing

        $taskResponseJson = [System.Text.Encoding]::UTF8.GetString(
            $taskWebResponse.RawContentStream.ToArray()
        )
        $taskResponse = $taskResponseJson | ConvertFrom-Json
        $taskCandidates = @($taskResponse.results)
    } catch {
        $taskError = $_.Exception.Message
    } finally {
        $taskTimer.Stop()
    }

    $taskExpectedSourceIds = @($taskCase.expectedSourceIds)
    $taskActualSourceIds = @(
        $taskCandidates | ForEach-Object { $_.sourceId }
    )

    $taskMatchedExpectedIds = @(
        $taskExpectedSourceIds |
            Where-Object { $_ -in $taskActualSourceIds }
    )

    $taskRelevantCandidateCount = @(
        $taskCandidates |
            Where-Object { $_.sourceId -in $taskExpectedSourceIds }
    ).Count

    $taskRecallAtK = 0.0
    if ($taskExpectedSourceIds.Count -gt 0) {
        $taskRecallAtK = [Math]::Round(
            $taskMatchedExpectedIds.Count / $taskExpectedSourceIds.Count,
            3
        )
    }

    $taskPrecisionAtK = 0.0
    if ($taskCandidates.Count -gt 0) {
        $taskPrecisionAtK = [Math]::Round(
            $taskRelevantCandidateCount / $taskCandidates.Count,
            3
        )
    }

    $taskPrimaryRank = 0
    if ($taskExpectedSourceIds.Count -gt 0) {
        for ($taskIndex = 0; $taskIndex -lt $taskCandidates.Count; $taskIndex++) {
            if ($taskCandidates[$taskIndex].sourceId -eq $taskExpectedSourceIds[0]) {
                $taskPrimaryRank = $taskIndex + 1
                break
            }
        }
    }

    $taskCandidateDetails = [System.Collections.Generic.List[string]]::new()

    for ($taskIndex = 0; $taskIndex -lt $taskCandidates.Count; $taskIndex++) {
        $taskCandidate = $taskCandidates[$taskIndex]
        $taskChunkIndex = $taskCandidate.chunkIndex
        $taskScore = ""

        if ($null -ne $taskCandidate.score) {
            $taskScore = $taskCandidate.score.ToString(
                "0.000000",
                [System.Globalization.CultureInfo]::InvariantCulture
            )
        }

        $taskCandidateDetails.Add(
            "rank=$($taskIndex + 1); sourceId=$($taskCandidate.sourceId); chunkIndex=$taskChunkIndex; score=$taskScore"
        )
    }

    $taskRows.Add([PSCustomObject]@{
        datasetVersion = $taskDataset.datasetVersion
        caseId = $taskCase.caseId
        group = $taskCase.group
        question = $taskCase.question
        topK = $taskDataset.topK
        expectedSourceIds = $taskExpectedSourceIds -join "|"
        retrievedSources = $taskCandidateDetails -join " || "
        recallAtK = $taskRecallAtK
        precisionAtK = $taskPrecisionAtK
        primarySourceRank = $taskPrimaryRank
        elapsedMs = $taskTimer.ElapsedMilliseconds
        error = $taskError
    })

    Write-Host (
        "{0}: Recall@{1}={2}; Precision@{1}={3}; 主来源排名={4}" -f
        $taskCase.caseId,
        $taskDataset.topK,
        $taskRecallAtK,
        $taskPrecisionAtK,
        $taskPrimaryRank
    )
}

$taskOutputDirectory = Join-Path $taskProjectRoot "artifacts\evaluation"
New-Item -ItemType Directory -Path $taskOutputDirectory -Force | Out-Null

$taskTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$taskOutputPath = Join-Path `
    $taskOutputDirectory `
    "multi-source-retrieval-scores-$taskTimestamp.csv"

$taskRows |
    Export-Csv -LiteralPath $taskOutputPath -NoTypeInformation -Encoding UTF8

$taskSuccessfulRows = @(
    $taskRows | Where-Object { [string]::IsNullOrEmpty($_.error) }
)

$taskSummary = [PSCustomObject]@{
    datasetVersion = $taskDataset.datasetVersion
    cases = $taskRows.Count
    successfulRequests = $taskSuccessfulRows.Count
    meanRecallAtK = if ($taskSuccessfulRows.Count -gt 0) {
        [Math]::Round(
            ($taskSuccessfulRows |
                Measure-Object -Property recallAtK -Average).Average,
            3
        )
    } else {
        $null
    }
    meanPrecisionAtK = if ($taskSuccessfulRows.Count -gt 0) {
        [Math]::Round(
            ($taskSuccessfulRows |
                Measure-Object -Property precisionAtK -Average).Average,
            3
        )
    } else {
        $null
    }
    outputCsv = $taskOutputPath
}

$taskSummary | ConvertTo-Json -Depth 4
$taskRows |
    Format-Table caseId, recallAtK, precisionAtK, primarySourceRank, elapsedMs
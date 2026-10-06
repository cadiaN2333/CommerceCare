param(
    [string]$BaseUrl = "http://localhost:8081"
)

$ErrorActionPreference = "Stop"

$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskDataPath = Join-Path $taskProjectRoot "datasets\evaluation\basic-rag-cases.json"
$taskDataset = Get-Content -LiteralPath $taskDataPath -Raw -Encoding UTF8 |
    ConvertFrom-Json

$taskRows = [System.Collections.Generic.List[object]]::new()

foreach ($taskCase in $taskDataset.cases) {
    $taskBody = @{
        question = $taskCase.question
        topK = 3
    } | ConvertTo-Json -Compress

    $taskActualStatus = "ERROR"
    $taskAnswer = ""
    $taskSourceMatch = $false
    $taskError = ""
    $taskTimer = [System.Diagnostics.Stopwatch]::StartNew()

    try {
        $taskWebResponse = Invoke-WebRequest `
            -Uri "$($BaseUrl.TrimEnd('/'))/api/v1/chat" `
            -Method Post `
            -ContentType "application/json; charset=utf-8" `
            -Body $taskBody `
            -TimeoutSec 45 `
            -UseBasicParsing

        $taskResponseJson = [System.Text.Encoding]::UTF8.GetString(
            $taskWebResponse.RawContentStream.ToArray()
        )
        $taskResponse = $taskResponseJson | ConvertFrom-Json

        if ([string]::IsNullOrWhiteSpace($taskResponse.status)) {
            throw "响应缺少业务状态。"
        }

        $taskActualStatus = $taskResponse.status
        $taskAnswer = $taskResponse.answer

        if ($taskCase.expectedStatus -eq "ANSWERED") {
            $taskSourceMatch = @(
                $taskResponse.sources |
                    Where-Object {
                        $_.sourceId -eq $taskCase.expectedSourceId
                    }
            ).Count -gt 0
        } else {
            $taskSourceMatch = @(
                $taskResponse.sources
            ).Count -eq 0
        }
    } catch {
        $taskError = $_.Exception.Message
    } finally {
        $taskTimer.Stop()
    }

    $taskRows.Add([PSCustomObject]@{
        datasetVersion = $taskDataset.datasetVersion
        caseId = $taskCase.caseId
        group = $taskCase.group
        question = $taskCase.question
        topK = 3
        expectedStatus = $taskCase.expectedStatus
        actualStatus = $taskActualStatus
        statusMatch = $taskActualStatus -eq $taskCase.expectedStatus
        sourceMatch = $taskSourceMatch
        elapsedMs = $taskTimer.ElapsedMilliseconds
        answer = $taskAnswer
        expectedAnswerPoints = $taskCase.expectedAnswerPoints
        manualCorrect = ""
        reviewNotes = ""
        error = $taskError
    })

    Write-Host "$($taskCase.caseId): $taskActualStatus"
}

$taskOutputDirectory = Join-Path $taskProjectRoot "artifacts\evaluation"
New-Item -ItemType Directory -Path $taskOutputDirectory -Force | Out-Null

$taskTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$taskOutputPath = Join-Path $taskOutputDirectory "basic-rag-$taskTimestamp.csv"

$taskRows |
    Export-Csv -LiteralPath $taskOutputPath -NoTypeInformation -Encoding UTF8

$taskRows |
    Format-Table caseId, expectedStatus, actualStatus, statusMatch, sourceMatch, elapsedMs

Write-Host "结果已保存：$taskOutputPath"
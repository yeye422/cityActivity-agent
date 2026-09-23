param(
    [string]$BaseUrl = "http://localhost:8080",
    [long]$UserId = 999999,
    [int]$Limit = 10,
    [switch]$PromoteBaseline,
    [string]$BaselineName = ""
)

$ErrorActionPreference = "Stop"

$headers = @{
    "X-User-Id" = $UserId.ToString()
    "Content-Type" = "application/json"
}

$body = @{
    suite = "react"
    includeLlmJudge = $false
    limit = $Limit
} | ConvertTo-Json

Write-Host "Running react-v1 regression against $BaseUrl ..."
$result = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/city/evaluations/regression" `
    -Headers $headers `
    -Body $body

Write-Host ""
Write-Host "runId:            $($result.runId)"
Write-Host "evalSetVersion:   $($result.evalSetVersion)"
Write-Host "gitCommit:        $($result.gitCommit)"
Write-Host "baselineRunId:    $($result.baselineRunId)"
Write-Host "avgScore:         $($result.report.avgScore)"
Write-Host "passed:           $($result.passed)"

$metrics = $result.report.metricAverages
$metricNames = @(
    "reactRouteCoverage",
    "recommendationReactSuccessRate",
    "planningReactSuccessRate",
    "reactDegradationRate",
    "latencyP50Ms",
    "latencyP95Ms",
    "reactToolCallCount",
    "toolErrorRate",
    "retrievalToolCallCount",
    "reRetrievalRate",
    "userGoalCoverage",
    "candidateOutOfSetRate",
    "travelToolCallCount",
    "planValidationCallCount",
    "planValidationFailureRate",
    "planRepairSuccessRate",
    "planValidRate",
    "planValidationTimeConflictRate",
    "planValidationBudgetViolationRate",
    "sessionHallucinationRate",
    "evidenceViolationRate",
    "reactReleaseGatePass",
    "reactReleaseGateFailureCount"
)

Write-Host ""
Write-Host "ReAct metrics:"
foreach ($name in $metricNames) {
    $property = $metrics.PSObject.Properties[$name]
    if ($null -ne $property) {
        Write-Host ("  {0,-34} {1}" -f $name, $property.Value)
    }
}

if (-not $result.passed) {
    Write-Error "react-v1 did not pass the release/regression gate. Baseline will not be promoted."
    exit 2
}

if ($PromoteBaseline) {
    $name = $BaselineName
    if ([string]::IsNullOrWhiteSpace($name)) {
        $shortCommit = $result.gitCommit
        if ([string]::IsNullOrWhiteSpace($shortCommit)) {
            $shortCommit = "unknown"
        } elseif ($shortCommit.Length -gt 12) {
            $shortCommit = $shortCommit.Substring(0, 12)
        }
        $name = "react-v1-$shortCommit"
    }

    $baselineBody = @{
        runId = $result.runId
        baselineName = $name
    } | ConvertTo-Json

    $baseline = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/city/evaluations/regression/baseline" `
        -Headers $headers `
        -Body $baselineBody

    Write-Host ""
    Write-Host "Promoted baseline: $($baseline.runId) / $($baseline.baselineName)"
}

exit 0

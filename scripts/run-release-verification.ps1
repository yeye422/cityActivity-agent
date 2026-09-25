param(
    [string]$BaseUrl = "http://localhost:8080",
    [long]$UserId = 999999,
    [int]$ReactLimit = 10,
    [int]$RetrievalK = 5,
    [string]$BaselineName = "",
    [string]$RetrievalOutputPath = "",
    [string]$ReactOutputDirectory = ""
)

$ErrorActionPreference = "Stop"

$reactScript = Join-Path $PSScriptRoot "run-react-regression.ps1"
$retrievalScript = Join-Path $PSScriptRoot "run-retrieval-baseline.ps1"
if (-not (Test-Path $reactScript)) { throw "Missing script: $reactScript" }
if (-not (Test-Path $retrievalScript)) { throw "Missing script: $retrievalScript" }

if (-not [string]::IsNullOrWhiteSpace($ReactOutputDirectory)) {
    New-Item -ItemType Directory -Force -Path $ReactOutputDirectory | Out-Null
}

$reactFirstPath = if ([string]::IsNullOrWhiteSpace($ReactOutputDirectory)) {
    Join-Path ([System.IO.Path]::GetTempPath()) "city-react-first.json"
} else {
    Join-Path $ReactOutputDirectory "react-first.json"
}
$reactStabilityPath = if ([string]::IsNullOrWhiteSpace($ReactOutputDirectory)) {
    Join-Path ([System.IO.Path]::GetTempPath()) "city-react-stability.json"
} else {
    Join-Path $ReactOutputDirectory "react-stability.json"
}

Write-Host ""
Write-Host "=== 1/4 react-v1 absolute gate ==="
$firstParams = @{
    BaseUrl = $BaseUrl
    UserId = $UserId
    Limit = $ReactLimit
    PromoteBaseline = $true
    ContinueOnGateFailure = $true
    OutputPath = $reactFirstPath
}
if (-not [string]::IsNullOrWhiteSpace($BaselineName)) {
    $firstParams.BaselineName = $BaselineName
}
& $reactScript @firstParams

if (-not (Test-Path $reactFirstPath)) {
    throw "react-v1 first run did not produce report: $reactFirstPath"
}
$reactFirst = Get-Content -Raw -Path $reactFirstPath | ConvertFrom-Json
$reactFirstPassed = [bool]$reactFirst.passed

Write-Host ""
Write-Host "=== 2/4 retrieval-v1 CURRENT_PIPELINE baseline ==="
$retrievalParams = @{
    BaseUrl = $BaseUrl
    UserId = $UserId
    K = $RetrievalK
}
if (-not [string]::IsNullOrWhiteSpace($RetrievalOutputPath)) {
    $retrievalParams.OutputPath = $RetrievalOutputPath
}
& $retrievalScript @retrievalParams
if ($LASTEXITCODE -ne 0) {
    throw "retrieval-v1 failed with exit code $LASTEXITCODE"
}

$reactStabilityPassed = $true
$stabilitySkipped = -not $reactFirstPassed
Write-Host ""
Write-Host "=== 3/4 react-v1 same-SHA stability gate ==="
if ($stabilitySkipped) {
    Write-Warning "Skipping stability run because the absolute react-v1 gate did not pass."
} else {
    $stabilityParams = @{
        BaseUrl = $BaseUrl
        UserId = $UserId
        Limit = $ReactLimit
        ContinueOnGateFailure = $true
        OutputPath = $reactStabilityPath
    }
    & $reactScript @stabilityParams

    if (-not (Test-Path $reactStabilityPath)) {
        throw "react-v1 stability run did not produce report: $reactStabilityPath"
    }
    $reactStability = Get-Content -Raw -Path $reactStabilityPath | ConvertFrom-Json
    $reactStabilityPassed = [bool]$reactStability.passed
}

Write-Host ""
Write-Host "=== 4/4 release verification summary ==="
Write-Host "react absolute passed: $reactFirstPassed"
Write-Host "retrieval baseline:     completed"
Write-Host "stability skipped:      $stabilitySkipped"
Write-Host "stability passed:       $reactStabilityPassed"

if (-not $reactFirstPassed) {
    Write-Error "Release verification failed: react-v1 absolute gate did not pass."
    exit 2
}
if (-not $reactStabilityPassed) {
    Write-Error "Release verification failed: same-SHA react-v1 stability gate did not pass."
    exit 3
}

Write-Host "Release verification completed successfully."
exit 0

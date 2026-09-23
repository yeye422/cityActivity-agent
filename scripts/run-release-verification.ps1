param(
    [string]$BaseUrl = "http://localhost:8080",
    [long]$UserId = 999999,
    [int]$ReactLimit = 10,
    [int]$RetrievalK = 5,
    [string]$BaselineName = "",
    [string]$RetrievalOutputPath = ""
)

$ErrorActionPreference = "Stop"

function Invoke-Step {
    param([string]$Name, [scriptblock]$Action)
    Write-Host ""
    Write-Host "=== $Name ==="
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

$reactScript = Join-Path $PSScriptRoot "run-react-regression.ps1"
$retrievalScript = Join-Path $PSScriptRoot "run-retrieval-baseline.ps1"
if (-not (Test-Path $reactScript)) { throw "Missing script: $reactScript" }
if (-not (Test-Path $retrievalScript)) { throw "Missing script: $retrievalScript" }

Invoke-Step "1/4 react-v1 first run + promote baseline" {
    $args = @("-ExecutionPolicy","Bypass","-File",$reactScript,"-BaseUrl",$BaseUrl,"-UserId",$UserId,"-Limit",$ReactLimit,"-PromoteBaseline")
    if (-not [string]::IsNullOrWhiteSpace($BaselineName)) { $args += @("-BaselineName",$BaselineName) }
    & powershell @args
}

Invoke-Step "2/4 react-v1 regression against promoted baseline" {
    & powershell -ExecutionPolicy Bypass -File $reactScript -BaseUrl $BaseUrl -UserId $UserId -Limit $ReactLimit
}

Invoke-Step "3/4 retrieval-v1 CURRENT_PIPELINE baseline" {
    $args = @("-ExecutionPolicy","Bypass","-File",$retrievalScript,"-BaseUrl",$BaseUrl,"-UserId",$UserId,"-K",$RetrievalK)
    if (-not [string]::IsNullOrWhiteSpace($RetrievalOutputPath)) { $args += @("-OutputPath",$RetrievalOutputPath) }
    & powershell @args
}

Write-Host ""
Write-Host "=== 4/4 release verification completed ==="
Write-Host "react-v1 absolute gate + baseline + regression gate + retrieval-v1 baseline all completed."
exit 0

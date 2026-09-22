param(
    [string]$BaseUrl = "http://localhost:8080",
    [long]$UserId = 999999,
    [int]$K = 5,
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"

$headers = @{
    "X-User-Id" = $UserId.ToString()
    "Content-Type" = "application/json"
}

$body = @{
    k = $K
} | ConvertTo-Json

Write-Host "Running retrieval baseline against $BaseUrl ..."
$result = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/api/v1/city/evaluations/retrieval" `
    -Headers $headers `
    -Body $body

Write-Host ""
Write-Host "evalSetVersion:              $($result.evalSetVersion)"
Write-Host "evalSetHash:                 $($result.evalSetHash)"
Write-Host "gitCommit:                   $($result.gitCommit)"
Write-Host "strategy:                    $($result.strategy)"
Write-Host "k:                           $($result.k)"
Write-Host "totalCases:                  $($result.summary.totalCases)"
Write-Host "recallAtK:                   $($result.summary.recallAtK)"
Write-Host "ndcgAtK:                     $($result.summary.ndcgAtK)"
Write-Host "noResultFalsePositiveRate:   $($result.summary.noResultFalsePositiveRate)"

Write-Host ""
Write-Host "Case results:"
foreach ($case in $result.cases) {
    Write-Host ("  {0,-30} raw={1,-3} ranked={2,-3} recall={3,-8} ndcg={4,-8} noResultFP={5}" -f `
        $case.caseId,
        $case.rawCandidateCount,
        $case.rankedCandidateCount,
        $case.recallAtK,
        $case.ndcgAtK,
        $case.noResultFalsePositive)
}

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent) -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }

    $result | ConvertTo-Json -Depth 12 | Set-Content -Path $OutputPath -Encoding UTF8
    Write-Host ""
    Write-Host "Saved retrieval baseline report to: $OutputPath"
}

exit 0

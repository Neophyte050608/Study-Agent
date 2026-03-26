$baseUrl = "http://localhost:9596"
$path = "D:\Practice\InterviewReview\knowledge\obsidian\vault"
$ignoreDirs = ""
$reportPath = "D:\Practice\InterviewReview\specs\rag-parent-child-retrieval\reindex-report.json"

if ($args.Length -ge 1 -and -not [string]::IsNullOrWhiteSpace($args[0])) {
    $path = $args[0]
}

if ($args.Length -ge 2) {
    $ignoreDirs = $args[1]
}

if ($args.Length -ge 3 -and -not [string]::IsNullOrWhiteSpace($args[2])) {
    $reportPath = $args[2]
}

Write-Host "== 1) 触发 Parent-Child 全量重建 ==" -ForegroundColor Cyan
$body = @{
    path = $path
    ignoreDirs = $ignoreDirs
} | ConvertTo-Json -Depth 6

try {
    $reindexResponse = Invoke-RestMethod -Uri "$baseUrl/api/ingestion/reindex/parent-child" -Method Post -ContentType "application/json; charset=utf-8" -Body $body
    $reindexResponse | ConvertTo-Json -Depth 8
} catch {
    Write-Error "触发重建失败：$($_.Exception.Message)"
    exit 1
}

Write-Host "`n== 2) 拉取重建统计报告 ==" -ForegroundColor Cyan
try {
    $report = Invoke-RestMethod -Uri "$baseUrl/api/ingestion/reindex/parent-child/report" -Method Get
    $report | ConvertTo-Json -Depth 8
} catch {
    Write-Error "拉取报告失败：$($_.Exception.Message)"
    exit 1
}

Write-Host "`n== 3) 写入本地报告文件 ==" -ForegroundColor Cyan
$reportDir = Split-Path -Path $reportPath -Parent
if (-not [string]::IsNullOrWhiteSpace($reportDir) -and -not (Test-Path $reportDir)) {
    New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
}
$report | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $reportPath
Write-Host "报告已写入：$reportPath" -ForegroundColor Green

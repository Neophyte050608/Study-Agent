$baseUrl = "http://localhost:9596"

Write-Host "== 1) Check Routing Stats ==" -ForegroundColor Cyan
try {
    Invoke-RestMethod -Uri "$baseUrl/api/model-routing/stats" -Method Get | ConvertTo-Json -Depth 8
} catch {
    Write-Error "Failed to connect to backend. Is the server running on $baseUrl?"
    exit
}

Write-Host "`n== 2) Triggering Business Request (Observation Mode) ==" -ForegroundColor Cyan
# 使用英文作为参数，避免编码解析问题
$body = @{
    taskType = "CODING_PRACTICE"
    payload = @{
        action = "start"
        topic = "Java"
        type = "ALGORITHM" 
        difficulty = "medium"
        count = 1
    }
    context = @{
        history = ""
        userId = "fault-drill-user"
    }
} | ConvertTo-Json -Depth 8

try {
    $response = Invoke-RestMethod -Uri "$baseUrl/api/task/dispatch" -Method Post -ContentType "application/json; charset=utf-8" -Body $body
    $response | ConvertTo-Json -Depth 8
} catch {
    Write-Host "Request failed as expected if testing failover, or check backend logs." -ForegroundColor Yellow
    $_.Exception.Message
}

Write-Host "`n== 3) Check Stats Again (Observe Counters) ==" -ForegroundColor Cyan
Invoke-RestMethod -Uri "$baseUrl/api/model-routing/stats" -Method Get | ConvertTo-Json -Depth 8

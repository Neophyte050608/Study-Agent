# ==========================================
# InterviewReview 环境一键启动脚本
# ==========================================

Write-Host "正在启动 InterviewReview 依赖的 Docker 容器环境 (Milvus, Neo4j, MySQL, Redis, RocketMQ)..." -ForegroundColor Cyan

# 启动 Docker Compose 中定义的所有服务
docker-compose up -d

Write-Host "容器启动命令已发送，等待服务就绪..." -ForegroundColor Cyan

# 等待 MySQL, Redis, RocketMQ 就绪
$maxRetries = 30
$retryCount = 0
$mysqlReady = $false
$redisReady = $false
$mqReady = $false

while (($retryCount -lt $maxRetries) -and (-not ($mysqlReady -and $redisReady -and $mqReady))) {
    Start-Sleep -Seconds 2
    $retryCount++
    
    if (-not $mysqlReady) {
        $mysqlStatus = docker inspect -f '{{.State.Health.Status}}' mysql-8 2>$null
        if ($mysqlStatus -eq "healthy") {
            $mysqlReady = $true
            Write-Host "MySQL 服务已就绪！" -ForegroundColor Green
        }
    }
    
    if (-not $redisReady) {
        $redisStatus = docker inspect -f '{{.State.Status}}' redis 2>$null
        if ($redisStatus -eq "running") {
            $redisReady = $true
            Write-Host "Redis 服务已就绪！" -ForegroundColor Green
        }
    }

    if (-not $mqReady) {
        $mqStatus = docker inspect -f '{{.State.Status}}' rmqbroker 2>$null
        if ($mqStatus -eq "running") {
            $mqReady = $true
            Write-Host "RocketMQ 服务已就绪！" -ForegroundColor Green
        }
    }
    
    Write-Host "等待服务初始化... ($retryCount/$maxRetries)" -ForegroundColor Yellow
}

if ($mysqlReady -and $redisReady -and $mqReady) {
    Write-Host "所有核心数据服务均已就绪！您可以启动 Spring Boot 项目了。" -ForegroundColor Green
} else {
    Write-Host "服务启动超时，请使用 docker ps 查看容器状态。" -ForegroundColor Red
}

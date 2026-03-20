# 停止当前项目的组件
Write-Host "Stopping Milvus environment..."
docker-compose -f "d:\Practice\InterviewReview\docker-compose.yml" stop

# 停止另一个目录中的 Redis 和 RocketMQ
Write-Host "Stopping Redis and RocketMQ..."
Push-Location "D:\Tools\NFTurbo_DockerCompose_2025_11_10\NFTurbo_DockerCompose"
docker-compose stop redis namesrv broker
Pop-Location

Write-Host "All middleware stopped successfully!"
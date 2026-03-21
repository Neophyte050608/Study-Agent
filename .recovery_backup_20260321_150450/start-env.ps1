# 启动当前项目需要的基础组件（Milvus 及其依赖）
Write-Host "Starting Milvus environment..."
docker-compose -f "d:\Practice\InterviewReview\docker-compose.yml" up -d

# 切换到另一个包含其他中间件的目录，按需启动本项目需要的 Redis 和 RocketMQ
Write-Host "Starting Redis and RocketMQ..."
Push-Location "D:\Tools\NFTurbo_DockerCompose_2025_11_10\NFTurbo_DockerCompose"
# 仅启动需要的服务：redis, namesrv, broker (根据 docker-compose.yml 中定义的服务名)
docker-compose up -d redis namesrv broker
Pop-Location

Write-Host "All required middleware started successfully!"

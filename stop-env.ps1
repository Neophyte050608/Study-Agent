# ==========================================
# InterviewReview 环境一键停止脚本
# ==========================================

Write-Host "正在停止 InterviewReview 依赖的所有 Docker 容器..." -ForegroundColor Cyan

# 停止并移除 Docker Compose 中定义的所有服务
docker-compose down

Write-Host "所有环境容器已停止并移除。" -ForegroundColor Green
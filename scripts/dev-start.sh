#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV_DIR="$ROOT_DIR/.dev"
LOG_DIR="$DEV_DIR/logs"
BACKEND_PID_FILE="$DEV_DIR/backend.pid"
FRONTEND_PID_FILE="$DEV_DIR/frontend.pid"
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"
DEPENDENCY_SERVICES=(mysql redis etcd minio milvus neo4j)

mkdir -p "$LOG_DIR"

info() {
  printf '\033[1;34m[dev-start]\033[0m %s\n' "$*"
}

warn() {
  printf '\033[1;33m[dev-start]\033[0m %s\n' "$*"
}

fail() {
  printf '\033[1;31m[dev-start]\033[0m %s\n' "$*" >&2
  exit 1
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

is_pid_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

read_pid_file() {
  local file="$1"
  [[ -f "$file" ]] && cat "$file" || true
}

ensure_not_running() {
  local name="$1"
  local pid_file="$2"
  local pid
  pid="$(read_pid_file "$pid_file")"
  if is_pid_running "$pid"; then
    fail "$name 已在运行，PID=$pid。如需重启，请先执行: bash scripts/dev-stop.sh"
  fi
  rm -f "$pid_file"
}

wait_for_port() {
  local name="$1"
  local host="$2"
  local port="$3"
  local attempts="${4:-60}"

  info "等待 $name 就绪: $host:$port"
  for ((i = 1; i <= attempts; i++)); do
    if command_exists nc; then
      if nc -z "$host" "$port" >/dev/null 2>&1; then
        info "$name 已就绪"
        return 0
      fi
    else
      if (echo >"/dev/tcp/$host/$port") >/dev/null 2>&1; then
        info "$name 已就绪"
        return 0
      fi
    fi
    sleep 2
  done
  fail "$name 在 $((attempts * 2)) 秒内未就绪，请检查 docker compose 日志"
}

cleanup_children() {
  warn "收到退出信号，正在停止后端/前端进程..."
  bash "$ROOT_DIR/scripts/dev-stop.sh" --app-only >/dev/null 2>&1 || true
}

check_prerequisites() {
  command_exists docker || fail "未找到 docker，请先安装并启动 Docker"
  command_exists mvn || fail "未找到 mvn，请先安装 Maven"
  command_exists npm || fail "未找到 npm，请先安装 Node.js/npm"

  if ! docker info >/dev/null 2>&1; then
    fail "Docker 未运行，请先启动 Docker Desktop 或 Docker 服务"
  fi

  if [[ ! -d "$ROOT_DIR/frontend/node_modules" ]]; then
    fail "前端依赖未安装。请先执行: cd frontend && npm install"
  fi
}

start_dependencies() {
  info "启动基础依赖容器: ${DEPENDENCY_SERVICES[*]}"
  (cd "$ROOT_DIR" && docker compose up -d "${DEPENDENCY_SERVICES[@]}")

  wait_for_port "MySQL" "localhost" "3307" 60
  wait_for_port "Redis" "localhost" "6379" 60
  wait_for_port "Milvus" "localhost" "19530" 90
  wait_for_port "Neo4j" "localhost" "7687" 60
}

start_backend() {
  ensure_not_running "后端" "$BACKEND_PID_FILE"
  info "启动后端 local-lite，日志: $BACKEND_LOG"
  (
    cd "$ROOT_DIR"
    mvn spring-boot:run -Dspring-boot.run.profiles=local-lite >"$BACKEND_LOG" 2>&1
  ) &
  echo $! >"$BACKEND_PID_FILE"
  info "后端 PID=$(cat "$BACKEND_PID_FILE")，端口: 9596"
}

start_frontend() {
  ensure_not_running "前端" "$FRONTEND_PID_FILE"
  info "启动前端 Vite，日志: $FRONTEND_LOG"
  (
    cd "$ROOT_DIR/frontend"
    npm run dev -- --host 0.0.0.0 >"$FRONTEND_LOG" 2>&1
  ) &
  echo $! >"$FRONTEND_PID_FILE"
  info "前端 PID=$(cat "$FRONTEND_PID_FILE")，端口: 5173"
}

print_summary() {
  cat <<SUMMARY

启动完成：
- 后端: http://localhost:9596
- 前端: http://localhost:5173
- 后端日志: $BACKEND_LOG
- 前端日志: $FRONTEND_LOG

停止应用进程：
  bash scripts/dev-stop.sh

停止应用进程和 Docker 依赖：
  bash scripts/dev-stop.sh --with-docker

当前脚本会持续挂起并监控后端/前端进程；按 Ctrl+C 会停止后端/前端，但保留 Docker 依赖。
SUMMARY
}

monitor_processes() {
  while true; do
    local backend_pid frontend_pid
    backend_pid="$(read_pid_file "$BACKEND_PID_FILE")"
    frontend_pid="$(read_pid_file "$FRONTEND_PID_FILE")"

    if ! is_pid_running "$backend_pid"; then
      warn "后端进程已退出，请查看日志: $BACKEND_LOG"
      exit 1
    fi
    if ! is_pid_running "$frontend_pid"; then
      warn "前端进程已退出，请查看日志: $FRONTEND_LOG"
      exit 1
    fi
    sleep 3
  done
}

main() {
  trap cleanup_children INT TERM
  check_prerequisites
  start_dependencies
  start_backend
  start_frontend
  print_summary
  monitor_processes
}

main "$@"

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV_DIR="$ROOT_DIR/.dev"
BACKEND_PID_FILE="$DEV_DIR/backend.pid"
FRONTEND_PID_FILE="$DEV_DIR/frontend.pid"
DEPENDENCY_SERVICES=(mysql redis etcd minio milvus neo4j)
WITH_DOCKER=false
APP_ONLY=false

for arg in "$@"; do
  case "$arg" in
    --with-docker)
      WITH_DOCKER=true
      ;;
    --app-only)
      APP_ONLY=true
      ;;
    *)
      printf '未知参数: %s\n' "$arg" >&2
      exit 2
      ;;
  esac
done

info() {
  printf '\033[1;34m[dev-stop]\033[0m %s\n' "$*"
}

warn() {
  printf '\033[1;33m[dev-stop]\033[0m %s\n' "$*"
}

is_pid_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

stop_pid_file() {
  local name="$1"
  local pid_file="$2"
  if [[ ! -f "$pid_file" ]]; then
    info "$name 未记录 PID，跳过"
    return 0
  fi

  local pid
  pid="$(cat "$pid_file")"
  if is_pid_running "$pid"; then
    info "停止 $name，PID=$pid"
    kill "$pid" >/dev/null 2>&1 || true
    for _ in {1..20}; do
      if ! is_pid_running "$pid"; then
        rm -f "$pid_file"
        info "$name 已停止"
        return 0
      fi
      sleep 0.5
    done
    warn "$name 未正常退出，强制终止 PID=$pid"
    kill -9 "$pid" >/dev/null 2>&1 || true
  else
    info "$name PID=$pid 不在运行"
  fi
  rm -f "$pid_file"
}

stop_apps() {
  stop_pid_file "前端" "$FRONTEND_PID_FILE"
  stop_pid_file "后端" "$BACKEND_PID_FILE"
}

stop_docker() {
  info "停止基础依赖容器: ${DEPENDENCY_SERVICES[*]}"
  (cd "$ROOT_DIR" && docker compose stop "${DEPENDENCY_SERVICES[@]}")
}

main() {
  stop_apps
  if [[ "$WITH_DOCKER" == "true" && "$APP_ONLY" != "true" ]]; then
    stop_docker
  fi
}

main "$@"

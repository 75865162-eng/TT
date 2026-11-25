#!/usr/bin/env bash
# Cloudflare Tunnel 守护：用 token 连接 CF，把本机 8080 暴露到你的固定域名。
# 用法：
#   TOKEN 来源优先级：环境变量 CF_TUNNEL_TOKEN > /workspace/woker/deploy/wimoor-p0/.cf-token
#   ./cf-tunnel.sh start   # 启动守护
#   ./cf-tunnel.sh stop    # 停止
#   ./cf-tunnel.sh status  # 查看状态
set -u

DIR=/workspace/woker/deploy/wimoor-p0
BIN="$DIR/cloudflared"
TOKEN_FILE="$DIR/.cf-token"
LOG=/tmp/cf-tunnel.log
PIDFILE=/tmp/cf-tunnel.pid

get_token() {
  if [ -n "${CF_TUNNEL_TOKEN:-}" ]; then
    echo "$CF_TUNNEL_TOKEN"
  elif [ -f "$TOKEN_FILE" ]; then
    cat "$TOKEN_FILE" | tr -d '\r\n'
  else
    echo ""
  fi
}

start() {
  local TOKEN
  TOKEN=$(get_token)
  if [ -z "$TOKEN" ]; then
    echo "[ERROR] 未找到 Tunnel token。请设置环境变量 CF_TUNNEL_TOKEN 或写入 $TOKEN_FILE"
    exit 1
  fi
  if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "[INFO] 守护已在运行 PID=$(cat "$PIDFILE")"
    return 0
  fi
  : > "$LOG"
  nohup "$BIN" tunnel --no-autoupdate run --token "$TOKEN" >> "$LOG" 2>&1 &
  echo $! > "$PIDFILE"
  echo "[INFO] Cloudflare Tunnel 已启动 PID=$(cat "$PIDFILE")，日志：$LOG"
  sleep 3
  status
}

stop() {
  if [ -f "$PIDFILE" ]; then
    local PID
    PID=$(cat "$PIDFILE")
    kill "$PID" 2>/dev/null && echo "[INFO] 已停止 PID=$PID"
    rm -f "$PIDFILE"
  else
    echo "[INFO] 未发现运行中的守护"
  fi
  pkill -f "cloudflared tunnel --no-autoupdate run --token" 2>/dev/null || true
}

status() {
  echo "=== 进程 ==="
  if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "运行中 PID=$(cat "$PIDFILE")"
  else
    echo "未运行"
  fi
  echo "=== 最近日志 ==="
  tail -n 15 "$LOG" 2>/dev/null || echo "(无日志)"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "用法：$0 {start|stop|status}" ;;
esac

#!/usr/bin/env bash
# localhost.run 隧道守护：断开自动重连，URL 写入 /tmp/tunnel-url.txt
LOG=/tmp/lhr.log
URLFILE=/tmp/tunnel-url.txt
: > "$LOG"
while true; do
  # 每次重连清空旧 URL 标记
  rm -f "$URLFILE"
  ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
      -o ExitOnForwardFailure=yes -o TCPKeepAlive=yes \
      -R 80:localhost:8080 nokey@localhost.run >> "$LOG" 2>&1 &
  SSH_PID=$!
  echo $SSH_PID > /tmp/lhr.pid
  # 等待 URL 出现
  for i in $(seq 1 30); do
    URL=$(grep -oE "https://[A-Za-z0-9.-]+\.lhr\.life" "$LOG" 2>/dev/null | tail -1)
    if [ -n "$URL" ]; then echo "$URL" > "$URLFILE"; break; fi
    kill -0 $SSH_PID 2>/dev/null || break
    sleep 1
  done
  # 等待 ssh 结束（断开）
  wait $SSH_PID 2>/dev/null
  echo "[$(date)] 隧道断开，3秒后重连" >> "$LOG"
  sleep 3
done

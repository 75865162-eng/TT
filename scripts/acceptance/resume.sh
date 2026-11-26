#!/usr/bin/env bash
# 抗休眠"一键继续"：拉起服务 → 断点续跑验收（分批）→ 打印进度。
# 沙箱重启后，只要重跑这一条命令，就会从上次断点继续，不重复已测页面。
#
# 用法：
#   bash scripts/acceptance/resume.sh          # 默认每批 25 页
#   bash scripts/acceptance/resume.sh 40       # 指定每批 40 页
#   bash scripts/acceptance/resume.sh --report # 只看进度，不测
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

BATCH="${1:-25}"
if [ "$BATCH" = "--report" ]; then
  node scripts/acceptance/resilient-acceptance.mjs --report
  exit 0
fi

echo "=== [1/2] 确保服务就绪 ==="
bash deploy/wimoor-p0/start-all.sh >/tmp/resume-services.log 2>&1 &
SVC_PID=$!
# 等待前端可用，最多 150 秒（期间服务脚本在后台跑）
for i in $(seq 1 75); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8080/ 2>/dev/null)
  [ "$CODE" = "200" ] && { echo "服务就绪"; break; }
  sleep 2
done
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8080/ 2>/dev/null)
if [ "$CODE" != "200" ]; then
  echo "服务仍未就绪，查看 /tmp/resume-services.log。本次跳过验收，进度不丢，稍后重试。"
  exit 2
fi

echo "=== [2/2] 断点续跑验收（本批 $BATCH 页）==="
node scripts/acceptance/resilient-acceptance.mjs --batch "$BATCH" --ensure-services

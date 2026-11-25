#!/usr/bin/env bash
# 一键恢复生产栈 + 公网隧道（沙箱休眠后刷新用）
# 用法: bash deploy/wimoor-p0/restore.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
P0="$ROOT/deploy/wimoor-p0"
WM="$ROOT/wimoor"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export JAVA_HOME

echo "== 1/3 拉起基础设施 =="
cd "$P0" && docker compose up -d >/dev/null 2>&1
for i in $(seq 1 30); do
  docker exec wm-p0-mysql mysql -uroot -p123456 -e "SELECT 1" >/dev/null 2>&1 && break
  sleep 1
done
for i in $(seq 1 30); do
  curl -s -o /dev/null http://127.0.0.1:8848/nacos/v1/console/health/readiness 2>/dev/null && break
  sleep 1
done
echo "   容器已就绪"

echo "== 2/3 启动后端服务 =="
mkdir -p /tmp/wimoor-p0-logs
start_jar() {
  local name="$1" jar="$2"; shift 2
  if pgrep -f "jar $jar" >/dev/null 2>&1; then echo "   $name 已在运行"; return; fi
  nohup "$JAVA_HOME/bin/java" -Xmx512m -XX:+UseSerialGC \
    -Dspring.profiles.active=dev -DNACOS_SERVER_ADDR=127.0.0.1:8848 \
    -jar "$jar" "$@" > "/tmp/wimoor-p0-logs/$name.log" 2>&1 &
  echo "   $name pid=$!"
}
start_jar gateway "$WM/wimoor-gateway/target/wimoor-gateway.jar"
start_jar admin   "$WM/wimoor-admin/admin-boot/target/admin-boot.jar"
start_jar erp     "$WM/wimoor-erp/erp-boot/target/erp-boot.jar"
AI_JAR=$(find "$WM/wimoor-ai/target" -maxdepth 1 -name 'wimoor-ai-*.jar' ! -name 'original*' | head -1)
if ! pgrep -f "jar $AI_JAR" >/dev/null 2>&1; then
  nohup "$JAVA_HOME/bin/java" -Xmx384m -XX:+UseSerialGC -jar "$AI_JAR" \
    --ai.persistence=jdbc \
    "--ai.datasource.url=jdbc:mysql://127.0.0.1:13306/db_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
    --ai.datasource.username=root --ai.datasource.password=123456 \
    > "/tmp/wimoor-p0-logs/ai.log" 2>&1 &
  echo "   ai pid=$!"
else
  echo "   ai 已在运行"
fi
for i in $(seq 1 40); do
  curl -s -o /dev/null http://127.0.0.1:8102/ai/api/v1/health 2>/dev/null && break
  sleep 1
done
echo "   后端已就绪"

echo "== 3/3 恢复公网隧道 =="
for f in /tmp/keep-tunnel.pid /tmp/lhr.pid; do
  [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true
done
if ! pgrep -f "keep-tunnel.sh" >/dev/null 2>&1; then
  nohup "$P0/keep-tunnel.sh" > /tmp/keep-tunnel.out 2>&1 &
  echo $! > /tmp/keep-tunnel.pid
fi
rm -f /tmp/tunnel-url.txt
for i in $(seq 1 40); do [ -s /tmp/tunnel-url.txt ] && break; sleep 1; done
URL=$(cat /tmp/tunnel-url.txt 2>/dev/null || echo '')
echo "   公网URL: $URL"

echo ""
echo "== 验证 =="
printf '   前端=%s ' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8080/)"
printf '登录=%s ' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST http://127.0.0.1:8080/admin/api/v1/auth/login -H 'Content-Type: application/json' -d '{"account":"admin@wimoor.com","password":"123456"}')"
printf 'AI=%s\n' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8080/ai/api/v1/health)"
echo "   账号: admin@wimoor.com / 123456"
echo "   链接: $URL"

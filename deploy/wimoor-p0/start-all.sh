#!/usr/bin/env bash
# 启动全部已编译的后端服务（补齐 restore.sh 缺失的 finance/amazon/adv/data）
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
P0="$ROOT/deploy/wimoor-p0"; WM="$ROOT/wimoor"
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64; export JAVA_HOME
mkdir -p /tmp/wimoor-p0-logs

# 基础设施
cd "$P0" && docker compose up -d >/dev/null 2>&1
for i in $(seq 1 40); do docker exec wm-p0-mysql mysql -uroot -p123456 -e "SELECT 1" >/dev/null 2>&1 && break; sleep 1; done
for i in $(seq 1 40); do curl -s -o /dev/null http://127.0.0.1:8848/nacos/v1/console/health/readiness 2>/dev/null && break; sleep 1; done

# 导入 Nacos 配置（原脚本缺失此步，导致 config.* 占位符与数据源 jdbcUrl 无法解析、服务启动即崩）
# 与 deploy.sh step_start 一致：数据源指向宿主映射端口 13306 / Redis 16379
for f in "$WM"/init-config/nacos/DEFAULT_GROUP/wimoor-*; do
  [ -f "$f" ] || continue
  id="$(basename "$f")"
  sed -e 's|mysql.host=localhost|mysql.host=127.0.0.1|' \
      -e 's|mysql.port=3306|mysql.port=13306|' \
      "$f" > "/tmp/nacos-$id.yaml"
  if [ "$id" = "wimoor-common" ]; then
    sed -i -e '/^redis.password=/a redis.port=16379' \
           -e '/^spring.redis.host=/a spring.redis.port= ${redis.port}' "/tmp/nacos-$id.yaml"
  fi
  curl -s -o /dev/null -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$id" --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" --data-urlencode "content@/tmp/nacos-$id.yaml"
done
echo "Nacos 配置已导入"

start_jar() {
  local name="$1" jar="$2"; shift 2
  [ ! -f "$jar" ] && { echo "SKIP $name (无jar)"; return; }
  if pgrep -f "jar $jar" >/dev/null 2>&1; then echo "RUN  $name"; return; fi
  nohup "$JAVA_HOME/bin/java" -Xmx480m -XX:+UseSerialGC -XX:MaxMetaspaceSize=180m \
    -Dspring.profiles.active=dev -DNACOS_SERVER_ADDR=127.0.0.1:8848 \
    -jar "$jar" "$@" > "/tmp/wimoor-p0-logs/$name.log" 2>&1 &
  echo "START $name pid=$!"
}
start_jar gateway  "$WM/wimoor-gateway/target/wimoor-gateway.jar"
start_jar admin    "$WM/wimoor-admin/admin-boot/target/admin-boot.jar"
start_jar erp      "$WM/wimoor-erp/erp-boot/target/erp-boot.jar"
start_jar amazon   "$WM/wimoor-amazon/amazon-boot/target/amazon-boot.jar"
start_jar finance  "$WM/wimoor-finance/target/wimoor-finance.jar"
start_jar data     "$WM/wimoor-data/target/wimoor-data.jar"
ADV=$(find "$WM/wimoor-amazon-adv/amazon-adv-boot/target" -maxdepth 1 -name '*.jar' ! -name original* ! -name '*sources*' 2>/dev/null | head -1)
[ -n "$ADV" ] && start_jar amazon-adv "$ADV"
QUOTE=$(find "$WM/wimoor-quote/quote-boot/target" -maxdepth 1 -name '*.jar' ! -name original* 2>/dev/null | head -1)
[ -n "$QUOTE" ] && start_jar quote "$QUOTE"
GEN=$(find "$WM/wimoor-gen/target" -maxdepth 1 -name '*.jar' ! -name original* 2>/dev/null | head -1)
[ -n "$GEN" ] && start_jar gen "$GEN"
AIJAR=$(find "$WM/wimoor-ai/target" -maxdepth 1 -name 'wimoor-ai-*.jar' ! -name original* 2>/dev/null | head -1)
[ -n "$AIJAR" ] && start_jar ai "$AIJAR" --ai.persistence=jdbc \
  "--ai.datasource.url=jdbc:mysql://127.0.0.1:13306/db_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
  --ai.datasource.username=root --ai.datasource.password=123456
echo "等待服务启动(120s)..."
sleep 90
echo "=== 端口探活 ==="
for p in 8100 8101 8102 8103 8104 8105; do printf "  %s: %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:$p/actuator/health 2>/dev/null)"; done

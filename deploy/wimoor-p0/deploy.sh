#!/usr/bin/env bash
# Wimoor P0 地基一键部署（8GB 单机）。端口与生产条条 ERP 隔离。
# 用法：bash deploy/wimoor-p0/deploy.sh [install|start|verify|all]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"          # .../woker
P0="$ROOT/deploy/wimoor-p0"
UI="$ROOT/wimoor/wimoor-ui"
WM="$ROOT/wimoor"
ACTION="${1:-all}"

step_install() {
  echo "== 1/4 构建前端 =="
  cd "$UI"
  [ -d node_modules ] || pnpm install --no-frozen-lockfile
  pnpm build
  mkdir -p "$P0/html"
  cp -r dist/* "$P0/html/"
  echo "== 2/4 启动基础设施（MySQL/Redis/Nacos/nginx）=="
  cd "$P0" && docker compose up -d
}

step_start() {
  echo "== 3/4 导入 Nacos 配置（指向宿主映射端口）=="
  for f in "$WM"/init-config/nacos/DEFAULT_GROUP/wimoor-*; do
    [ -f "$f" ] || continue
    id="$(basename "$f")"
    sed -e 's|mysql.host=localhost|mysql.host=127.0.0.1|' \
        -e 's|mysql.host=127.0.0.1|mysql.host=127.0.0.1|' \
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
  echo "== 4/4 启动后端 gateway/admin/erp + wimoor-ai（JDK11）=="
  export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
  mkdir -p /tmp/wimoor-p0-logs
  for spec in "gateway:$WM/wimoor-gateway/target/wimoor-gateway.jar" \
              "admin:$WM/wimoor-admin/admin-boot/target/admin-boot.jar" \
              "erp:$WM/wimoor-erp/erp-boot/target/erp-boot.jar"; do
    name="${spec%%:*}"; jar="${spec#*:}"
    [ -f "$jar" ] || { echo "缺少 $jar，请先编译后端"; exit 1; }
    nohup "$JAVA_HOME/bin/java" -Xmx512m -XX:+UseSerialGC \
      -Dspring.profiles.active=dev -DNACOS_SERVER_ADDR=127.0.0.1:8848 \
      -jar "$jar" > "/tmp/wimoor-p0-logs/$name.log" 2>&1 &
    echo "$name pid=$!"
  done

  # AI 能力模块：jdbc 生产模式（真实落库 db_ai）。凭证从环境变量注入，缺失时保持诚实边界。
  AI_JAR=$(find "$WM/wimoor-ai/target" -maxdepth 1 -name 'wimoor-ai-*.jar' ! -name 'original*' | head -1)
  if [ -n "$AI_JAR" ]; then
    AI_DS_URL="${AI_DATASOURCE_URL:-jdbc:mysql://127.0.0.1:13306/db_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai}"
    AI_DS_USER="${AI_DATASOURCE_USER:-root}"
    AI_DS_PASS="${AI_DATASOURCE_PASSWORD:-123456}"
    nohup "$JAVA_HOME/bin/java" -Xmx384m -XX:+UseSerialGC -jar "$AI_JAR" \
      --ai.persistence=jdbc \
      --ai.datasource.url="$AI_DS_URL" \
      --ai.datasource.username="$AI_DS_USER" \
      --ai.datasource.password="$AI_DS_PASS" \
      --ai.llm.apiKey="${AI_LLM_API_KEY:-}" \
      --ai.llm.baseUrl="${AI_LLM_BASE_URL:-}" \
      --ai.llm.model="${AI_LLM_MODEL:-}" \
      --ai.llm.wireApi="${AI_LLM_WIRE_API:-chat_completions}" \
      > "/tmp/wimoor-p0-logs/ai.log" 2>&1 &
    echo "ai pid=$! (jdbc 生产模式)"
  else
    echo "警告: 未找到 wimoor-ai jar，跳过 AI 服务启动"
  fi
  echo "等待服务启动（约 30s）..."
  sleep 30
}

step_verify() {
  echo "== 验证 =="
  docker compose -f "$P0/docker-compose.yml" ps
  printf '前端 /            -> '; curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/
  printf '鉴权 /admin/api/v1/menus -> '; curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/admin/api/v1/menus
  printf '网关直连 8099     -> '; curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8099/
  printf 'AI health         -> '; curl -s http://127.0.0.1:8080/ai/api/v1/health 2>/dev/null; echo ''
  printf 'AI skills         -> '; curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/ai/api/v1/skills
  printf 'AI 真实落库(db_ai) -> '
  docker exec wm-p0-mysql mysql -uroot -p123456 -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='db_ai'" 2>/dev/null | tr -d '\n'; echo ' 张表'
}

case "$ACTION" in
  install) step_install ;;
  start)   step_start ;;
  verify)  step_verify ;;
  all)     step_install; step_start; step_verify ;;
  *) echo "用法: $0 [install|start|verify|all]"; exit 1 ;;
esac

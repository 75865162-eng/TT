#!/usr/bin/env bash
# Wimoor 迁移模块统一验收脚本
# 用法: bash verify-migration.sh [compile|test|smoke|all]
set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

MODE="${1:-all}"
log() { echo ">>> $*"; }

do_compile() {
  log "全量编译 wimoor-ai + wimoor-erp/erp-boot"
  mvn -q -pl wimoor-ai,wimoor-erp/erp-boot -am package -DskipTests -Djava.version=11
  log "编译产物:"
  find wimoor-ai/target wimoor-erp/erp-boot/target -maxdepth 1 -name '*.jar' 2>/dev/null
}

do_test() {
  log "运行全部单元测试"
  mvn -q -pl wimoor-ai,wimoor-erp/erp-boot -am test -Djava.version=11
  log "测试结果汇总:"
  grep -h 'testsuite ' wimoor-ai/target/surefire-reports/*.xml 2>/dev/null \
    | sed -E 's/.*name="([^"]+)" time="[^"]*" tests="([0-9]+)" errors="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)".*/\1: tests=\2 errors=\3 failures=\5/' || true
  grep -h 'testsuite ' wimoor-erp/erp-boot/target/surefire-reports/*.xml 2>/dev/null \
    | sed -E 's/.*name="([^"]+)" time="[^"]*" tests="([0-9]+)" errors="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)".*/\1: tests=\2 errors=\3 failures=\5/' || true
}

do_smoke() {
  log "启动 wimoor-ai 并冒烟测试 (8102)"
  AI_JAR=$(find wimoor-ai/target -maxdepth 1 -name 'wimoor-ai-*.jar' ! -name 'original*' | head -1)
  if [ -z "$AI_JAR" ]; then log "未找到 wimoor-ai jar，请先 compile"; exit 1; fi
  nohup "$JAVA_HOME/bin/java" -Xmx256m -jar "$AI_JAR" \
    > /tmp/wimoor-ai-smoke.log 2>&1 &
  PID=$!
  for i in $(seq 1 30); do
    if curl -s -o /dev/null http://127.0.0.1:8102/actuator/health 2>/dev/null; then break; fi
    if ! kill -0 $PID 2>/dev/null; then
      log "启动失败，日志尾部:"; tail -20 /tmp/wimoor-ai-smoke.log; exit 1
    fi
    sleep 1
  done
  HEALTH=$(curl -s http://127.0.0.1:8102/ai/api/v1/health 2>/dev/null || echo '')
  log "GET /ai/api/v1/health -> $HEALTH"
  if ! echo "$HEALTH" | grep -q '"status":"UP"'; then
    log "健康检查失败"; tail -20 /tmp/wimoor-ai-smoke.log; kill $PID 2>/dev/null || true; exit 1
  fi
  SKILLS=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8102/ai/api/v1/skills 2>/dev/null || echo 000)
  METRICS=$(curl -s 'http://127.0.0.1:8102/ai/api/v1/ads/metrics?impressions=10000&clicks=500&spend=100&orders=25&sales=500' 2>/dev/null || echo '')
  log "GET /skills -> $SKILLS ; /ads/metrics -> $METRICS"
  kill $PID 2>/dev/null || true
  log "冒烟完成"
}

case "$MODE" in
  compile) do_compile ;;
  test)    do_test ;;
  smoke)   do_smoke ;;
  all)     do_compile; do_test; do_smoke ;;
  *) echo "用法: $0 [compile|test|smoke|all]"; exit 1 ;;
esac
log "完成"

#!/usr/bin/env bash
# 生产端部署脚本（由 CI 上传 bundle 后远程调用）
# 用法: server-deploy.sh <bundle临时目录> <发布根目录>
#   server-deploy.sh /tmp/wimoor-release-<sha> /opt/wimoor
# 流程: 互斥锁 -> 校验SHA256 -> 新版本独立目录 -> DB迁移(备份) -> 原子切换 -> 健康验收 -> 失败回滚
# 红线: 数据库迁移失败不自动回滚数据库；不清空 Redis；不覆盖配置；不删业务数据。
set -euo pipefail

BUNDLE="${1:?缺少 bundle 目录}"
ROOT="${2:-/opt/wimoor}"
LOCK="$ROOT/.deploy.lock"
RELEASES="$ROOT/releases"
CURRENT="$ROOT/current"
LOG_DIR="$ROOT/logs"
mkdir -p "$RELEASES" "$LOG_DIR"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# 1) 部署互斥：抢不到锁立即失败，不排队、不覆盖
exec 9>"$LOCK"
if ! flock -n 9; then
  log "已有部署在进行中，本次放弃（互斥）" >&2
  exit 1
fi

# 2) SHA256 校验：确认生产部署的是同一产物
log "[verify] 校验产物完整性"
( cd "$BUNDLE" && sha256sum -c SHA256SUMS >/dev/null ) || {
  log "SHA256 校验失败，停止部署" >&2; exit 1;
}
SHA=$(python3 -c "import json;print(json.load(open('$BUNDLE/release.json'))['commit'][:12])" 2>/dev/null || echo "unknown")
NEW="$RELEASES/$SHA"
log "[stage] 版本 $SHA"

# 3) 新版本独立发布目录（不覆盖旧版本）
log "[stage] 写入新版本目录 $NEW"
rm -rf "$NEW"
mkdir -p "$NEW"
cp -a "$BUNDLE"/. "$NEW"/

# 记录旧版本用于回滚
OLD_TARGET=""
[ -e "$CURRENT" ] && OLD_TARGET="$(readlink -f "$CURRENT" || true)"

# 4) 数据库迁移（幂等，仅在版本变化时执行；失败停止，不回滚数据库）
log "[migrate] 执行数据库迁移（幂等）"
if [ -f "$NEW/deploy/wimoor-p0/migrate.sh" ]; then
  bash "$NEW/deploy/wimoor-p0/migrate.sh" wm-p0-mysql db_admin "$NEW/deploy/wimoor-p0/mysql-init" \
    || { log "数据库迁移失败，停止发布，旧版本未受影响，转人工处置" >&2; exit 1; }
  bash "$NEW/deploy/wimoor-p0/migrate.sh" wm-p0-mysql db_ai "$NEW/deploy/wimoor-p0/mysql-init" \
    || { log "db_ai 迁移失败，停止发布，转人工处置" >&2; exit 1; }
fi

# 5) 原子切换：软链指向新版本
log "[switch] 原子切换到新版本"
ln -sfn "$NEW" "$CURRENT.tmp" && mv -Tf "$CURRENT.tmp" "$CURRENT"

# 6) 启动服务（有上限轮询，不盲等）
log "[start] 启动服务"
NEW_START="$NEW/deploy/wimoor-p0/start-all.sh"
if [ -f "$NEW_START" ]; then
  # start-all.sh 内部已改为按自身目录定位，这里在 new 根目录执行
  bash "$NEW_START" > "$LOG_DIR/start-$SHA.log" 2>&1 || true
fi

# 7) 轻量健康验收（有上限轮询）
log "[health] 健康验收"
ok=0
for i in $(seq 1 45); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8080/ 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then ok=1; break; fi
  sleep 2
done

# 8) 失败回滚：切回旧版本并重启（数据库不动）
if [ "$ok" -ne 1 ]; then
  log "[rollback] 健康检查失败，回滚到旧版本" >&2
  cp -a "$LOG_DIR/start-$SHA.log" "$LOG_DIR/failed-$SHA.log" 2>/dev/null || true
  if [ -n "$OLD_TARGET" ] && [ -d "$OLD_TARGET" ]; then
    ln -sfn "$OLD_TARGET" "$CURRENT.tmp" && mv -Tf "$CURRENT.tmp" "$CURRENT"
    bash "$OLD_TARGET/deploy/wimoor-p0/start-all.sh" > "$LOG_DIR/rollback-$SHA.log" 2>&1 || true
    log "[rollback] 已切回 $OLD_TARGET" >&2
  else
    log "[rollback] 无旧版本可回退，需人工介入" >&2
  fi
  exit 1
fi

log "[done] 部署成功：$SHA（耗时见 CI 报告）"

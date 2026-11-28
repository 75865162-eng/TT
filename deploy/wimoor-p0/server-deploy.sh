#!/usr/bin/env bash
# 生产端部署脚本（由 CI 上传 bundle 后远程调用）
# 用法: server-deploy.sh <bundle临时目录> <发布根目录>
#   server-deploy.sh /tmp/wimoor-release-<sha> /opt/wimoor
# 流程: 互斥 -> SHA256校验 -> 版本目录 -> DB迁移(备份) -> 原子切换 -> 优雅重启 -> 9项验收 -> 失败回滚 -> 报告
# 红线: DB迁移失败不自动回滚数据库；不清空 Redis；不覆盖配置；不删业务数据。
set -uo pipefail

BUNDLE="${1:?缺少 bundle 目录}"
ROOT="${2:-/opt/wimoor}"
LOCK="$ROOT/.deploy.lock"
RELEASES="$ROOT/releases"
CURRENT="$ROOT/current"
LOG_DIR="$ROOT/logs"
# 前端：nginx 的 bind-mount 源是一个固定软链，指向当前版本的 html 目录。
# 切换 current 后重启 nginx 使其重新解析新 inode（历史 html swap 旧 inode 500 的根因）。
HTML_LINK="${HTML_LINK:-/home/ubuntu/tt/deploy/wimoor-p0/html}"
NGINX_CONTAINER="${NGINX_CONTAINER:-wm-p0-nginx}"
KEEP_RELEASES="${KEEP_RELEASES:-3}"
mkdir -p "$RELEASES" "$LOG_DIR"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# ---- 耗时采集与报告 ----
T0=$(date +%s)
declare -A STAGE_T
STAGE_T[x]=$T0
stage_done() { STAGE_T[$1]=$(date +%s); }
REPORT="$LOG_DIR/deployment-report-$(date +%Y%m%d-%H%M%S).json"
REPORT_RESULT="failed"; ROLLBACK="false"; FAIL_REASON=""
ACCEPT_LINES=""
declare -A ACCEPT_COUNTS
ACCEPT_COUNTS[PASS]=0; ACCEPT_COUNTS[FAIL]=0; ACCEPT_COUNTS[SKIP]=0
write_report() {
  python3.11 - "$REPORT" "$SHA" "${SOURCE_BRANCH:-unknown}" "$REPORT_RESULT" "$ROLLBACK" \
    "$FAIL_REASON" "$CHANGE_TIER" "$CHANGE_TYPES" \
    "${STAGE_T[lock]:-$T0}" "${STAGE_T[verify]:-$T0}" "${STAGE_T[stage]:-$T0}" \
    "${STAGE_T[migrate]:-$T0}" "${STAGE_T[switch]:-$T0}" "${STAGE_T[start]:-$T0}" \
    "${STAGE_T[health]:-$T0}" "${STAGE_T[accept]:-$T0}" "$T0" \
    "${ACCEPT_COUNTS[PASS]}" "${ACCEPT_COUNTS[FAIL]}" "${ACCEPT_COUNTS[SKIP]}" <<'PY'
import sys, json, datetime
r=sys.argv
def dur(a,b): return max(0, int(b)-int(a))
data={
  "commit": r[2], "branch": r[3], "result": r[4], "rolled_back": r[5]=='true',
  "fail_reason": r[6], "change_tier": r[7], "change_types": r[8],
  "total_seconds": dur(r[9], r[17]),
  "stages_seconds": {
    "lock": dur(r[9],r[10]), "verify": dur(r[10],r[11]), "stage": dur(r[11],r[12]),
    "migrate": dur(r[12],r[13]), "switch": dur(r[13],r[14]), "start": dur(r[14],r[15]),
    "health": dur(r[15],r[16]), "accept": dur(r[16],r[17]),
  },
  "acceptance": {"pass": int(r[18]), "fail": int(r[19]), "skip": int(r[20])},
  "generated_at": datetime.datetime.now().isoformat(timespec='seconds'),
}
open(r[1],'w').write(json.dumps(data,ensure_ascii=False,indent=2)+"\n")
print(json.dumps(data,ensure_ascii=False))
PY
}
finish() { write_report > "$LOG_DIR/report-last.json" 2>/dev/null || true; }
trap finish EXIT

# 1) 部署互斥
exec 9>"$LOCK"
if ! flock -n 9; then
  log "已有部署进行中，本次放弃（互斥）" >&2
  FAIL_REASON="部署互斥锁被占用"; exit 1
fi
stage_done lock

# 2) SHA256 校验
log "[verify] 校验产物完整性"
( cd "$BUNDLE" && sha256sum -c SHA256SUMS >/dev/null ) || { FAIL_REASON="SHA256 校验失败"; log "$FAIL_REASON" >&2; exit 1; }
SHA=$(python3.11 -c "import json;print(json.load(open('$BUNDLE/release.json'))['commit'][:12])" 2>/dev/null || echo "unknown")
CHANGE_TIER=$(python3.11 -c "import json;print(json.load(open('$BUNDLE/release.json')).get('change_tier','unknown'))" 2>/dev/null || echo unknown)
CHANGE_TYPES=$(python3.11 -c "import json;print(json.load(open('$BUNDLE/release.json')).get('change_types','unknown'))" 2>/dev/null || echo unknown)
SOURCE_BRANCH=$(python3.11 -c "import json;print(json.load(open('$BUNDLE/release.json')).get('branch','unknown'))" 2>/dev/null || echo unknown)
NEW="$RELEASES/$SHA"
log "[stage] 版本 $SHA 档位 $CHANGE_TIER 类型 $CHANGE_TYPES"
stage_done verify

# 3) 新版本独立目录（不覆盖旧版本）
log "[stage] 写入 $NEW"
rm -rf "$NEW"; mkdir -p "$NEW"; cp -a "$BUNDLE"/. "$NEW"/
OLD_TARGET=""
[ -e "$CURRENT" ] && OLD_TARGET="$(readlink -f "$CURRENT" || true)"
stage_done stage

# 4) DB 迁移（幂等；失败停止，不回滚数据库）
log "[migrate] 数据库迁移（幂等）"
if [ -f "$NEW/deploy/wimoor-p0/migrate.sh" ]; then
  bash "$NEW/deploy/wimoor-p0/migrate.sh" wm-p0-mysql db_admin "$NEW/deploy/wimoor-p0/mysql-init" \
    || { FAIL_REASON="db_admin 迁移失败，转人工（不回滚数据库）"; log "$FAIL_REASON" >&2; exit 1; }
  bash "$NEW/deploy/wimoor-p0/migrate.sh" wm-p0-mysql db_ai "$NEW/deploy/wimoor-p0/mysql-init" \
    || { FAIL_REASON="db_ai 迁移失败，转人工（不回滚数据库）"; log "$FAIL_REASON" >&2; exit 1; }
fi
stage_done migrate

# 5) 原子切换（后端 current + 前端 html 软链同一版本）
log "[switch] 原子切换到新版本"
ln -sfn "$NEW" "$CURRENT.tmp" && mv -Tf "$CURRENT.tmp" "$CURRENT"
# 前端：把 nginx 挂载源软链指向新版本的 html 目录。
# 幂等：首次迁移时 HTML_LINK 可能是物理目录，需先移除（其内容已在版本目录/备份中）。
if [ -d "$NEW/html" ]; then
  if [ -e "$HTML_LINK" ] && [ ! -L "$HTML_LINK" ]; then
    log "[switch] $HTML_LINK 是物理目录，迁移为软链（旧目录重命名为 .html.pre-ci 保留）"
    mv -Tf "$HTML_LINK" "${HTML_LINK}.pre-ci" 2>/dev/null || {
      FAIL_REASON="前端目录迁移失败：$HTML_LINK 无法重命名，转人工"; log "$FAIL_REASON" >&2; exit 1
    }
  fi
  ln -sfn "$NEW/html" "$HTML_LINK.tmp" && mv -Tf "$HTML_LINK.tmp" "$HTML_LINK"
  # docker bind-mount 缓存旧 inode，必须 restart 才能看到新版本文件。
  if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$NGINX_CONTAINER"; then
    docker restart "$NGINX_CONTAINER" >/dev/null 2>&1 || log "[switch] 警告：nginx 容器重启失败，前端可能仍为旧版本" >&2
  fi
fi
stage_done switch

# 6) 启动：优先 systemd 优雅重启，回退 start-all.sh
log "[start] 启动服务"
USE_SYSTEMD=0
if command -v systemctl >/dev/null 2>&1 && [ -f "$NEW/deploy/wimoor-p0/setup-systemd.sh" ]; then
  bash "$NEW/deploy/wimoor-p0/setup-systemd.sh" "$ROOT" > "$LOG_DIR/systemd-$SHA.log" 2>&1 && USE_SYSTEMD=1
fi
if [ "$USE_SYSTEMD" -eq 1 ]; then
  # 先优雅停止旧服务（SIGTERM，等 30s），再启动新版本
  for svc in gateway admin erp amazon amazon-adv finance data gen quote ai; do
    systemctl stop "wimoor-$svc" 2>/dev/null || true
  done
  for svc in gateway admin erp amazon amazon-adv finance data gen quote ai; do
    systemctl start "wimoor-$svc" 2>/dev/null || true
  done
  log "[start] systemd 优雅重启完成"
else
  log "[start] systemd 不可用，回退 start-all.sh"
  bash "$NEW/deploy/wimoor-p0/start-all.sh" > "$LOG_DIR/start-$SHA.log" 2>&1 || true
fi
stage_done start

# 7) 轻量健康检查（有上限轮询）
log "[health] 健康验收"
ok=0
for i in $(seq 1 45); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8080/ 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then ok=1; break; fi
  sleep 2
done
stage_done health

# 8) 自动验收（9 项）
log "[accept] 自动验收"
AC_OK=0
if [ -f "$NEW/deploy/wimoor-p0/acceptance.sh" ]; then
  ACCEPT_LINES=$(bash "$NEW/deploy/wimoor-p0/acceptance.sh" 2>&1)
  echo "$ACCEPT_LINES" | grep '^RESULT|' | while IFS='|' read -r _ item st detail; do
    case "$st" in
      PASS) k=PASS;; FAIL) k=FAIL;; *) k=SKIP;;
    esac
    echo "$k"  # 仅用于逐行统计示意
  done > /dev/null
  # 用 python 精确统计（避免子 shell 计数丢失）
  read -r pc fc sc <<< "$(echo "$ACCEPT_LINES" | python3.11 -c "
import sys
p=f=s=0
for ln in sys.stdin:
    if ln.startswith('RESULT|'):
        st=ln.split('|')[2]
        if st=='PASS': p+=1
        elif st=='FAIL': f+=1
        else: s+=1
print(p,f,s)
")"
  ACCEPT_COUNTS[PASS]=$pc; ACCEPT_COUNTS[FAIL]=$fc; ACCEPT_COUNTS[SKIP]=$sc
  echo "$ACCEPT_LINES" > "$LOG_DIR/acceptance-$SHA.log"
  [ "$fc" -eq 0 ] && AC_OK=1
fi
stage_done accept

# 9) 失败回滚：切回旧版本并重启（数据库不动）
if [ "$ok" -ne 1 ] || [ "$AC_OK" -ne 1 ]; then
  FAIL_REASON="健康检查或验收失败（web=$ok 验收FAIL=${ACCEPT_COUNTS[FAIL]}）"
  log "[rollback] $FAIL_REASON，回滚到旧版本" >&2
  cp -a "$LOG_DIR/start-$SHA.log" "$LOG_DIR/failed-$SHA.log" 2>/dev/null || true
  if [ -n "$OLD_TARGET" ] && [ -d "$OLD_TARGET" ]; then
    ln -sfn "$OLD_TARGET" "$CURRENT.tmp" && mv -Tf "$CURRENT.tmp" "$CURRENT"
    # 前端软链一并回退到旧版本 html
    if [ -d "$OLD_TARGET/html" ] && [ -L "$HTML_LINK" ]; then
      ln -sfn "$OLD_TARGET/html" "$HTML_LINK.tmp" && mv -Tf "$HTML_LINK.tmp" "$HTML_LINK"
      docker restart "$NGINX_CONTAINER" >/dev/null 2>&1 || true
    fi
    if [ "$USE_SYSTEMD" -eq 1 ]; then
      for svc in gateway admin erp amazon amazon-adv finance data gen quote ai; do
        systemctl stop "wimoor-$svc" 2>/dev/null || true
        systemctl start "wimoor-$svc" 2>/dev/null || true
      done
    else
      bash "$OLD_TARGET/deploy/wimoor-p0/start-all.sh" > "$LOG_DIR/rollback-$SHA.log" 2>&1 || true
    fi
    # 回滚后再次检查服务和业务
    rb=0
    for i in $(seq 1 30); do
      c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8080/ 2>/dev/null || echo 000)
      [ "$c" = "200" ] && { rb=1; break; }; sleep 2
    done
    [ "$rb" -eq 1 ] && ROLLBACK="true" && log "[rollback] 已切回 $OLD_TARGET 且健康检查通过" >&2 \
      || log "[rollback] 已切回 $OLD_TARGET 但健康仍异常，需人工介入" >&2
  else
    log "[rollback] 无旧版本可回退，需人工介入" >&2
  fi
  exit 1
fi

# 10) 版本保留清理：只保留最近 KEEP_RELEASES 个版本，正在运行的版本不删
log "[cleanup] 清理旧版本（保留最近 $KEEP_RELEASES 个）"
find "$RELEASES" -mindepth 1 -maxdepth 1 -type d | sort -r | awk -v keep="$KEEP_RELEASES" 'NR>keep' | while read -r old; do
  [ "$(readlink -f "$CURRENT")" = "$(readlink -f "$old")" ] && continue
  in_use=false
  if [ "$USE_SYSTEMD" -eq 1 ]; then
    for svc in gateway admin erp amazon amazon-adv finance data gen quote ai; do
      pid=$(systemctl show "wimoor-$svc" --property=MainPID --value 2>/dev/null || echo 0)
      cwd=$(readlink -f "/proc/$pid/cwd" 2>/dev/null || true)
      case "$cwd" in "$old"|"$old"/*) in_use=true ;; esac
    done
  fi
  # 前端软链若仍指向该版本也不删
  [ "$(readlink -f "$HTML_LINK" 2>/dev/null)" = "$(readlink -f "$old/html" 2>/dev/null)" ] && in_use=true
  [ "$in_use" = "true" ] && continue
  rm -rf "$old" && log "[cleanup] 已删除旧版本 $(basename "$old")"
done
# 临时 bundle 目录与日志轮转（CI 上传的 /tmp/wimoor-release-<sha> 由本脚本自行清理）
case "$BUNDLE" in
  /tmp/wimoor-release-*) rm -rf "$BUNDLE" && log "[cleanup] 已删除临时 bundle $BUNDLE" ;;
esac
find "$LOG_DIR" -maxdepth 1 -type f -name 'deployment-report-*' -mtime +30 -delete 2>/dev/null || true

REPORT_RESULT="success"
log "[done] 部署成功：$SHA（报告：$REPORT）"

#!/usr/bin/env bash
# 部署后自动验收：9 项检查（Web/核心API/MySQL/Redis/Nacos/Worker/文件存储/权限隔离/业务回归）。
# 用法: acceptance.sh [--full]   # 默认轻量；--full 追加 130 页业务回归（耗时较长）
# 输出: 每项一行 RESULT|项|PASS|FAIL|SKIP|详情；退出码 0=关键项全通过，1=有关键项失败。
# 说明: 基于真实审计——Java 栈无独立 Worker 进程（任务内嵌在各 boot 服务），文件存储为阿里云 OSS。
set -uo pipefail

FULL=0
[ "${1:-}" = "--full" ] && FULL=1
HOST="${ACCEPT_HOST:-127.0.0.1:8080}"
BASE="http://$HOST"
PASS=0; FAIL=0; SKIP=0

result() { # 项 状态 详情
  echo "RESULT|$1|$2|$3"
  case "$2" in PASS) PASS=$((PASS+1));; FAIL) FAIL=$((FAIL+1));; SKIP) SKIP=$((SKIP+1));; esac
}

# 1) Web 健康检查
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/" 2>/dev/null || echo 000)
[ "$code" = "200" ] && result "web" PASS "HTTP $code" || result "web" FAIL "HTTP $code"

# 2) 核心 API（登录 + 菜单路由）——本项目认证用 data.session 作为 jsessionid 请求头。
# 注意：POST /admin/api/v1/menus 是「新增菜单」写接口，验收绝不能调用；只读路由用 GET /menus/route。
LOGIN_JSON=$(curl -s --max-time 8 -X POST "$BASE/admin/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"account":"admin@wimoor.com","password":"123456"}' 2>/dev/null)
SESSION=$(echo "$LOGIN_JSON" | python3.11 -c "import sys,json; d=json.load(sys.stdin); print((d.get('data') or {}).get('session') or '')" 2>/dev/null)
[ -n "$SESSION" ] && result "api_login" PASS "取得 session" || result "api_login" FAIL "未取得 session"
if [ -n "$SESSION" ]; then
  mcode=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$BASE/admin/api/v1/menus/route" -H "jsessionid: $SESSION" 2>/dev/null || echo 000)
  mcount=$(curl -s --max-time 8 "$BASE/admin/api/v1/menus/route" -H "jsessionid: $SESSION" 2>/dev/null \
    | python3.11 -c "
import sys,json
d=json.load(sys.stdin); x=d.get('data',[])
def cnt(ns):
    return sum(1+cnt(n.get('children') or []) for n in ns)
print(cnt(x) if isinstance(x,list) else -1)
" 2>/dev/null)
  [ "$mcode" = "200" ] && result "api_menu" PASS "HTTP $mcode 路由节点=$mcount" || result "api_menu" FAIL "HTTP $mcode"
else
  result "api_menu" SKIP "无 session，跳过"
fi

# 3) MySQL（容器内存活 + 关键库表）
if docker exec wm-p0-mysql mysql -uroot -p123456 -e "SELECT 1" >/dev/null 2>&1; then
  dbai=$(docker exec wm-p0-mysql mysql -uroot -p123456 -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='db_ai'" 2>/dev/null)
  result "mysql" PASS "存活，db_ai 表数=$dbai"
else
  result "mysql" FAIL "容器 wm-p0-mysql 不可用"
fi

# 4) Redis（容器内 ping）
RPONG=$(docker exec wm-p0-redis redis-cli ping 2>/dev/null | tr -d '\r' || true)
[ "$RPONG" = "PONG" ] && result "redis" PASS "PONG" || result "redis" FAIL "无 PONG（容器名或状态异常）"

# 5) Nacos（ready + 关键服务实例数）
NREADY=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8848/nacos/v1/console/health/readiness 2>/dev/null || echo 000)
if [ "$NREADY" = "200" ]; then
  inst=$(curl -s --max-time 5 "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=wimoor-admin" 2>/dev/null \
    | python3.11 -c "import sys,json; d=json.load(sys.stdin); print(len([h for h in d.get('hosts',[]) if h.get('healthy')]))" 2>/dev/null)
  result "nacos" PASS "ready，admin 健康实例=$inst"
else
  result "nacos" FAIL "readiness HTTP $NREADY"
fi

# 6) Worker（Java 栈无独立进程，检查内嵌任务的宿主服务进程存活）
SVC_COUNT=0
for svc in gateway admin erp amazon finance data gen quote ai amazon-adv; do
  systemctl is-active --quiet "wimoor-$svc" 2>/dev/null && SVC_COUNT=$((SVC_COUNT+1)) || true
done
if [ "$SVC_COUNT" -gt 0 ]; then
  result "worker" PASS "systemd 活跃服务=$SVC_COUNT（任务内嵌于 boot 服务）"
else
  # 未用 systemd 时回退到 java 进程计数
  JCOUNT=$(pgrep -f 'java .*\.jar' | wc -l)
  [ "$JCOUNT" -gt 0 ] && result "worker" PASS "java 进程=$JCOUNT（内嵌任务宿主存活）" \
    || result "worker" FAIL "无活跃服务进程"
fi

# 7) 文件存储（阿里云 OSS：检查凭证配置项存在；无真实凭证则 SKIP 实连）
OSS_CFG=$(docker exec wm-p0-redis printenv 2>/dev/null >/dev/null; grep -rl "oss-cn\|aliyun.oss\|accessKeyId" /opt/wimoor/current/wimoor/init-config 2>/dev/null | head -1)
if [ -n "${OSS_ACCESS_KEY_ID:-}" ]; then
  result "file_storage" PASS "OSS 凭证环境变量已配置"
elif [ -n "$OSS_CFG" ]; then
  result "file_storage" SKIP "配置存在但无凭证环境变量，未做实连（需真实凭证）"
else
  result "file_storage" SKIP "文件存储为阿里云 OSS，当前环境无凭证，未做实连"
fi

# 8) 权限隔离（管理员可见路由树应 >0；收敛后应为 173 节点）
# 完整隔离验证需第二个低权限账号对比菜单集合，当前仅验证管理员视角可用。
if [ -n "$SESSION" ]; then
  total=$(curl -s --max-time 8 "$BASE/admin/api/v1/menus/route" -H "jsessionid: $SESSION" 2>/dev/null \
    | python3.11 -c "
import sys,json
d=json.load(sys.stdin); x=d.get('data',[])
def cnt(ns): return sum(1+cnt(n.get('children') or []) for n in ns)
print(cnt(x) if isinstance(x,list) else 0)
" 2>/dev/null)
  [ "${total:-0}" -ge 100 ] && result "permission" PASS "管理员可见路由节点=$total（收敛基线 173）" \
    || result "permission" FAIL "路由节点=$total，低于基线，权限/菜单可能异常"
else
  result "permission" SKIP "无 session，跳过"
fi

# 9) 业务回归（轻量=冒烟已覆盖；--full 调 resilient-acceptance.mjs 分批）
if [ "$FULL" -eq 1 ]; then
  if [ -f /opt/wimoor/current/scripts/acceptance/resilient-acceptance.mjs ]; then
    node /opt/wimoor/current/scripts/acceptance/resilient-acceptance.mjs --base "$BASE" >/tmp/accept-full.log 2>&1 \
      && result "business_regression" PASS "130 页回归通过（详见 /tmp/accept-full.log）" \
      || result "business_regression" FAIL "回归有失败项（详见 /tmp/accept-full.log）"
  else
    result "business_regression" SKIP "当前版本无 resilient-acceptance.mjs"
  fi
else
  result "business_regression" SKIP "轻量模式仅做 1-8 项；--full 启用 130 页回归"
fi

echo "SUMMARY|PASS=$PASS|FAIL=$FAIL|SKIP=$SKIP"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1

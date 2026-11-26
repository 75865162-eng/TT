#!/usr/bin/env bash
# 部署前自检：静态扫描本次审计发现的问题模式，防止"部署后才发现"。
# 用法: bash scripts/preflight-audit.sh [repo_root]
# 退出码: 0=全部通过; 1=发现问题(需人工确认)
set -u
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$ROOT" || exit 2
FAIL=0
pass(){ printf "  [OK]   %s\n" "$1"; }
fail(){ printf "  [FAIL] %s\n" "$1"; FAIL=1; }

echo "===== 部署前自检 (repo=$ROOT) ====="

# 1. JDBC URL 编码参数：@characterEncoding 是分隔符写错的典型 bug
echo "[1] JDBC 编码参数分隔符"
if grep -rnE 'useUnicode=true@characterEncoding|useUnicode=[^&]*@' \
    wimoor/init-config deploy --include='*' 2>/dev/null | grep -v '.bak' | head -5 | grep -q .; then
  fail "发现 useUnicode=true@characterEncoding（应为 &）"
  grep -rnE 'useUnicode=true@characterEncoding' wimoor/init-config deploy 2>/dev/null | grep -v '.bak' | head -5
else
  pass "无 @characterEncoding 分隔符错误"
fi

# 2. UserInfoContext.set 是否都有对应 remove（ThreadLocal 串号/越权）
echo "[2] ThreadLocal 清理"
miss=0
while IFS= read -r f; do
  grep -q 'UserInfoContext.remove' "$f" || { fail "set 未清理: $f"; miss=1; }
done < <(grep -rln 'UserInfoContext.set' wimoor --include=*.java 2>/dev/null | grep -v target)
[ "$miss" -eq 0 ] && pass "所有 UserInfoContext.set 均有 remove"

# 3. 裸 jsessionid 删除漏 token 前缀（登出可重放）
echo "[3] 登出 token 删除前缀"
if grep -rnE 'redisTemplate\.delete\(jsessionid\)|delete\(jsessionid\)' wimoor --include=*.java 2>/dev/null | grep -v target | grep -q .; then
  fail "发现 delete(jsessionid) 漏 LOGIN_TOKEN_KEY 前缀"
  grep -rnE 'delete\(jsessionid\)' wimoor --include=*.java 2>/dev/null | grep -v target | head -5
else
  pass "无裸 jsessionid 删除"
fi

# 4. 单点登录互踢残留：setSession 里删除旧 session
echo "[4] 登录互踢逻辑"
if grep -nE 'delete\(sessionkeyOld\)' wimoor/wimoor-admin/admin-boot/src/main/java/com/wimoor/auth/client/controller/LoginController.java 2>/dev/null | grep -q .; then
  fail "setSession 仍删除旧 session（多会话互踢）"
else
  pass "setSession 无互踢"
fi

# 5. 已知无效外部域名/假域名（只看 Nacos 配置源文件，排除 dist 历史外链与 SQL 占位示例）
echo "[5] 无效配置域名"
BAD_DOMAINS='sso\.wimoor\.com|mywebsit\.com|localhost:8087'
domain_hits=$(grep -rnE "$BAD_DOMAINS" wimoor/init-config/nacos deploy/wimoor-p0/*.yml 2>/dev/null \
  | grep -v '.bak' \
  | grep -vE ':[0-9]+:[[:space:]]*#')
if [ -n "$domain_hits" ]; then
  echo "  [WARN] 发现占位/外部域名（需确认是私有部署期望值还是未替换的假配置）:"
  echo "$domain_hits" | head -12
else
  pass "无已知无效域名"
fi

# 6. 潜在 NPE: xxx.getUsertype().equals / Optional.get() 无 orElse
#    排除同一行已有判空的（getUsertype()!=null&&...）
echo "[6] 常见 NPE 模式"
npe_hits=$(grep -rnE 'getUsertype\(\)\.equals|findFirst\(\)\.get\(\)' wimoor --include=*.java 2>/dev/null \
  | grep -v target \
  | grep -vE '!=null *&&|null *!=|Objects\.equals')
if [ -n "$npe_hits" ]; then
  echo "  [WARN] 发现反向 equals 缺失 / Optional.get 无兜底:"
  echo "$npe_hits" | head -8
else
  pass "无常见 NPE 模式"
fi

# 7. Feign 内部接口依赖 UserInfoContext 但无空守卫（后台/无上下文调用会 NPE）
echo "[7] Feign接口UserInfoContext空守卫"
# admin-api 声明的 Feign 接口对应的实现方法若直接用 UserInfoContext.get().xxx 且无判空则告警
feign_methods='getNameMap|getIDMap|getUserByUserId|getUserName|getAllUserName'
ng=$(grep -rnE "UserInfoContext.get\(\)" wimoor/wimoor-admin/admin-boot/src/main/java/com/wimoor/admin/controller/UserController.java 2>/dev/null \
  | grep -E "$feign_methods" | head -3)
# 逐个检查守卫: 简单探测方法体内是否有 ==null 守卫（用方法名附近10行）
guard_ok=1
for m in getNameMap getIDMap; do
  body=$(awk "/$m\(/{f=1} f{print} f&&/^\t}/{exit}" wimoor/wimoor-admin/admin-boot/src/main/java/com/wimoor/admin/controller/UserController.java 2>/dev/null)
  echo "$body" | grep -q 'userInfo==null' || { fail "Feign接口 $m 无 UserInfoContext 空守卫"; guard_ok=0; }
done
[ "$guard_ok" -eq 1 ] && pass "Feign接口 getNameMap/getIDMap 均有空守卫"

echo "===== 结果 ====="
if [ "$FAIL" -eq 1 ]; then
  echo "发现问题，请修复后再部署。"
else
  echo "静态自检通过（WARN 项需人工确认）。"
fi
exit $FAIL

#!/usr/bin/env bash
# 让 Cloudflare Proxied(橙色云朵) 回源的 80 端口流量落到 nginx 的 8080 映射。
# 背景：nginx 容器只映射了 8080->80，宿主机 80 无监听，Cloudflare 回源 80 会 521。
# 零中断：用 iptables REDIRECT，不动正在运行的服务；systemd unit 保证重启后仍生效。
set -euo pipefail

RULE_DESC="wimoor port80->8080 redirect (Cloudflare origin)"

install_redirect() {
  if ! iptables -t nat -C PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null; then
    iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080
    echo "已添加 80->8080 转发规则"
  else
    echo "80->8080 转发规则已存在，跳过"
  fi
}

# 1) 立即生效
install_redirect

# 2) 持久化：systemd oneshot，开机自动确保规则存在
UNIT=/etc/systemd/system/wimoor-port80.service
cat > "$UNIT" <<'EOF'
[Unit]
Description=Wimoor port 80 to 8080 redirect (Cloudflare origin)
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/bash -c 'iptables -t nat -C PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080'
ExecStop=/bin/bash -c 'iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports 8080 2>/dev/null || true'

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable wimoor-port80 >/dev/null 2>&1 || true
systemctl start wimoor-port80
echo "已安装并启用 systemd unit: $UNIT"

# 3) 验证
echo "=== 验证 ==="
curl -s -o /dev/null -w "本机 80 -> HTTP %{http_code}\n" --max-time 6 http://127.0.0.1:80/ || true
iptables -t nat -L PREROUTING -n | grep REDIRECT || true
echo "完成。现在可通过 http://域名/（无需 :8080）访问。"

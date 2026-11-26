#!/usr/bin/env bash
# 源站 HTTPS 配置（配合 Cloudflare 灰云 DNS Only 使用）
# - 申请/续期 Let's Encrypt 证书（webroot，无需停机）
# - 证书复制到 nginx 挂载目录 certs/（gitignore 已排除）
# - docker-compose 增加 443 映射与证书卷；nginx.conf 需包含 443 server 块
# 前置：域名 A 记录已解析到本机，80 端口可从公网访问（HTTP-01 验证）
set -euo pipefail

DOMAIN="${HTTPS_DOMAIN:-esmorah.com}"
WWW="www.${DOMAIN}"
BASE_DIR="${BASE_DIR:-/home/ubuntu/tt/deploy/wimoor-p0}"
WEBROOT="${BASE_DIR}/html"
CERT_SRC="/etc/letsencrypt/live/${DOMAIN}"
CERT_DST="${BASE_DIR}/certs"

echo "=== 1. 申请/续期证书（webroot HTTP-01）==="
sudo certbot certonly --webroot -w "$WEBROOT" \
  -d "$DOMAIN" -d "$WWW" \
  --email "admin@${DOMAIN}" --agree-tos --non-interactive --keep-until-expiring

echo "=== 2. 复制证书到 nginx 挂载目录 ==="
sudo mkdir -p "$CERT_DST"
sudo cp "$CERT_SRC/fullchain.pem" "$CERT_DST/fullchain.pem"
sudo cp "$CERT_SRC/privkey.pem" "$CERT_DST/privkey.pem"
sudo chmod 644 "$CERT_DST/fullchain.pem"
sudo chmod 604 "$CERT_DST/privkey.pem"   # nginx 容器可以读取
ls -la "$CERT_DST"

echo "=== 3. 校验 nginx 配置并重建 ==="
cd "$BASE_DIR"
docker run --rm --add-host host.docker.internal:host-gateway \
  -v "$BASE_DIR/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  -v "$CERT_DST:/etc/letsencrypt/certs:ro" \
  nginx:alpine nginx -t
sudo docker compose up -d --force-recreate nginx

echo "=== 4. 本机 HTTPS 验证 ==="
curl -s -o /dev/null -w "首页: HTTP %{http_code}\n" --max-time 8 --resolve "${DOMAIN}:443:127.0.0.1" "https://${DOMAIN}/"
echo "完成。接下来需在 Cloudflare 把 DNS 改为灰云(DNS only)、腾讯云防火墙放行 443 IPv4。"

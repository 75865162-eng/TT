#!/usr/bin/env bash
# 生成并安装 10 个 Wimoor Java 服务的 systemd unit（优雅停止 + 失败自重启）。
# 用法: setup-systemd.sh <发布根目录，默认 /opt/wimoor>
#   setup-systemd.sh /opt/wimoor
# 说明:
#   - ExecStart 指向 current 软链下的 jar，配合 server-deploy.sh 原子切换实现版本升级。
#   - ExecStop 用 SIGTERM，给服务 30 秒优雅退出（处理完在途请求/任务），超时 systemd 自动 KILL。
#   - Restart=on-failure 失败自启；RestartSec=5 避免频繁重启风暴。
#   - 不再使用 nohup + pgrep，进程由 systemd 统一管理。
set -euo pipefail

ROOT="${1:-/opt/wimoor}"
CURRENT="$ROOT/current"
UNIT_DIR="/etc/systemd/system"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

# 服务清单：名称 | jar 相对路径 | 额外 JVM/应用参数
# 端口由各服务自身配置决定；actuator 非全量可用，健康检查用网关 8080 入口。
read -r -a SERVICES <<'EOF'
gateway|wimoor-gateway/target/wimoor-gateway.jar|
admin|wimoor-admin/admin-boot/target/admin-boot.jar|
erp|wimoor-erp/erp-boot/target/erp-boot.jar|
amazon|wimoor-amazon/amazon-boot/target/amazon-boot.jar|
amazon-adv|wimoor-amazon-adv/amazon-adv-boot/target/amazon-adv-boot.jar|
finance|wimoor-finance/target/wimoor-finance.jar|
data|wimoor-data/target/wimoor-data.jar|
gen|wimoor-gen/target/wimoor-gen.jar|
quote|wimoor-quote/quote-boot/target/quote-boot.jar|
ai|wimoor-ai/target/wimoor-ai.jar|--ai.persistence=jdbc --ai.datasource.url=jdbc:mysql://127.0.0.1:13306/db_ai?useUnicode=true\&characterEncoding=utf8\&serverTimezone=Asia/Shanghai --ai.datasource.username=root --ai.datasource.password=123456
EOF

JAVA_HOME_BIN=/usr/lib/jvm/java-11-openjdk-amd64/bin/java
[ -x "$JAVA_HOME_BIN" ] || JAVA_HOME_BIN="$(command -v java)"

echo "[systemd] 生成 unit，jar 根目录: $CURRENT/wimoor"

for entry in "${SERVICES[@]}"; do
  IFS='|' read -r name jar extra <<< "$entry"
  unit="$UNIT_DIR/wimoor-$name.service"
  cat > "$unit" <<EOF
[Unit]
Description=Wimoor $name service
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=$CURRENT/wimoor
Environment=JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ExecStart=$JAVA_HOME_BIN -Xmx480m -XX:+UseSerialGC -XX:MaxMetaspaceSize=180m -Dspring.profiles.active=dev -DNACOS_SERVER_ADDR=127.0.0.1:8848 -jar $CURRENT/wimoor/$jar $extra
ExecStop=/bin/kill -TERM \$MAINPID
KillMode=mixed
TimeoutStopSec=30
Restart=on-failure
RestartSec=5
StandardOutput=append:$LOGS/$name.log
StandardError=append:$LOGS/$name.err.log

[Install]
WantedBy=multi-user.target
EOF
  echo "  生成 $unit"
done

systemctl daemon-reload
echo "[systemd] daemon-reload 完成"
echo "[systemd] 启用开机自启（按需）:"
echo "  systemctl enable wimoor-gateway wimoor-admin wimoor-erp wimoor-amazon wimoor-amazon-adv wimoor-finance wimoor-data wimoor-gen wimoor-quote wimoor-ai"

# Wimoor P0 地基部署（8GB 单机版）

目标：把 Wimoor 原生架构（仅关 Seata、JVM 小堆）在单机跑起来——能登录、能看到菜单、清单内页面可打开。这是迁移 v2 的第一阶段，全程与现有条条 ERP 生产（3000 端口）隔离。

## 目录

- `docker-compose.yml`：基础设施（MySQL 13306 / Redis 16379 / Nacos 18848），端口均与生产隔离
- `mysql-init/01-init.sql`：空库初始化（8 库表结构 + db_admin 系统数据，**不迁历史业务数据**）
- 后端服务镜像与前端 nginx：编译/构建后按下方步骤加入

## 一键起基础设施

```bash
cd deploy/wimoor-p0
docker compose up -d
docker compose ps   # 三个容器 healthy
```

## MySQL 初始化 SQL 的三个坑（已在本仓库的 01-init.sql 中处理）

Wimoor 仓库自带的 `init-config/mysql/*.sql` 是 HeidiSQL 导出，直接导入 MySQL 8.0.46 会失败。生成 `01-init.sql` 时已做三处清洗，重新生成时务必保留：

1. **`SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS,'')` 报错**（ERROR 1231）
   → 删除所有 `/*!NNNNN SET ... IFNULL(...)*/` 会话恢复语句。
2. **`row size too large`**（ERROR 1118，t_amz_vat_transaction 等宽表）
   → MySQL 启动参数加 `--innodb_strict_mode=0`。
3. **`alter-*.sql` 在 `table-*.sql` 之前执行导致表不存在**（ERROR 1146）
   → 结构脚本排序：`table-*.sql` 在前，`alter-*.sql` 在后。
4. **系统数据重复主键**（ERROR 1062，t_sys_role_menu）
   → 系统数据段的 `INSERT INTO` 改为 `INSERT IGNORE INTO`。

初始化成功后应有：db_admin 84 表 / db_amazon 245 / db_amazon_adv 155 / db_erp 191 / db_finance 46 / db_quartz 11 / db_quote 22；`t_sys_menu` 225 行、`t_user` 1 行。

## 编译后端（JDK 17 + Maven，Lombok 兼容）

本地只有 JDK17，Wimoor 的老 Lombok 与 JDK17 模块系统冲突，需给 Maven 加 add-opens：

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
OPENS=""; for p in code comp file main model parser processing tree util api jvm; do \
  OPENS="$OPENS --add-opens=jdk.compiler/com.sun.tools.javac.$p=ALL-UNNAMED"; done
export MAVEN_OPTS="$OPENS"
mvn -pl wimoor-gateway,wimoor-admin/admin-boot,wimoor-erp/erp-boot -am \
  clean package -DskipTests -Djava.version=17
```

## Nacos 配置本地化

启动后把 `wimoor/init-config/nacos/DEFAULT_GROUP/*` 导入 Nacos，并把数据源指向容器：

- `wimoor-common`：`mysql.host` → `wm-p0-mysql`，`redis.host` → `wm-p0-redis`
- `seata.enabled=false`（默认已关，保持）
- 各服务 bootstrap-dev.yml 的 `NACOS_SERVER_ADDR` → `wm-p0-nacos:8848`

## P0 验证记录（2026-11-25 实测）

| 项 | 结果 |
|---|---|
| MySQL / Redis / Nacos 三容器 | ✅ healthy |
| 8 库表结构 | ✅ db_admin 84 / db_amazon 245 / db_amazon_adv 155 / db_erp 191 / db_finance 46 / db_quartz 11 / db_quote 22 |
| 系统数据 | ✅ t_sys_menu 225 行 / t_user 1 行 |
| gateway / admin / erp | ✅ 三服务启动，端口 8099/8100/8101，全部注册到 Nacos |
| 网关转发鉴权 | ✅ `/admin/api/v1/menus` 经网关返回 403（未登录拦截，路由与鉴权生效） |
| JDK 编译 | ✅ JDK 11（Lombok 1.18.18 与 JDK17 不兼容，必须用 11） |

**实测内存（比 v2 预算偏高，需据此调整 8GB 规划）**：

| 组件 | 实测 RSS |
|---|---:|
| gateway | 388 MB |
| admin | 646 MB |
| erp | 655 MB |
| Nacos | ~1.1 GB |
| MySQL | ~500 MB |
| Redis | ~15 MB |
| **合计（含系统）** | **~3.4 GB** |

→ 即使全服务（再+amazon/amazon-adv/finance/data/quote，各 ~300-600MB）合计约 5.5-6GB，8GB 仍能承载，但比 v2 估算的 5.4GB 略高，需保留 2GB 余量。

**启动 Nacos 必须映射 gRPC 端口**：Nacos 2.x 客户端按 `server_addr+1000` 推导 gRPC 端口。因客户端连 `127.0.0.1:8848`，宿主端口必须保持 8848/9848/9849（不能用 18848 等自定义端口，否则客户端会去连 9848 失败）。

## 待办（前端）

- [ ] 构建 `wimoor-ui`（需 `pnpm install`，本次为控制体积已排除 node_modules），nginx 托管静态文件并反代到网关 8099
- [ ] 用 `admin@wimoor.com` 登录，验证 12 个一级菜单
- [ ] 验证清单内页面可打开

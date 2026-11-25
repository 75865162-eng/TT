# Wimoor ERP — Java + MySQL 生产系统

> 本仓库是**唯一维护版本**：以 Wimoor/WMS 为标准，把原条条 ERP（Next.js/PostgreSQL）的能力完整迁移到 Java + MySQL 栈。
> 原 Next.js/PostgreSQL 老栈**不在本仓库**，且已不再维护。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | `wimoor/wimoor-ui`（Vue 3 + Vite + Element Plus） |
| 网关 / 后端 | `wimoor/wimoor-gateway`、`wimoor-admin`、`wimoor-erp`、`wimoor-ai`（Java 11 / Spring Boot） |
| 基础设施 | MySQL 8 / Redis / Nacos（Docker Compose） |
| 数据 | MySQL 8 库：`db_admin`/`db_erp`/`db_amazon`/`db_amazon_adv`/`db_finance`/`db_quartz`/`db_quote`/`db_ai` |

- 登录账号：`admin@wimoor.com` / `123456`
- 菜单口径：12 一级 / 39 二级 / 160 三级；超级管理员 `wimoor.admin-scope=all` 可见全部

## 目录

- `wimoor/`：全部 Java 后端模块 + Vue 前端 + 初始化 SQL（`wimoor/init-config/`）
- `deploy/wimoor-p0/`：单机生产部署（docker-compose、nginx、一键脚本）
- `docs/migration/`：迁移方案、P0-P4 落地报告、验收报告、运行栈澄清
- `scripts/page-acceptance.mjs`：Playwright 逐页验收工具
- `outputs/`：验收结果

## 从零部署（新机器）

环境要求：JDK 11、Docker + Compose、Node.js（仅前端构建用）。

```bash
# 1. 编译后端（JDK 11，离线或在线二选一）
cd wimoor
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
mvn -o -pl wimoor-gateway,wimoor-admin/admin-boot,wimoor-erp/erp-boot,wimoor-ai -am -DskipTests package

# 2. 构建前端
cd wimoor-ui && npm install && npm run build && cd ..
# 产物 wimoor-ui/dist 需拷到 deploy/wimoor-p0/html/

# 3. 拉起基础设施 + 4 个后端 + 前端
cd ..  # 回到仓库根
bash deploy/wimoor-p0/deploy.sh all

# 一键恢复（沙箱休眠/重启后）
bash deploy/wimoor-p0/restore.sh
```

## 验收

```bash
cd wimoor && bash verify-migration.sh all   # 编译 + 200 单测 + 冒烟，全绿
node scripts/page-acceptance.mjs             # 浏览器逐页验收 126 个菜单 href
```

详细验收结论见 `docs/migration/PROD-CLOSURE-ACCEPTANCE-REPORT.md` 与 `docs/migration/RUNTIME-STACK-CLARIFICATION.md`。

## 诚实边界

- 环境无真实 LLM / MCP / 卖家精灵 / 西柚 凭证：领域层接口与真实 HTTP client 已就位，但未用假数据冒充业务结果。
- `wimoor.admin-scope=all` 让超级管理员看到全部菜单（验收需要）；可用配置回退到原行为。
- 公网访问：临时隧道会轮换域名；固定域名需 Cloudflare Tunnel token，见 `deploy/wimoor-p0/CF-TUNNEL-SETUP.md`。

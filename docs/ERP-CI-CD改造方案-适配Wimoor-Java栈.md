# ERP CI/CD 效率优先、可靠性不妥协改造方案（适配 Wimoor Java+MySQL 栈）

> 版本：v1.0　日期：2026-11-26
> 适用项目：`75865162-eng/TT` 的 `java-only` 分支（Java 11 + MySQL 8 + Redis + Nacos + Vue3/Vite）
> 依据：用户《ERP CI/CD 效率优先、可靠性不妥协升级》要求 + 对当前仓库/脚本/服务器的真实审计
> 标记约定：**【已有】**=代码或机制已存在可复用；**【待建】**=本方案要新增；**【阻塞】**=受外部条件限制，无法在当前环境单方面完成

---

## 0. 先说清楚一件事：参考文档不能照抄

你给的参考要求里写着 TypeScript、Next.js、Prisma、PostgreSQL、amazon-ad-bulk-operation 这些词。那对应的**是老栈（Next.js 15 + Prisma + PostgreSQL）**，也就是你明确要淘汰的那套。

本项目现在的真实栈是：

| 维度 | 参考文档口径 | 本项目实际口径 | 处理方式 |
|---|---|---|---|
| 前端 | TypeScript + Next.js | **Vue 3 + Vite 7（纯 JS，无 tsconfig）** | TypeScript 检查暂不适用；改为 Vite build + 待引入 ESLint |
| 数据库 | PostgreSQL + Prisma | **MySQL 8 + 手写 SQL 脚本** | 全部改为 MySQL；Schema Check 改为 SQL 版本表校验 |
| 构建 | npm build | **Maven（10 个 jar）+ npm run build** | 两条并行构建线 |
| 服务 | Next standalone + worker | **nohup jar + Docker Compose 基础设施** | 改为版本目录 + systemd 优雅管理 |
| 产物 | amazon-ad-bulk-operation-release | **wimoor-deploy-bundle** | 沿用现有 bundle 命名 |
| 目标机 | 159.75.203.221 / 123.207.47.225 | **43.143.28.153（你的腾讯云，常驻）** | 全部改为新机；老目标地址删除 |

**结论**：下面的方案是"重写适配"，不是"替换几个词"。凡是参考里要求、但本项目当前不具备的能力，我一律标为【待建】或【阻塞】，不假装已经完成。

---

## 1. 现状审计（全部基于实际文件，不臆测）

### 1.1 【已有】可复用的资产

| 资产 | 位置 | 现状 |
|---|---|---|
| CI 骨架 | `.github/workflows/build.yml`（java-only 分支） | 已并行：`build-backend`（JDK11 Maven）与 `build-frontend`（Node20 Vite）两个 job 同时跑，再汇入 `package-release` |
| Maven 缓存 | `actions/setup-java@v4` 的 `cache: maven` | 已启用 |
| 私有 jar 安装 | build.yml 内 `install:install-file` | 已处理 `ocean.client.java.biz.jar`→`com.biz:biz:1.0`、`aop-sdk-message-0.9.0.jar`→`alibaba.message:alibaba.message:0.9.0`；报价模块的无用 `sxjd` 依赖已移除 |
| 一键部署包 | build.yml `package-release` | 已把 jar + dist + deploy/docs/scripts 打成 `wimoor-deploy-bundle` |
| 一键拉起 | `deploy/wimoor-p0/start-all.sh` | 已能启动 10 个服务（gateway/admin/erp/amazon/finance/data/amazon-adv/quote/gen/ai） |
| 菜单收敛 | `deploy/wimoor-p0/mysql-init/02-menu-slim.sql` | 幂等脚本，隐藏 56 项、补插条条 4 入口 |
| 抗休眠验收 | `scripts/acceptance/resilient-acceptance.mjs` + `resume.sh` | 已固化 130 页清单到仓库、每页存盘、断点续跑、分批 25 页 |
| 老栈变更识别思路 | `scripts/deployment-plan.mjs`、`change-tier.mjs`、`package-ci-artifact.sh`、`release-check.sh` | 逻辑成熟（正向白名单 + 高风险路径 + 关键词），但**路径规则是 Next.js 的**，需重写为 Java 目录 |
| 生产主机 | 43.143.28.153 | 常驻、不休眠、固定 IP；防火墙已收敛为只放 8080 + 22 |

### 1.2 【待建】关键缺口

| 缺口 | 现状证据 | 影响 |
|---|---|---|
| CI 只编译 4 个 jar | build.yml 只 `-pl wimoor-gateway,wimoor-admin/admin-boot,wimoor-erp/erp-boot,wimoor-ai` | finance/data/amazon-adv/quote/gen 这 5 个服务没进产物，部署后 403/503 复发 |
| 无 SSH 部署 job | java-only 的 build.yml 到 `package-release` 就结束，没有 deploy | 产物要人工下载上传，不是自动发布 |
| SSH 参数过慢 | 老栈 `ConnectTimeout=60` + `ServerAliveCountMax=6` | 连接失败要等数分钟，违反 30 秒失败目标 |
| 固定盲等 | `start-all.sh` 里 `sleep 90` | 违反"不设置不必要固定等待" |
| 前端无质量门禁 | `package.json` 只有 `dev/build/preview`，无 ESLint / 单测 / typecheck | 参考要求的 TypeScript/ESLint/Unit Tests 在本项目**目前不存在** |
| 无 Schema 版本管理 | 全仓无 flyway/liquibase，SQL 靠手工执行 | "没变化的数据库不重复迁移"无法判断，需引入迁移版本号 |
| 无版本目录/原子切换 | jar 从固定 `target/` 路径 nohup 启动 | 无法优雅重启、无法一键回滚旧版本 |
| 无部署互斥 | 老栈有 `concurrency` 但目标机无锁 | 重复触发可能并发覆盖 |
| 无自动恢复 | 服务挂了只能人工跑 `start-all.sh` | 不满足"健康检查失败恢复旧版本" |
| 无自动报告 | 无 deployment-report | 不满足"每阶段耗时/结果/回滚原因"汇报 |

### 1.3 你的 9 条设计原则 → 本项目落地映射

| # | 你的原则 | 本项目做法 | 状态 |
|---|---|---|---|
| 1 | 独立检查并行 | CI 已并行后端/前端；再把单测、安全扫描、Schema 检查拆成独立 job 并行 | 部分已有，补齐中 |
| 2 | 产物不重复构建 | 一次 `package-release` 出 bundle，SHA256 清单，生产只解压不编译 | 部分已有，加校验 |
| 3 | 无变化不迁移 DB | 建 `schema_migrations` 版本+checksum 表 | 【待建】 |
| 4 | 无影响不跑重型回归 | 重写 `change-tier.mjs` 为 Java 路径规则，分 fast/full | 【待建】 |
| 5 | 普通升级走快速发布 | fast 档：只跑 build + 核心 API 验收 + 轻量页面巡检 | 【待建】 |
| 6 | 高风险自动加强检查 | DB/权限/Worker/部署脚本变更强制 full 档 + 人工 review 门禁 | 【待建】 |
| 7 | 出错立即停 | 每个 job `fail-fast`，部署脚本 `set -euo pipefail`，健康检查失败即止 | 【待建】 |
| 8 | 生产自动完成 | SSH 部署 job + systemd 优雅重启 + 自动验收 | 【待建，受 Secrets 阻塞】 |
| 9 | 安全/可用/隔离不妥协 | 权限隔离验收、迁移失败不盲目回滚、备份保留 | 【待建】 |

---

## 2. 目标架构

### 2.1 一次推送的完整链路（目标态）

```
git push java-only
      │
      ├─[并行] 变更识别 change-tier（输出 fast / full + 变更类型）
      │
      ├─[并行] 后端：Maven 缓存 → 安装私有 jar → 编译 10 jar → 单测
      ├─[并行] 前端：npm 缓存 → npm ci → Vite build → (待建) ESLint
      ├─[并行] 安全扫描：(待建) 依赖漏洞
      └─[并行] Schema Check：(待建) SQL 版本表 + 语法校验
      │
      ▼ 全部通过才继续
   package-release：一次打包 + SHA256 清单 + 发布元数据
      │
      ▼
   deploy-production（互斥锁）
      ├─ 预检查（并行）：磁盘/端口/依赖服务/旧版本状态
      ├─ 上传 bundle → 新版本独立目录 releases/<sha>/
      ├─ DB 迁移（仅版本变化时）+ 迁移前备份
      ├─ 原子切换 current 软链 → systemd 优雅重启
      ├─ 轻量健康检查（有上限轮询，无盲等）
      ├─ 自动验收：Web/核心API/MySQL/Redis/权限隔离/业务回归
      ├─ 失败 → 切回旧版本软链并重启（DB 迁移失败除外）
      └─ 生成 deployment-report.json + Summary
```

### 2.2 缓存与产物策略

| 项 | 做法 | 失效条件 |
|---|---|---|
| Maven 依赖 | `actions/setup-java cache: maven` | `pom.xml` 哈希变化 |
| npm 依赖 | `actions/setup-node cache: npm` | `package-lock.json` 变化 |
| 前端构建缓存 | 缓存 `wimoor-ui/node_modules/.vite` | Vite 配置 / lockfile 变化 |
| 测试缓存 | Maven surefire 增量 / Jest(待引入) cache | 源码哈希变化 |
| Playwright 浏览器 | 缓存 `~/.cache/ms-playwright` | lockfile 变化 |
| 产物复用 | 一次构建 bundle + `sha256sum` 清单，生产端校验后直接部署 | commit sha 变化 |

**防缓存污染**：缓存 key 必须含 lockfile/pom 哈希；私有 jar 安装步骤无条件执行（轻量、幂等），不缓存安装结果，避免 jar 变更被旧缓存掩盖。

### 2.3 智能变更识别（重写 `change-tier.mjs`）

按本项目目录判定 7 类变更，**正向白名单进 fast，其余默认 full（宁可漏提速，不可漏检查）**：

| 变更类型 | 触发路径 | 强制档位 |
|---|---|---|
| UI | `wimoor-ui/src/views/**`、`*.vue`、`*.css` | fast（白名单内且文件数 ≤ 12） |
| 业务逻辑 | `wimoor-*/**/service/**`、`**/impl/**` | full |
| API | `**/controller/**`、`**/api/**` | full |
| 数据库 | `**/mysql-init/**`、`**/*.sql`、`**/mapper/**` | full + DB 专项验收 |
| 权限安全 | `SysMenuServiceImpl.java`、`**/auth/**`、`**/security/**`、文件名含 auth/permission/tenant/scope | full + 权限隔离验收 + 人工 review |
| Worker | `**/worker/**`、`**/scheduled/**`、`scripts/acceptance/**` | full + Worker 验收 |
| 部署底层 | `.github/**`、`deploy/**`、`docker-compose.yml`、`nginx.conf` | full + 人工 review |

**全局基础门禁**（fast 也不能跳过）：build 成功 + 核心 API 健康检查 + 登录/菜单冒烟。路径识别若遗漏，回退 full——这条写成代码里的兜底分支，不靠人记。

---

## 3. 分阶段实施路线

> 顺序按你的要求：**先修 SSH 卡顿和部署效率，再补安全发布、自动验收和恢复**。每一阶段结束都是可交付、可回滚的。

### 阶段 1：效率优先（SSH + 并行 + 产物）——【待建，1 个工作日内】

**1.1 扩展后端构建到 10 个 jar**（改 `.github/workflows/build.yml`）

把 `-pl` 列表改为实际服务的 10 个模块，或直接用 `-pl ... -am` 覆盖 gateway/admin/admin-boot/erp-boot/amazon-boot/finance/data/gen/quote-boot/amazon-adv-boot/ai。产物汇总步骤相应 `cp` 全部 boot jar。

**1.2 加快 SSH**（新增部署 job 的 `Configure SSH` 步骤）

```
SSH_OPTS = -o ConnectTimeout=8 -o ConnectionAttempts=2 \
           -o ServerAliveInterval=15 -o ServerAliveCountMax=2 \
           -o StrictHostKeyChecking=yes -o ControlMaster=auto \
           -o ControlPath=/tmp/ssh-%r@%h:%p -o ControlPersist=60
```

- 目标：正常连接 5–10 秒；失败 30 秒内明确退出（`timeout 30 ssh ...`）。
- 一次连接执行全部部署命令（ControlMaster 复用），不重复初始化。
- 阶段化日志：`[precheck] [upload] [migrate] [switch] [health] [accept] [report]` 每阶段单独打印耗时。

**1.3 去掉盲等**

`start-all.sh` 的 `sleep 90` 改为有上限轮询：每 2 秒探一次 `/actuator/health` 或网关接口，最多 60 秒，全部 ready 立即继续；超时即失败退出并打印未就绪服务名。数据库、文件传输按实际规模等待，不设固定值。

**1.4 产物校验**

`package-release` 生成 `SHA256SUMS`，生产端 `sha256sum -c` 通过才允许切换，失败即中止——保证"生产部署的是同一产物、不在服务器上重复构建"。

### 阶段 2：安全发布（互斥 + 版本目录 + 优雅重启 + DB 版本）——【待建，1–2 个工作日】

**2.1 部署互斥**：服务器端 `flock /opt/wimoor/.deploy.lock`，抢不到锁立即失败（不排队等待），CI 侧 `concurrency: group: release-${{ github.ref }}` 保持 `cancel-in-progress: false` 防止同分支互相取消。

**2.2 版本目录 + 原子切换**：

```
/opt/wimoor/releases/<commit-short>/   # 新版本独立目录（jar + html + deploy）
/opt/wimoor/current -> releases/<old>  # 软链
```

切换用 `ln -sfn` 原子替换，服务 systemd 的 `ExecStart` 指向 `/opt/wimoor/current/...`。

**2.3 systemd 优雅重启**：为 10 个 Java 服务生成 unit，`ExecStop` 发 SIGTERM 并等待进程退出（超时再 KILL），`Restart=on-failure`。替换当前 nohup + pgrep 方式，避免"老进程没死新进程起不来"。

**2.4 DB 迁移版本化**：新增 `deploy/db/schema_migrations.sql`（建 `version`, `checksum`, `applied_at` 表）和迁移执行器——执行前算 SQL 文件 checksum，与版本表一致则**跳过**；不一致且版本更新则先 `mysqldump` 备份再执行；版本回退/不兼容则**停止并进入人工处置，不自动回滚代码**（这是你明确要求的红线）。

### 阶段 3：自动验收 + 自动恢复——【待建，1–2 个工作日】

**3.1 轻量健康检查优先**：切换后先探 `8080/` 与登录接口（有上限轮询，通常秒级通过），再跑核心业务验收。

**3.2 自动验收清单**（每次部署必须执行，按你的要求逐项对应）：

| 验收项 | 本项目检查方式 | 依赖 |
|---|---|---|
| Web 健康 | `curl -f http://127.0.0.1:8080/` | nginx |
| 核心 API | 登录 `/admin/api/v1/auth/login`、菜单 `/admin/api/v1/menus`、AI `/ai/api/v1/health` | admin/ai |
| MySQL | `mysql -e 'SELECT 1'` + 关键表计数（db_admin/db_ai） | 13306 |
| Redis | `redis-cli ping` | 16379 |
| Nacos | `/nacos/v1/console/health/readiness` + 关键服务实例数 | 8848 |
| Worker | 【待确认】Java 栈目前无独立 Worker 进程；若确认无，则改为检查异步任务写入心跳表，或明确标注"不适用" | 需你确认 |
| 文件存储 | 【待确认】当前未见 MinIO/OSS 配置；先检查上传目录可写 | 需你确认 |
| 权限隔离 | 用两个权限不同的账号验证菜单接口返回集合不同（收敛脚本已让菜单可比对） | admin |
| 业务回归 | `resilient-acceptance.mjs` 按变更档位跑：fast 跑冒烟批次，full 跑 130 页分批 | 已有脚本 |

**3.3 自动恢复**：

- 服务启动失败 → 立即停止发布，保留 `/opt/wimoor/logs/<sha>/` 故障日志。
- 健康检查失败 → `current` 软链切回旧版本 + systemd 重启 + 再次健康检查与业务冒烟。
- DB 迁移不兼容 → **停止自动恢复操作**，发通知进入安全处置流程（牢记：数据库可能已部分变更，回滚代码会造成更大不一致）。

### 阶段 4：自动报告 + 真实验证——【待建，受外部条件阻塞】

**4.1 报告字段**（`deployment-report.json` + GitHub Job Summary）：部署版本（sha）、总耗时、每阶段耗时、变更类型、执行测试列表、部署结果、业务验收通过率、是否回滚、失败原因。

**4.2 你要求的 7 项生产验证**——当前能否执行，逐项如实标注：

| # | 验证项 | 当前是否可验 | 说明 |
|---|---|---|---|
| 1 | 普通代码变更快速部署 | 【阻塞】 | 需先把 SSH 私钥写入 GitHub Secrets 并配置 production environment |
| 2 | 数据库变更安全部署 | 【阻塞】 | 需先完成阶段 2 的 schema_migrations |
| 3 | 部署失败快速停止 | 【阻塞】 | 需先有真实 deploy job 才能构造失败场景 |
| 4 | 服务异常安全恢复 | 【阻塞】 | 需先有版本目录 + systemd + 回滚脚本 |
| 5 | 重复触发不并发覆盖 | 【部分已有】 | CI 侧 concurrency 已有；服务器侧 flock 待建 |
| 6 | Worker 和 Redis 队列不受破坏 | 【阻塞】 | 需先确认 Worker 形态；红线：任何脚本不得 `FLUSHDB/FLUSHALL` |
| 7 | 生产真实业务验收通过 | 【部分已有】 | 130 页断点续跑脚本已就绪，但需要在服务稳定窗口跑完 |

---

## 4. 效率目标与耗时对比（不虚报）

**当前可实测的基线**（2026-11-26 实测）：

| 指标 | 实测值 |
|---|---|
| 8080 前端响应 | HTTP 200，约 54 ms |
| 登录接口 | HTTP 200 |
| 沙箱资源 | 磁盘剩 237G、内存可用 101G（非瓶颈） |
| 沙箱生命周期 | 约每 20–30 分钟回收，杀掉所有后台服务 |

**其余耗时项我没有精确采集，不编造数字。** 按你的要求"最终提供升级前后耗时对比"，采集方法固定为：在 GitHub Actions 每个 step 前后打时间戳，写入 `deployment-report.json`；升级前先跑 1 次现有流程采集基线，升级后跑同样场景对比。下表是**目标值**，不是已达成的结果：

| 环节 | 当前状态 | 目标 | 性质 |
|---|---|---|---|
| SSH 正常连接 | 未启用 / 老脚本 60s 超时 | 5–10 秒 | 目标 |
| SSH 失败判定 | 可达数分钟 | ≤ 30 秒 | 目标 |
| 代码检查 | 后端/前端已并行，其余串行 | 全部独立 job 并行 | 部分已有 |
| 依赖安装/构建 | 有 Maven/npm 缓存 | 缓存命中不重复安装 | 部分已有 |
| 固定等待 | `sleep 90` | 0（有上限轮询） | 待建 |
| 普通变更路径 | 无分级 | fast 档快速发布 | 待建 |
| 生产构建 | 无自动部署 | 0（本地构建，生产只部署） | 待建 |
| 回滚 | 手动 | 自动（DB 例外） | 待建 |

> 完工后必须把"目标"列替换成**实测数据**并提交对比报告；现在这张表不能当作已完成证据。

---

## 5. 禁止事项 → 本项目硬约束

| 你的禁止项 | 本项目落地 |
|---|---|
| 跳过必要测试 | fast 档也必须过 build + 核心 API + 冒烟；路径识别遗漏回退 full |
| 无差别全量重型回归 | 由 `change-tier` 分级，130 页分批按档位执行 |
| 每次重复构建 | bundle 一次构建 + SHA256，生产端校验后直接部署 |
| 无条件 DB 迁移 | `schema_migrations` checksum 一致则跳过 |
| 无限 SSH 等待 | `ConnectTimeout=8` + `timeout 30` + 有限重试 |
| 清空 Redis 队列 | 部署脚本禁止出现 `FLUSHDB/FLUSHALL`，代码评审检查项 |
| 覆盖生产配置 | 配置随版本目录走，不原地覆盖；迁移不改现有配置 |
| 删除用户文件/业务数据 | 上传目录与业务库不在发布目录内，滚动更新不触碰 |
| 不兼容迁移后盲目回滚 | 迁移失败停止自动恢复，转人工安全处置 |
| 引入复杂基础设施 | 复用现有 GitHub Actions + Docker Compose + systemd，不上 K8s 之类 |
| 改无关业务模块 | 本改造只动 CI/CD、deploy、scripts、必要配置，不碰业务代码 |

---

## 6. 阻塞项与外部依赖（明确列出，不虚报完成）

1. **GitHub Secrets 未配置**【阻塞全部自动部署】：需要在 `TT` 仓库 Settings → Secrets and variables → Actions 添加：
   - `PRODUCTION_SSH_PRIVATE_KEY`（对应 43.143.28.153 上 root 的公钥）
   - `PRODUCTION_SERVER_HOST = 43.143.28.153`
   - `PRODUCTION_SERVER_USER`（如 root）
   - `PRODUCTION_SERVER_DIR`（建议 `/opt/wimoor`）
   并把 22 端口安全组确认对 GitHub Actions 出口可达（你当前防火墙保留了 22）。
2. **production environment**：GitHub 仓库需创建 `production` environment 并可配置 required reviewers（高风险变更人工确认用）。
3. **Worker 形态未确认**：Java 栈是否有独立后台 Worker 进程？老栈的 `deploy/systemd/amazon-*-worker.service` 是 Next.js 遗留，不能直接套用。需你确认后决定 Worker 验收方式。
4. **文件存储位置未确认**：当前未见 MinIO/OSS 配置，需确认上传文件落在本地磁盘还是对象存储，才能设计验收与备份。
5. **真实生产部署验证窗口**：7 项验证会实际改动 43.143.28.153，需在维护窗口进行；验证期间系统可能短暂不可用。
6. **前端质量门禁缺失**：ESLint/单测需新增依赖，会改 `package-lock.json`（属于"部署底层"高风险变更，本身要走 full 档）。

---

## 7. 立即可执行的第一步（不依赖外部条件）

不需要 Secrets、不需要动生产，现在就能做、且能立刻看到收益：

1. 把 build.yml 的后端 `-pl` 扩到 10 个服务 jar，让产物完整；
2. 把 `start-all.sh` 的 `sleep 90` 改成有上限轮询；
3. 给 bundle 生成 `SHA256SUMS`；
4. 重写 `scripts/change-tier.mjs` 的路径表为 Java 目录，并加"识别失败回退 full"兜底；
5. 新建 `schema_migrations` 建表 SQL 与幂等执行器（先落代码，不连生产）。

这 5 项做完，CI 的产物完整性和效率就已经改善，且全部可本地验证；之后只要你提供 GitHub Secrets，阶段 2–4 就能接上真实生产部署验证。

---

## 8. 交付判定标准（完工前逐条打勾）

- [ ] 10 个服务 jar 全部进 bundle，生产端 SHA256 校验通过
- [ ] SSH 正常 ≤10 秒、失败 ≤30 秒，日志分阶段
- [ ] 无固定 sleep，全部有上限轮询
- [ ] 变更识别 7 类可判定，遗漏回退 full，fast/full 测试档位生效
- [ ] 版本目录 + 原子切换 + systemd 优雅重启可用
- [ ] DB 迁移有版本号/checksum，无变化跳过，备份与不兼容处置到位
- [ ] 9 项自动验收全部有对应检查脚本（Worker/文件存储按确认结果落地）
- [ ] 失败自动恢复（DB 例外）+ 故障日志保留
- [ ] deployment-report 生成且含每阶段耗时
- [ ] 7 项生产验证在真实 GitHub Actions 上跑过，未跑的明确标注原因
- [ ] 升级前后耗时对比用实测数据填写，非估算

> 本方案是**改造设计文档**，不是"已完成的部署"。当前真实状态：阶段 1–4 均为【待建】，生产自动部署受 GitHub Secrets【阻塞】。我不会把设计写成已实现。

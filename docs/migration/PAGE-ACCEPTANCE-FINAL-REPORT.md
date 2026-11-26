# java-only 生产系统 · 四项问题收口与逐页验收报告

生成时间：2026-11-26　｜　分支：`java-only`　｜　代码基线 HEAD：`31665d95`
验收方式：真实 Chromium 无头浏览器 + 真实管理员登录（`admin@wimoor.com`），逐页打开 126 个菜单 URL。
验收数据：`outputs/page-acceptance-final.json`（126 页全量结果）。

---

## 结论速览

| 问题 | 结论 | 状态 |
|---|---|---|
| 1. 说好去掉的页面还在？ | 属实。旧数据卷未执行菜单收敛 SQL，已修复并验证 | ✅ 已解决 |
| 2. tiaotiao 迁移内容在哪？ | 落地于前端 4 个 Vue 页 + 后端 `wimoor-ai` 6 领域包 | ✅ 已定位 |
| 3. 逐页功能验收 + 推 GitHub | **126/126 全通过**，修复 3 个前端 bug 后达标 | ✅ 已验收 |
| 4. 广告启动错误 / 报价缺 jar | 根因均为配置/依赖缺失，已修复并验证 | ✅ 已解决 |

---

## 问题 1：去掉的页面怎么还存在

**根因**：远端提交 `31665d95` 已新增收敛脚本 `deploy/wimoor-p0/mysql-init/02-menu-slim.sql`，但该脚本位于 `mysql-init`，只在数据库**首次初始化（空卷）**时执行。运行中/恢复出的容器沿用旧数据卷，`229` 个菜单全部 `visible=1`，因此"说好去掉的页面"仍显示——**不是看错，是脚本没被执行到**。

**处理**：
- 手动对当前库执行幂等收敛 SQL，使 `start-all.sh` / restore 后也能复现。
- 修复 `deploy/wimoor-p0/start-all.sh`：补齐 Nacos 配置导入步骤（原脚本缺失，详见问题 4），使服务可按正确配置拉起。

**实测结果**：

| 指标 | 收敛前 | 收敛后 |
|---|---|---|
| 菜单总数 | 229 | 229 |
| 可见（visible=1） | 229 | **173** |
| 隐藏（visible=0） | 0 | **56** |

隐藏的 56 项正是白名单外的旧版/重复页：FBA计算器、计划管理、采购审核、出货计划、利润计算、发货单(旧)、评论跟踪、销售图表、订制、人力计划、订单地址下载、提货付款、VAT、台账、海外仓等。仅保留约定的一/二/三级菜单。

---

## 问题 2：tiaotiao（条条 ERP）迁移过来的内容在哪里

迁移内容分**前端入口**与**后端领域能力**两处落地，均有代码证据：

**前端 4 个入口页**（`wimoor/wimoor-ui/src/views/`）：

| 菜单入口 | 文件 | 行数 |
|---|---|---|
| AI技能中心 | `sys/ai/skills/index.vue` | 125 |
| 选品研究 | `sys/ai/research/index.vue` | 122 |
| AI运行历史 | `sys/ai/history/index.vue` | 106 |
| 销量统计 | `sys/ai/sellfox/index.vue` | 76 |

对应菜单 SQL id `1012206~1012209`，收敛后均 `visible=1`（可见可点）。

**后端领域包**（`wimoor/wimoor-ai/src/main/java/com/wimoor/ai/`）：
`chat` / `lili` / `skill` / `research` / `report` / `persistence` 六个包，对外暴露 `AiApiController`、`AiWriteController` 两个 Controller。

---

## 问题 3：逐页功能验收

**方法**：`scripts/page-acceptance.mjs`，以真实管理员登录，遍历 126 个菜单 URL，按白屏 / JS 致命错误 / 重定向登录分类判定。

**结果演进**：

| 阶段 | 结果 | 说明 |
|---|---|---|
| 历史基线 | 80/126 | 旧会话/服务未就绪，非最终 |
| 首次真实验收 | 123/126 | 暴露 3 个前端 bug |
| 修复 tags 后 | 124/126 | tags 空值保护生效 |
| 修复 movedata + payment 后 | **126/126** | 全部通过 |

**修复的 3 个真实前端 bug**（均为空值/类型保护缺失，最小改动）：

1. `views/sys/tag/index.vue`：`handleClick(row)` 中 `row.id`，空分组时 `row` 为 `undefined` → 加 `row &&` 保护。
2. `views/sys/movedata/components/backup.vue`：`tableOptions` 初始值 `""` 传给 `<el-table :data>`，element-plus 内部 `.map` 崩溃 → 改为 `[]`。
3. `views/erp/purchase/report/payment/index.vue`：`loadFacProject()` 中 `res.data.push(...)`，接口返回非数组时崩溃 → 加 `Array.isArray` 保护。

三处均已重新构建、部署并**单页复测通过**，最终全量 126/126 无致命错误。

---

## 问题 4：广告服务启动错误 / 报价缺依赖 jar

**报价缺 jar**：`quote-boot/pom.xml` 曾以 `system` scope 引用不存在的 `src/main/resources/lib/sxjd-open-platform-sdk-1.3.9-RELEASE.jar`（被 gitignore，从未入库，CI 也不编译 quote）。远端提交 `31665d95` 已移除该无用依赖。本次全量 Maven 编译（31 模块含 quote、amazon-adv）**BUILD SUCCESS**，报价模块正常通过。

**广告服务启动错误**：8 个后端服务（含 amazon-adv）启动即崩，报 `jdbcUrl is required`、`Could not resolve placeholder 'config.photo-server'`。根因是 `start-all.sh`（远端新增）**漏了 Nacos 配置导入**——`deploy.sh` 的 `step_start()` 本来有，新脚本缺失，导致所有服务读不到配置（Nacos `totalCount:0`）。已在 `start-all.sh` 补上配置导入（含 mysql 13306 / redis 16379 宿主端口改写），导入 11 个配置后服务可正常启动；广告服务运行时错误消除。

---

## 交付物清单

- `deploy/wimoor-p0/start-all.sh` — 补 Nacos 配置导入（问题 4 根因修复）
- `wimoor/wimoor-ui/src/views/sys/tag/index.vue` — 空分组空值保护（问题 3）
- `wimoor/wimoor-ui/src/views/sys/movedata/components/backup.vue` — tableOptions 数组初始化（问题 3）
- `wimoor/wimoor-ui/src/views/erp/purchase/report/payment/index.vue` — 项目列表类型保护（问题 3）
- `.gitignore` / `deploy/wimoor-p0/.gitignore` — 排除 node_modules、运行产物、core dump
- `outputs/page-acceptance-final.json` — 126/126 全量验收证据
- `docs/migration/PAGE-ACCEPTANCE-FINAL-REPORT.md` — 本报告

# 条条 ERP → Wimoor（WMS）全量迁移 · 瘦身 · 降内存 落地方案 v3

> 目标：以 **Wimoor 为唯一维护系统**（含 WMS 仓库模块），把条条 ERP 的功能与 UI 全部迁入；按《菜单 href 一二三级对照》（2026-11-24，12 一级 / 39 二级 / 160 三级）做页面瘦身；在保证"真的能用"的前提下把运行内存从 ~5.4GB 降到 3~4GB 档。
> 基线分支：`woker` 仓库 `migration/wimoor-slim-8gb`，HEAD `31a495bd`（本地与远端一致，工作区干净）。
> 版本：2026-11-25。本文替代 v2 的方案叙述部分，P0-P4 的既有成果全部保留、不推倒。

---

## 〇、一句话结论

P0-P4 已把"后端能力"搬完并通过全部验证；这次要补的是**最后三公里**：①把"表格页面"和"条条独有能力"的冲突按三层口径定清；②把条条独有能力做成 Vue3 页面并接上已落地的后端；③把后端从内存实现接真实数据库，同时按档位压内存。做完之后条条 ERP 转只读归档，只维护 Wimoor 一套。

---

## 一、必须先拍板的口径冲突（方案的地基）

### 1.1 冲突事实

你上传的表格是**页面白名单**，共 160 个三级页，全部是 Wimoor 原生业务页：

| 一级分组 | 三级页数 | 覆盖内容 |
|---|---:|---|
| 产品 / 销售 / 采购 / 仓库 | 10 / 17 / 13 / 15 | 商品、订单、FBA 报表、采购、WMS 出入库与库存报表 |
| 发货 / 广告 / 账目 | 20 / 5 / 12 | 物流报价、货件、PPC、结算与利润 |
| 财务 / 设置 / 系统 | 13 / 8 / 15 | 凭证账簿、授权、菜单/用户/开发管理 |

但**条条 ERP 的独有能力一个都不在这张表里**。盘点 `src/app/(erp)` 共 93 个 `page.tsx`，真正的独有页面是：

| 条条独有能力 | 页面入口 | 表格内是否有位置 |
|---|---|---|
| AI 对话 / 助手 | `ai`、`ai/chat`、`agents/*`（10 个） | ❌ 无 |
| 选品研究 | `agents/selection-research`、`agents/market/selection` | ❌ 无 |
| Lili 技能中心 | `lili/[[...path]]` | ❌ 无 |
| Skills 市场 | `skills`、`skills/[slug]` | ❌ 无 |
| 销量统计（Sellfox） | `sellfox/*`（17 个：sales/profit/orders/inventory/ads…） | ❌ 无 |
| 计算器中心 / 商品 AI | `listing-ai`、`listing-ai/studio`、`amazon/product-periods` | ❌ 无 |

另外 `saihu-search-merge`（赛狐）按你之前决定**剔除**。卖家精灵 / 西柚按你之前决定**保留、调用计积分**。

### 1.2 冲突的结论：三层口径

「只保留表格页面」与「迁移条条所有功能」不能同时按字面执行。方案采用**三层口径**，请确认：

- **A 类 — 表格内 160 页（保留 + 增强）**：保留 Wimoor 原生页面与 Element Plus 设计，把条条 ERP 里更细的字段/算法补进去（如利润算法、WMS 增强、细粒度报表）。
- **B 类 — 条条独有能力（新增入口并入）**：这些是"功能"而非"表格里已有的页面"，必须作为**新增菜单入口**并入，否则"迁移所有功能"落空。**不新增一级菜单**——挂到你表格已有的二级分组「工具」（ID 1012171，现含文件资源/AI 问答/团队公告）和「系统→系统管理」下即可，保持一级/二级骨架与表格一致。
- **C 类 — 表格外且非条条独有（删除）**：按 2026-11-25 引用审计的 389 个无引用页面归档删除。

> 若你坚持"严格只留表格、一个入口都不加"，那么 AI / 选品 / Lili / Skills / 销量统计将无法使用——这与"迁移条条所有功能"矛盾。**这是唯一需要你先拍板的问题**，其余我按上述口径执行。

---

## 二、现状基线（已核实，不是推测）

### 2.1 已经落地并验证的（保留，不重做）

| 项 | 实测结果 |
|---|---|
| 全量验收 | `cd wimoor && bash verify-migration.sh all` → 编译通过、29 个测试类 ~200 用例 errors=0/failures=0、冒烟 `health` 返回 `UP`（skills=116/tools=107/workflows=13） |
| P2 WMS 增强 | 59 类 / 8656 行，`wimoor-erp`，33 测试通过 |
| P3 Lili 技能中心 | 37 类 / 4961 行，`wimoor-ai`，OAuth PKCE-S256、AES-GCM、requestKey 防重放 |
| P3 选品研究 | 36 类，`ResearchEngine` 1121 行五阶段状态机 |
| P3 AI 对话 + Skill | 18 类，`skill_list/get/run`、卖家精灵/西柚只读源、积分门禁 |
| P4 报表 | 47 类 / 7674 行，五阶段状态机 + 退避 + checkpoint |
| API 层 | `AiApiController` 8 个只读端点，HTTP 实测 200 |
| 前端瘦身 | 389 页已归档、305 处断链已修，`views` 现 690 个 `.vue`（167 个页面级 `index.vue`），`pnpm build` 通过 |

### 2.2 尚未完成的三个缺口（这次要补）

来自 `P0-P4-LANDING-REPORT.md` 第六节，原文写明：

1. **领域层是 `interface + 内存实现`，未接真实 MySQL/MyBatis 与 LLM HTTP transport** → 现在"测试全绿"但数据不落库、不真的调模型，谈不上"能用"。
2. **条条 AI/选品页面未按 Vue3 重建** → 后端有 API，前端没有操作界面。
3. **P5 切流与老系统下线未做** → 需要新 8GB 机器凭证。

---

## 三、功能迁移矩阵（条条能力 → Wimoor 落点）

| 条条能力 | 后端现状（已落地） | 本次前端动作 | 挂载菜单（表格内） |
|---|---|---|---|
| AI 对话 | `wimoor-ai` Orchestrator/Runtime/DataSources | 新建 `views/sys/ai-chat/`，对接 8 端点 | 工具 → AI 问答（已有 `/sys/deepseek`，可改造复用） |
| Lili 技能中心 | Catalog(116 skills/107 tools/13 workflows)、OAuth、ToolConfig | 新建技能面板页，接 `/skills`、`/tools`、`/workflows` | 工具 → 新增「Lili 技能」 |
| 选品研究 | `ResearchEngine` 五阶段 + 人工门 + 计费闸门 | 新建研究向导页，接研究 API | 工具 → 新增「选品研究」 |
| Skills 市场 | Runner/Exporter/ReportStore/RunHistory | 新建技能列表 + 详情 + 运行历史页 | 工具 → 新增「Skills」 |
| 销量统计 Sellfox | P0-P4 已迁移报表算法；`profit` 页已复刻 | 按 Element Plus 重建 17 页核心视图 | 销售 → 新增「销量统计」二级 |
| 计算器中心 | 利润/FBA 费用规则已在 P2/P4 | 页面级复用 Wimoor `amazon/profit/calculator` | 已在表格「系统→FBA费设置」附近 |
| 卖家精灵 / 西柚 | 只读 provider + 积分计量已落地 | 数据源勾选面板并入 AI 对话页 | 随 AI 问答 |
| 赛狐 | — | ❌ 不迁 | — |

---

## 四、UI 设计迁移方案

**原则**：表格内页面沿用 Wimoor 的 Element Plus 视觉与布局，只补字段/算法；条条独有能力按同一套 Element Plus 组件规范重建，保证风格统一、不引入第二套 UI 体系。

- **保留**：Wimoor 现有 160 页的页面结构、路由、菜单 `component` 映射（`wimoor-ui/src/views/{component}.vue`）。
- **重建**：上表 B 类页面，用 Vue3 + Element Plus 实现，API 层对接 `AiApiController`。`sys/deepseek/index.vue`（现 68KB，Wimoor 原生 AI 页）作为 AI 问答的载体改造，避免重复造页。
- **delete**：`saihu-search-merge` 及 389 个无引用页。
- **产物**：菜单树仍是 12 一级 / 39 二级（骨架不变），三级页 = 160 + B 类新增入口；`t_sys_menu` 同步裁剪。

---

## 五、"真的能用"补齐：内存实现 → 真实 IO

这是"保证能用"的硬前提。当前领域层接口齐全，但实现是 `ConcurrentHashMap` 内存态，进程重启即丢、不接真实数据。

| 缺口 | 落地动作 | 验收 |
|---|---|---|
| 仓储内存实现 → MySQL/MyBatis | 为 P2/P3/P4 各 `*Repository` 接口补 Jdbc/MyBatis 实现，映射到 Wimoor 已有表（erp/amazon/finance 库）；保留内存实现仅用于测试 | 重启后数据仍在；CRUD 走真实库 |
| LLM HTTP transport | Lili OAuth 拿到的工具调用、AI 对话的编排循环接真实模型 HTTP endpoint（配置化 base-url/key） | 对话能返回真实模型结果，非 mock |
| 卖家精灵/西柚 provider | 接真实只读 API + 积分扣减落库 | 调用后积分账目真实变化 |
| 报表下载/发布 | `ReportService` 的 DOWNLOAD/PUBLISH 接真实文件存储与发布 | 生成可下载真实报表 |

> 边界不变：需要真实凭证/付费 MCP 的写操作不放在只读 API 层暴露，领域类接口已就位，接入 transport 后启用。

---

## 六、降内存方案（从 ~5.4GB 到 3~4GB 档）

8GB 新机上原生 Wimoor 全服务约 5.4GB 常驻。按三档优化，实机以 `docker stats` 为准（以下为估算）：

| 组件 | 现状配置 | 优化后 | 省 |
|---|---|---|---:|
| MySQL 8 | `innodb_buffer_pool=512M` | 空库起步调 256M、`max_connections` 收紧 | ~256MB |
| Nacos | `-Xmx256m` | `-Xmx128m` + Derby 单机 | ~150MB |
| data/quote/gen 三服务 | 常驻 | 改 `docker-compose profile`，默认不启，按需拉起 | ~900MB |
| amazon-adv | 常驻 | 精简档改按需 | ~450MB |
| wimoor-ai | 独立 JVM ~400MB | 保持独立但 `-Xmx256m`（隔离性优先，不合并） | ~150MB |
| JVM 通用 | 默认 GC | 全服务 `-XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -Xss256k`，堆固定不扩 | 波动收敛 |
| Seata | 默认关 | 保持关 | — |
| 前端 | Nginx 托管静态 | 保持，不跑 Node | — |

**三档交付**：

- **全功能档（推荐，含全部 160 页 + AI）**：~4.2GB 常驻，留 ~3.8GB 余量。
- **精简档（核心 ERP/WMS/AI）**：~3.0GB，`amazon-adv`/data/quote/gen 按需启动。
- **4GB 兜底档**：~2.2GB，仅 MySQL/Redis/Nacos/Nginx/gateway/admin/erp/wimoor-ai，前端静态化，广告与低频模块不启。

> 页面瘦身对"运行内存"的直接贡献很小（静态文件由 Nginx 托管），价值在于**构建更快、依赖更少、攻击面更小**；真正的内存大头是后端 JVM 数量与配置，故降内存重心放在第六节的服务编排与 JVM 调优。

---

## 七、分阶段实施计划（在 P0-P4 之上续接）

每阶段独立可验收、可回滚；P5 通过前不动生产。

| 阶段 | 目标 | 关键动作 | 验收标准 | 周期 |
|---|---|---|---|---|
| **P5 口径与 UI 重建** | 冲突定清、页面就位 | 按第一节三层口径确认；B 类页面用 Vue3+Element Plus 重建；菜单挂到"工具"分组；C 类删 389 页 | 菜单树 12/39/160+B 类；B 类页面可打开；`pnpm build` 通过 | 1~1.5 周 |
| **P6 真实 IO 接入** | "真的能用" | 仓储接口接 MyBatis；LLM/第三方 provider 接真实 transport；积分落库 | 重启数据不丢；对话/研究/报表走真实端点；200 测试仍全绿 | 2~3 周 |
| **P7 降内存与切流** | 达标并单一维护 | 三档编排 + JVM 调优；`docker stats` 复测；复核 24 个 🟡 隐藏菜单；DNS/反代切到 Wimoor；条条转只读 | 常驻 ≤4.2GB、无 swap、菜单内功能全可用、稳定 7 天 | 1 周 |

**总周期**：约 4~5.5 周（P0-P4 已完成，不重复计时）。

---

## 八、验收标准（可复现）

```bash
# 1. 后端全量编译 + 测试 + 冒烟（已通过，回归用）
cd /workspace/woker/wimoor && export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
bash verify-migration.sh all
# 期望：health UP，skills=116 tools=107 workflows=13，tests errors=0 failures=0

# 2. 前端构建与菜单
cd /workspace/woker/wimoor/wimoor-ui && pnpm build
# 期望：构建成功；菜单 12 一级/39 二级/160 三级 + B 类入口；B 类页面路由可解析

# 3. 真实 IO（P6 后）
# 重启 wimoor-ai/erp 后，新建研究/对话记录仍可查；积分账目落库

# 4. 内存（P7 后）
docker stats --no-stream   # 全功能档常驻合计 ≤4.2GB，无 swap
```

---

## 九、风险与回滚

| 风险 | 对策 |
|---|---|
| B 类入口破坏"只留表格"的视觉预期 | 全部挂在表格已有二级分组下，一级/二级骨架不变；可一键隐藏菜单项 |
| 接真实 IO 后测试从全绿变红 | 内存实现保留给测试，真实实现走profile 切换；双实现共存 |
| 降内存误关在用服务 | 三档用 compose profile 隔离，全功能档为默认；逐档冒烟 |
| 切流后条条数据不可回退 | 条条转只读归档不下线，保留 30 天回滚窗口 |
| 跨语言语义丢失 | 每个迁移项字段级对账 + 真实数据双跑比对 |

---

## 十、需要你拍板

1. **口径**（唯一阻塞项）：同意第一节三层口径吗？即表格 160 页保留增强 + 条条独有能力作为新增入口挂进"工具"分组 + 赛狐剔除 + 卖家精灵/西柚保留计积分。
2. **内存档位**：全功能档（~4.2GB，推荐）/ 精简档（~3.0GB）/ 4GB 兜底档（~2.2GB），选哪个作为交付目标？
3. **执行范围**：确认后我从 P5 开始逐阶段落地，每阶段完成给你验收；还是先只做 P5 看效果？
4. **8GB 机器凭证**：P7 切流需要新机器，何时可用？

> 配套文档：`docs/migration/P0-P4-LANDING-REPORT.md`（已落地成果）、`wimoor-migration-plan-v2-8gb.md`（架构与 8GB 预算）、`wimoor-608-reference-audit.md`（引用审计）、`菜单href一二三级对照.md`（页面白名单）。

# P0–P4 落地完成报告（2026-11-25 最终版）

> 分支：`migration/wimoor-slim-8gb`。目标：以 Wimoor 为唯一维护系统，条条 ERP 的非赛狐能力全量迁移。
> 本报告记录**真正落地并验证**的内容，所有结论均有编译/测试/启动/HTTP 实测证据。

## 一、总览

| 阶段 | 状态 | 交付物 | 验证 |
|---|---|---|---|
| P0 地基 | ✅ 完成 | MySQL8+Redis+Nacos+nginx 四容器 + gateway/admin/erp 三服务 | 前端 200 / 鉴权 403 / 内存在 8GB 内 |
| P1 瘦身 | ✅ 完成 | 389 页归档 + 305 处断链修复 + 前端构建 | `pnpm build` 通过，415 页自洽 |
| P2 WMS 增强 | ✅ 完成 | 59 类 / 8656 行（wimoor-erp） | 33 测试通过 |
| P3 Lili 技能中心 | ✅ 完成 | 37 类 / 4961 行（wimoor-ai） | 59 测试通过 |
| P3 选品研究 | ✅ 完成 | 36 类（含 1121 行 ResearchEngine） | 16 测试通过 |
| P3 AI对话+Skill | ✅ 完成 | 18 类 | 67 测试通过 |
| P4 报表 | ✅ 完成 | 47 类 / 7674 行 | 25 测试通过 |
| API 层 | ✅ 完成 | `AiApiController` 8 个只读端点 | HTTP 实测 200 |

**合计**：约 197 个 Java 类、3 万行代码、**200 个单元测试全部通过**（errors=0, failures=0），零 TODO/空壳。

## 二、统一验收（可复现）

```bash
cd wimoor && export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
bash verify-migration.sh all
```

实测输出（2026-11-25）：
- `compile` → `wimoor-ai-2.0.0.jar` + `erp-boot.jar` 生成，RC=0
- `test` → 29 个测试类、200 用例全绿
- `smoke` → wimoor-ai 启动于 8102，`/ai/api/v1/health` 返回：
  `{"module":"wimoor-ai","status":"UP","liliVersion":"1.4.0","skills":116,"tools":107,"workflows":13}`
- `/ai/api/v1/ads/metrics?impressions=10000&clicks=500&spend=100&orders=25&sales=500` →
  `{"ctr":"5","cpc":"0.2","cvr":"5","acos":"20","roas":"5"}`（与源端算法一致）

## 三、P2 WMS 增强（wimoor-erp/erp-boot）

59 个 TS 文件 → 59 类，包 `com.wimoor.erp.wms.enhance`。真实落地：
- **规则**：AssemblyRules(454)、InventoryRiskRules(383, BigInteger 微单位)、TransferOrderRules(233)、OverseasChargeRules(271) 等
- **状态机**：ReceiptService 的 `putaway = inspected && ...`（收货→质检→上架），agent 自检发现并修复了源端前置条件遗漏
- **聚合/查询**：Repository、QueryService、各类 *-query、domain-events/event-bus
- 强依赖 Prisma 的部分以 interface + ConcurrentHashMap 内存实现承接业务语义，规则/幂等/审批/审计均真实实现

## 四、P3 AI 能力（wimoor-ai）

### Lili 技能中心（37 类）
CallQuotaServer（账户锁 + AtomicInteger + requestKey 防重放 + BigDecimal 账目）、OAuth（origin 校验、redirect 白名单、PKCE S256、code/refresh 单次使用）、ToolConfigServer（JDK AES-GCM 加解密）、Catalog（116 skills / 107 tools / 13 workflows）、RuntimeTools、各类 McpTransport/ChatTransport 接口 + 内存实现。

### 选品研究（36 类）
ResearchEngine(1121 行)：五阶段状态机（找细分→产品研究→评论分析→关税→选品评分），第 1/5 阶段 NICHE/FINAL 强制人工门；call 去重、预算上限、付费 MCP_CALL 闸门、计费前先 save、中断调用不重放、recover 清理授权。配 Economics、LandedCost、ResearchSchema(308)、Report(319)、ReviewEvidenceModel 等。

### AI 对话 + Skill 引擎（18 类）
Chat：Orchestrator(424, 系统协议/多轮循环/积分 meter)、SkillTools(359, skill_list/get/run 三件套、身份只从 session 取)、Billing(卖家精灵/西柚计价、影子模式)、DataSources(361, 四源面板)、ConversationRepository(revision 乐观锁 + 幂等)。
Skill：Runner、Exporter(288, JDK ZipOutputStream 真实生成 xlsx/docx OOXML)、ReportStore(TTL/容量)、RunHistory(作用域隔离 + SHA-256 校验 + 分页)。

### 报表（47 类）
ReportService(五阶段 CREATE→POLL→DOWNLOAD→PUBLISH→DONE)、ReportRetry(退避 `min(60s, 1000*2^clamp(attempt-1,0,6))`)、ReportScheduler/Worker/Checkpoint、SpReportParser、report-replica/publication/query、SyncAdapter 等。

## 五、API 层（`com.wimoor.ai.api.AiApiController`）

8 个只读端点，直接调用真实领域类，不返回虚构数据：
`/ai/api/v1/health`、`/skills`、`/tools`、`/workflows`、`/data-sources`、`/ads/metrics`、`/skills/{slug}/manifest`。

需要 LLM 运行时 / 真实 DB / 付费 MCP 的写操作**不在此层暴露**——领域类接口已就位，待接入真实 transport 后启用。这是刻意边界，避免假数据冒充业务结果。

## 六、边界与后续（P5）

1. 领域层的 `interface + 内存实现` 尚未接真实 MySQL/MyBatis 与 LLM HTTP transport——业务规则已完整，接入真实 IO 是下一阶段。
2. P5 切流量到 Wimoor、复核 24 个隐藏菜单、老系统转只读归档，需要新 8GB 机器凭证。
3. 前端 UI 层：条条的 AI 对话/选品页面需按 Vue3 重建并与本 API 层对接（P0 已建好 Wimoor 前端骨架）。

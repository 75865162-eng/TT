# 生产级闭环收口 · 实施清单（P5-P6-P7）

> 依据：用户 2026-11-25 拍板——三层口径通过；UI 重建 + 真实 IO 接入开始做；8GB 内存足够、暂不压缩；先瘦身去掉不需要的；生产级闭环收口后做生产级验收循环。
> 分支：`migration/wimoor-slim-8gb`。基线：P0-P4 已全量落地（197 类 / 3 万行 / 200 测试全绿）。

## 调研结论（决定实现边界）

1. **wimoor-ai 现状**：pom 仅 `spring-boot-starter-web` + test，**无数据库依赖**；47 个 interface 中大多数是内部策略/transport，真正需要真实 IO 的是少数持久化仓储与外部调用。
2. **可复用的本地依赖**（无需联网）：`spring-boot-starter-jdbc:2.6.13`、`mysql-connector-java:8.0.33` 已在 `~/.m2`；根 pom 已管理 mybatis-plus 3.4.3、druid。
3. **必须保留内存实现**：`InMemoryConversationRepository`(325行)、`InMemoryPointsLedger`、`InMemoryResearchStore/Repository`、`LlmClient.InMemory` 等承载全部业务规则且支撑 200 测试。真实实现走 `@ConditionalOnProperty(ai.persistence=jdbc)`，默认内存，测试不受影响。
4. **已有真实 transport 骨架**：`LiliTransport`（JSON-RPC/MCP，宿主注入 HttpFetcher）、`SellerTransport`、`ReportingV1/V3Transport` 都已实现协议，只差注入真实 HTTP fetcher。
5. **页面基线**：`views` 现 690 个 .vue（167 个页面级 index.vue）；表格 160 个三级页 + B 类新增入口；赛狐入口 `saihu-search-merge` 待剔除。

## 需要做的最小闭环（按此执行）

### A. 瘦身（先做）
- [ ] A1 剔除赛狐前端入口 `src/app/(erp)/saihu-search-merge` 及导航引用
- [ ] A2 核对 C 类归档页无引用回归，确认 `views` 清单内页面无 404
- [ ] A3 `pnpm build` 通过

### B. 真实 IO 接入（wimoor-ai）
- [ ] B1 pom 加 `spring-boot-starter-jdbc` + `mysql-connector-java`（scope runtime）
- [ ] B2 新建 `db_ai` 建表 SQL：ai_conversation / ai_message / ai_run / ai_tool_call / ai_points_ledger / ai_research_run / ai_skill_run / ai_report_task
- [ ] B3 `JdbcConversationRepository implements ConversationRepository`（租户三元组隔离、revision 乐观锁、clientKey/requestKey 幂等、run 状态机）
- [ ] B4 `JdbcPointsLedger implements Billing.PointsLedger`（幂等扣分、影子/强制双模式）
- [ ] B5 `JdbcResearchStore / JdbcResearchRepository`（transaction + crash-safe checkpoint）
- [ ] B6 `JdbcSkillRunStore / JdbcReportTaskStore`（运行历史、报表任务状态）
- [ ] B7 `HttpLlmClient implements LlmClient`（JDK HttpClient，chat_completions / responses 两种 wireApi，apiKey 不出日志）
- [ ] B8 `JdkHttpFetcher implements LiliTransport.HttpFetcher`（带端点白名单、超时、响应体上限）
- [ ] B9 `@ConditionalOnProperty(ai.persistence=jdbc)` 配置类装配真实 bean；`application-jdbc.yml` 数据源
- [ ] B10 领域层编排接真实 bean：Orchestrator / Runner / ResearchEngine / ReportService

### C. API 层补齐写端点（AiApiController）
- [ ] C1 对话 CRUD + run 状态 + 取消（租户隔离，身份只从 session/header 取）
- [ ] C2 研究启动/恢复/查询
- [ ] C3 skill 运行 + 历史 + 导出
- [ ] C4 报表任务创建/轮询/下载

### D. UI 重建（wimoor-ui，挂"工具"分组）
- [ ] D1 AI 对话页（复用/改造 `sys/deepseek/index.vue`）
- [ ] D2 Lili 技能中心（skills/tools/workflows 目录）
- [ ] D3 选品研究向导（五阶段 + 人工门）
- [ ] D4 Skills 市场 + 运行历史
- [ ] D5 销量统计（Sellfox 核心视图，Element Plus 重建）

### E. 生产级验收循环
- [ ] E1 `verify-migration.sh all`：编译/200 测试/冒烟全绿
- [ ] E2 JDBC profile 集成测试（真实库 CRUD + 幂等 + 租户隔离）
- [ ] E3 前端 `pnpm build` + 菜单 12/39/160+B 类
- [ ] E4 API 写端点 HTTP 实测
- [ ] E5 多轮循环至全绿，产出验收报告，推送分支

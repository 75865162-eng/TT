# 生产级闭环收口 · 验收报告（2026-11-25）

> 分支：`migration/wimoor-slim-8gb`。基线：P0-P4 已全量落地（197 类 / 3 万行 / 200 测试全绿）。
> 本轮交付：三层口径落地、瘦身去赛狐、UI 重建（B 类）、真实 IO 接入（JDBC/HTTP）、生产级验收循环。

## 一、本轮改了什么

### 1. 瘦身（用户明确的"去掉不要的"）
- 移除 woker ERP 首页的赛狐搜索词合并入口（`src/app/(erp)/erp/page.tsx`）。
- Wimoor 页面保持 P1 已验证的自洽态（690 个 .vue）；审计确认表格 126 组件 100% 存在，未冒进删除（避免重蹈误删断链）。
- 赛狐数据模块（sellfox 销量统计）作为 B 类能力保留并迁入 Wimoor。

### 2. 真实 IO 接入（wimoor-ai，生产级闭环核心）
- `pom.xml` 加 `spring-boot-starter-jdbc` + mysql-connector（runtime）。
- `application.yml` 默认排除 DataSource 自动配置，`ai.persistence=memory` 保持低内存。
- 新增 `persistence/` 包：
  - `JdbcConversationRepository`：租户三元组隔离、revision 乐观锁、clientKey/requestKey 幂等、run 状态机、工具证据落库、回答 JSON 往返。
  - `JdbcPointsLedger`：积分幂等扣分/释放、影子/强制双模式、余额落库。
  - `JdbcResearchStore`：整 run JSON 落盘，transaction 先 save 后执行（crash-safe，不重放计费）。
  - `HttpLlmClient`：JDK HttpClient，chat_completions / responses 双协议，apiKey 只入 header。
  - `JdkHttpFetcher`：Lili/报表 transport 的真实 HTTP，端点白名单防 SSRF + 超时 + 响应体上限。
  - `JdbcAutoConfiguration`：`@ConditionalOnProperty(ai.persistence=jdbc)` 装配全部真实 bean。
- `Billing.LedgerEntry` 构造/setReleased 改 public（供跨包持久化构建）。
- 新增 `AiWriteController`：对话 CRUD / 消息准备 / run 状态/取消 / 积分 / 研究 run 写端点；memory 模式明确返回 503（诚实边界）。
- 建表脚本：`init-config/mysql/数据库结构/db_ai/ai_schema.sql`（9 张表）。

### 3. UI 重建（B 类，Vue3 + Element Plus）
- 新增 API 层 `api/sys/tool/aiApi.js`（对接 `/ai/api/v1/*`）。
- 新增 4 个页面（挂"工具"分组，`views/sys/ai/`）：技能中心 / 选品研究 / AI 运行历史 / 销量统计。
- `t_sys_menu.sql` 与 P0 `01-init.sql` 各追加 4 条菜单 + 管理员角色授权。
- 代理：`vite.config.js`（dev）+ `nginx.conf`（prod）加 `/ai/api` → 8102。

## 二、生产级验收循环（4 轮，全绿）

| 轮次 | 验证项 | 结果 |
|---|---|---|
| 1. 全量脚本 | `bash verify-migration.sh all`：编译 + 200 测试 + 冒烟 | ✅ RC=0；health UP（skills=116/tools=107/workflows=13）；29 测试类 errors=0 failures=0 |
| 2. 真实库集成 | `JdbcPersistenceIT` 连真实 MySQL（13307）跑 4 用例 | ✅ Tests run: 4, Failures: 0, Errors: 0, **Skipped: 0** |
| 3. 配置完整性 | 菜单 4 条（双脚本）、角色授权 4、页面 4、代理 2 处、API 层 | ✅ 全部存在 |
| 4. 诚实边界 | 默认 memory 模式 health 正常、写端点返回 503 | ✅ health UP / HTTP 503 |

### 真实库端到端 HTTP 实测（JDBC 生产模式）
- 建会话落库、requestKey 派生 `org:ws:user:conv:idem` 正确、clientKey 幂等（第二次 duplicate=true）。
- 研究 run 创建/读取字段完整。
- **跨租户隔离**：otherUser 看不到 orgE2E 的会话（返回 `[]`）。
- **重启持久化**：kill 进程重启后会话仍在 → 真实落库，非内存态。
- JDBC 模式启动：HikariPool 连库成功、health UP。

## 三、诚实边界（不夸大）
- 完整多轮 LLM 编排（工具发现→模型→证据回灌）仍需注入真实 MCP transport 与模型凭证；本轮提供了 `HttpLlmClient`/`JdkHttpFetcher` 真实实现与 `/llm/probe` 连通性探针，未用假数据冒充业务结果。
- 报表下载/发布、Skill 运行状态等更深端点沿用领域层接口，待真实凭证/存储接入后启用。
- 页面瘦身保持 P1 自洽态，未激进删除 22 个"表格外候选"（含 dashboard 首页经 url 字符串引用的页面），避免断链。

## 四、复现命令
```bash
cd wimoor && bash verify-migration.sh all          # 全量（内存态）
# 真实库集成（需 MySQL）
mvn -pl wimoor-ai test -Dtest=JdbcPersistenceIT \
  -Dai.it.url='jdbc:mysql://127.0.0.1:13307/db_ai?...' -Dai.it.user=root -Dai.it.password=xxx
# 生产模式启动
java -jar wimoor-ai/target/wimoor-ai-2.0.0.jar \
  --ai.persistence=jdbc --ai.datasource.url=jdbc:mysql://.../db_ai \
  --ai.datasource.username=root --ai.datasource.password=xxx
cd wimoor-ui && pnpm build
```

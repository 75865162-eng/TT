# AEC 后台数据定时拉取：部署与运维说明

> 目标：配好店铺授权后，系统按域自动定时拉取亚马逊数据并落地到对应页面，**数据直连亚马逊官方端点，不经任何第三方中转**。
> 依据：亚马逊官方《SP-API 新人配置指南》（用户提供的 2026 版）§8.3 / §11 限流 / 附录 B。

## 一、数据链路合规声明（重要）

审计确认，所有数据请求**直连亚马逊官方域名，不经 wimoor 或任何第三方中转**：

| 环节 | 实际端点 | 代码位置 |
|---|---|---|
| LWA token 换取 | `https://api.amazon.com/auth/o2/token` | `ApiBuildService.endpoint` |
| SP-API 业务数据 | `https://sellingpartnerapi-{na\|eu\|fe}.amazon.com`（硬编码） | `ApiBuildService.getEndPoint()` |
| 广告 API | `https://{t_amz_region.advpoint}`（NA/EU/FE 均为 `advertising-api*.amazon.com`） | 广告 `ApiBuildServiceImpl` |
| 代理 | `needproxy:false`，代理对象是死代码（创建未使用） | Nacos `wimoor-amazon` |

- 官方 §8.3：2023-10-02 起 SP-API 不再要求 AWS SigV4，SDK 只走 LWA 授权 → **AWS `accessKeyId/secretKey` 不参与拉数**，仅用于 SQS 通知（本方案不用 SQS，改用定时轮询下载兜底）。
- 调度工具 `SchedulerApiUtil` 只登录**本机网关 127.0.0.1:8080**（纯内网回环），不产生任何外部数据外发。

## 二、本次改动（3 个代码文件 + 2 个 SQL + 本文档）

| 文件 | 改动 |
|---|---|
| `common/service/impl/SchedulerApiUtil.java`（新增） | 调度统一 HTTP 工具：调用前自动登录本机网关拿 jsessionid，带 token 调端点；token 缓存 30 分钟；失败重试一次 |
| `common/service/impl/QuartzTaskFactory.java` | 裸 RestTemplate 调用改为走 `SchedulerApiUtil`（原来无登录态必然 403，定时任务全空跑）；顺带修正 parameter 判断反转的 bug |
| `sys/tool/controller/SysTaskController.java` | 页面"立即执行"按钮同样改走 `SchedulerApiUtil`，保持一致 |
| `mysql-init/07-seed-report-types-and-regions.sql`（新增） | 补种报告类型 36 条 + 广告区域 NA/EU/FE/TT（两库），幂等；广告区域端点为亚马逊官方域名，client_id/secret 留空待填 |
| `mysql-init/08-scheduler-tasks.sql`（新增） | 配置 21 个分层调度任务，默认暂停（isdelete=1），幂等 |

## 三、前置条件（必须由你完成，我无法臆造凭证）

1. **店铺授权**：在「店铺授权」页用真实 LWA clientId/clientSecret + refresh token 绑定每个店铺（`t_amazon_auth` 当前为 0）。
2. **广告授权**：广告走 `t_amz_adv_auth` 单独授权 + `captureProfile`（不走 SP-API 入口）。
3. **广告 LWA 凭证**：`t_amz_region.client_id/client_secret` 填真实值（07 SQL 已留空）。
4. **Nacos 全局 LWA**：`wimoor-amazon.auth.clientId/clientSecret` 需非空（有 `Assert.notBlank` 启动校验），每店手填优先、此为空时回退。

> 没有真实凭证时，调度任务会正常执行但拉不到数据（各域为空态），这是业务数据未录入，不是代码 bug。

## 四、频率分层（依据官方限流 + 数据时效）

| 域 | 频率 | 依据 |
|---|---|---|
| 订单 | 15 分钟 | `getOrders` v0 rate 0.0167/s（约 1 次/分）、burst 20 |
| 订单明细 | 20 分钟 | `getOrderItems` rate 0.5/s，比订单略疏、错峰 |
| 多渠道订单 | 30 分钟 | 低频域 |
| FBA 库存 | 1 小时 | 库存变化较慢 |
| Listing / 价格 / 销售计划 | 6 小时 | 商品主数据变化慢 |
| 产品类型 / 财务账期 / 结算 | 每天 | 报告按天/周生成 |
| 报告状态同步 | 15 分钟 | 配合报告发起 |
| **报告下载解析** | **15 分钟** | 报告生成需等待，高频轮询兜底（替代被 dev profile 关闭的 SQS） |
| 报告发起（订单/库存/结算） | 每天 | 关键 type；其余 type 可在页面按需手动发起 |
| 广告报告发起 / 结构快照 | 每天 | 广告报告 T+1 为主 |
| 广告报告解析 / 快照解析 | 30 分钟 | 报告生成等待 |
| 广告汇总 | 2 小时 | 聚合计算 |

所有任务在 `minute` 字段错峰（3/15、8/20、12/30…），避免同一时刻并发触发限流。429 由 SDK 内置限流处理（官方 §11.5 指数退避）。

## 五、启用步骤

1. 导入 `07-seed-report-types-and-regions.sql`、`08-scheduler-tasks.sql`（幂等，可重复执行）。
2. 重启 admin 服务，`SystemSchedulerInit` 会 `refreshTask()` 装载 `target=#` 且 `isdelete=0` 的任务。
3. 进入「系统任务」页面，核对 21 个 `AEC-SYNC-*` 任务（默认暂停）。
4. 逐域启用：先启用 1-2 个（如报告下载解析 + 订单），观察日志无 401/429、数据落库正常，再批量启用其余。
5. 用页面"立即执行"按钮可手动触发单个任务验证（已修复裸调 403 问题）。

## 六、验证与排障

- **任务是否加载**：系统任务页面 `listScheduler`，或查 `db_quartz.QRTZ_JOB_DETAILS`。
- **登录是否成功**：admin 日志搜 `[scheduler] login ok`。
- **调用是否成功**：admin 日志搜 `[scheduler] GET ... -> code=200`。
- **403**：检查 `SchedulerApiUtil.gateway/account/password` 配置（默认 `http://127.0.0.1:8080` / `1` / `1`）；可在 Nacos `wimoor-admin` 覆盖 `scheduler.auth.*`。
- **`No instances available for 127.0.0.1`**：`SchedulerApiUtil` 内部用普通 `RestTemplate`（非 `@LoadBalanced`），因为 `@LoadBalanced` 的 `restTemplateApi` 会把 IP 当服务名去 Nacos 解析而失败。这是修复点，勿改回 `restTemplateApi`。
- **429**：属正常限流，SDK 指数退避；如持续，调大对应域 cron 间隔。

## 七、诚实边界

- "定时拉取"能保证的是**按域自动调用官方 API 并落地**，不代表一定有数据——取决于是否绑定了真实店铺/广告授权、角色是否获批、报告是否生成完成。
- 刷新 token 有有效期（官方口径 365 天/长期不一），需在到期前重新授权。
- 报告类为异步两阶段：发起 → 等待亚马逊生成 → 下载解析，`processReport` 轮询兜底，但生成时效由亚马逊决定。
- 本方案未启用 SQS 通知（生产 profile=dev 会关闭消费者，且需真实 AWS 凭证），改用定时轮询，功能等价、零 AWS 依赖。

# Refresh-Token 模式全量拉取方案（不走 OAuth 交互授权）

> 目标：部署上线后，只配置应用的 LWA/IAM 凭证 + 店铺 refresh token，由系统自动换取 access token，按需/定时全量拉取 SP-API 与 ADS-API 数据。
> 结论先行：**refresh token 换 access token 在两套服务里都是代码原生、自动完成的，不需要浏览器 OAuth 交互**。但"配好就全量自动拉"还差两件必须补的事（见 §5），且"全量"在本系统里是"按已绑定店铺/授权逐域拉取"，不是无差别一次拉全历史。以下全部来自代码核实，未做的功能明确标注。

## 1. 为什么 refresh token 路线成立（代码证据）

| 环节 | SP-API（wimoor-amazon） | ADS-API（wimoor-amazon-adv） |
|---|---|---|
| refresh token 存储 | `t_amazon_auth.refresh_token`，店铺级 `client_id/client_secret` 同表 | `t_amz_adv_auth.refresh_token`，client 凭证在 `t_amz_region.client_id/client_secret` |
| 换 token 实现 | `ApiBuildService.getLWAAuthorizationCredentials()` 把 refresh token 交给官方 `amazon-sp-api` SDK，SDK 内部走 `https://api.amazon.com/auth/o2/token` 自动换 access token | `AmzAdvAuthServiceImpl.refreshToken()` 用 `grant_type=refresh_token` POST 同一端点换 access token |
| 自动刷新触发 | 每次构造 API 客户端时由 SDK 自动完成 | `AmzAdvAuth.getAccessToken()` 每次调用检测 `opttime` 超过 55 分钟即自动 `refreshToken()` 并落库 |
| 失效处理 | `AmazonAuthority.setApiStatus()` 识别 `invalid grant parameter : refresh_token` → 置 `status=invalid` | `refreshToken()` 遇 `invalid_grant` → 置 `disable=true`、`disableTime`，抛异常 |
| 限流 | `AmzAuthApiTimelimit` + `x-amzn-RateLimit-Limit` 解析，30 处调用 `getRateLimitPermit()` | `isBusy()` + `Too Many Requests`/`Server is busy` 退避，置忙 10 秒 |

**关键点**：这套代码本来就是"一次授权拿到 refresh token，之后长期用 refresh token 自动续期"的设计。OAuth 回调页（`authSeller`/`captureAmzAdvAuthByCode`）只是首次拿 refresh token 的一条路径，**不是运行时依赖**。因此你的 refresh token 路线与代码模型天然契合。

## 2. 需要你提供的凭证（一次性注入，不含 OAuth）

| 凭证 | 用途 | 落点 |
|---|---|---|
| LWA `client_id` / `client_secret` | 换 access token | SP：Nacos `wimoor-amazon.auth.clientId/clientSecret`；ADS：`db_amazon.t_amz_region.client_id/client_secret`（按区域） |
| AWS `accessKeyId` / `secretKey` / `region` / SQS ARN | SP-API 签名 + 通知 | Nacos `wimoor-amazon.aws.*` |
| `appid`（SP-API Application ID） | 请求头 | Nacos `wimoor-amazon.auth.appid` |
| 每个店铺 `sellerid` + `refresh_token` + `region` + `aws_region` | 逐店拉数主凭证 | `db_amazon.t_amazon_auth` |
| 每个广告授权 `groupid/shopid` + `refresh_token` + `region` | 逐广告账户拉数 | `db_amazon_adv.t_amz_adv_auth` |
| 广告区域 endpoint（`advpoint`，如 `advertising-api.amazon.com`） | 拼 API 域名 | `db_amazon.t_amz_region` / `db_amazon_adv.t_amz_region` |

> 安全：这些值不要写进 git。当前仓库 `t_amz_region.sql`、`wimoor-amazon` Nacos 里全是示例占位串（`EXAMPLE_PLACEHOLDER`、`ABCD1EFGHIJK...`），**必须替换**，否则服务启动即因 `Assert.notBlank(clientId/clientSecret)` 失败，或调 API 返回 401。

## 3. 配置与注入步骤（按顺序）

### 3.1 Nacos（服务启动前）
导入 `wimoor-amazon`、`wimoor-amazon-adv` 等配置（部署脚本已自动做端口本地化），然后把 §2 的 LWA/AWS/appid 真实值填入 `wimoor-amazon`。ADS 的 `auth.redirecturl` 由于不走 OAuth 可保留。

### 3.2 广告区域表补数据（当前初始化 SQL 缺数据）
`01-init.sql` 只建了 `t_amz_region` 表结构，没有 INSERT。原始数据在 `wimoor/init-config/mysql/数据/db_amazon_adv/t_amz_region.sql`（EU/FE/NA/TT 四行，但 client 是占位值）。需要在 `db_amazon` 和 `db_amazon_adv` 两库都补上真实区域行：
```sql
INSERT INTO t_amz_region (code,name,advname,advpoint,client_id,client_secret) VALUES
('NA','北美','North America','advertising-api.amazon.com','<真实clientId>','<真实clientSecret>'),
('EU','欧洲','Europe','advertising-api-eu.amazon.com','<真实clientId>','<真实clientSecret>'),
('FE','远东','Far East','advertising-api-fe.amazon.com','<真实clientId>','<真实clientSecret>');
```

### 3.3 注入店铺授权（关键：用 `saveAuth` 而非写裸 SQL）
`AmazonAuthorityController.saveAuth`（`POST /admin/api/v1/...`，实际前缀以网关路由为准）接收 `AmazonAuthority` 对象，**强制校验 sellerid/clientId/clientSecret/refreshToken 非空**，并要求 `groupid` 已存在对应店铺组（`t_amazon_group`）。保存后自动调 `refreshMarketByAuth` 拉取站点参与信息。
- 前置：先用店铺信息建 `t_amazon_group`（`groupid`/`shopid`/`name`）。
- 每个店铺调一次 `saveAuth`，body 含 sellerid、clientId、clientSecret、refresh_token、region、aws_region、groupid、proxy 配置（如走代理）。
- 效果：`refresh_token_time`/`opttime` 自动写入，`disable=false`、`status=null`。

### 3.4 注入广告授权 + 拉取 Profile
广告 `t_amz_adv_auth` 没有独立的"用 refresh token 直接入库"端点：`captureAmzAdvAuthByCode` 是 OAuth code 换 token 的路径。走 refresh token 路线时，需直接写 `t_amz_adv_auth`（字段：`id`、`groupid`、`shopid`、`code`、`region`、`refresh_token`、`token_type`、`creator`、`createtime`、`opttime`、`disable=0`），其中 `refresh_token` 来自你已持有的 token，`region` 对应 §3.2 的区域码。
- 之后调 `AdvertController.captureProfile`（`GET /amazonadv/api/v1/advert/captureProfile?groupid=&region=` 或 `captureProfileById?id=`），内部用 refresh token 换 access token 调 `/v2/profiles` 拉取广告 Profile 落 `t_amz_adv_profile`。这一步是广告全量拉取的前置（所有广告接口都要 profileId）。

## 4. 全量拉取入口（手动 HTTP 端点，已绑定全部授权）

代码里**没有**"一键全量定时"的开关，但有现成的批量拉取端点，内部 `executTask()` 会遍历所有 region 的所有授权逐个执行。按域调用即可实现"全量"：

| 数据域 | 服务 | 端点（经网关，宿主 8099） | 批量机制 |
|---|---|---|---|
| 订单 | amazon | `GET /amazon/api/v0/orders/refreshOrder`、`/refreshOrdersItem` | `executTask` 遍历全部 auth |
| 单店订单 | amazon | `GET /amazon/api/v0/orders/refreshOrderByAuth?authid=`、`/refreshOrdersItemByAuth` | 指定 auth |
| 库存/FBA | amazon | `GET /amazon/api/v0/inventry/syncInventorySupply`、`/taskInventoryData` | 遍历授权 |
| 产品报告(SP) | amazon | `GET /amazon/api/v1/report/product/kiosk/requestReport`、`/refresh` | 按授权 |
| 产品广告/费用 | amazon | `GET /amazon/api/v1/report/product/productInOpt/refreshAllProductAdv`、`/refreshAllProductFees` | 全量 |
| 财务结算 | amazon | `POST /amazon/api/v1/fin/settlementAccReport/getSettlementAccReport` | 按月/授权 |
| 广告结构+报告 | amazon-adv | `GET /amazonadv/api/v1/advschedule/requestReport`、`/requestProductAdsReport` | 遍历 profile/auth |
| 广告报告指定日期/店 | amazon-adv | `GET /amazonadv/api/v1/advschedule/requestReportByDate?authid=&start=&end=`、`/requestReportByType?profileid=&typeid=` | 指定范围 |
| 店铺/品牌/发票 | amazon-adv | `GET /amazonadv/api/v1/advschedule/refreshListPortfolios` | 全量 |
| 广告汇总/提醒 | amazon-adv | `GET /amazonadv/api/v1/advschedule/refreshRemaind` | 全量 |
| 广告 Profile | amazon-adv | `GET /amazonadv/api/v1/advert/captureProfile?groupid=&region=` | 逐授权 |

> 注意：报告类是**异步两阶段**（create report → 下载解析），`requestReport*` 只是发起；真正入库由后续下载回调完成。全量跑批需给足等待时间并观察日志。

## 5. 达到"配好即全量自动拉"还差的两件事（必须补）

1. **调度器**：代码里 SP 应用有 `@EnableScheduling`，但全局唯一 `@Scheduled` 方法在 admin 模块，amazon / amazon-adv **没有内置定时拉取任务**，全部拉取端点靠人工/外部触发。要实现"自动"，二选一：
   - a) 用admin 自带的 Quartz（`db_quartz` 表已建）注册 cron job 调上述端点；
   - b) 宿主机 crontab / 外部调度器定时 `curl` 这些端点（更简单，推荐先跑通）。
2. **"全量"范围策略**：现有批量入口是"所有已绑定店铺 × 单域"，首次历史回补要控制并发与日期跨度——SP 侧有真实限流（`t_amz_auth_api_timelimit`），ADS 侧有忙时退避。建议首次按域串行、按店铺分批，而非全部并发。

## 6. 落地清单（按顺序执行）

- [ ] 1. Nacos 填入真实 LWA/AWS/appid 凭证（替换占位串），重启验证服务正常启动（不再 `Assert.notBlank` 失败）
- [ ] 2. `db_amazon` / `db_amazon_adv` 补 `t_amz_region` 真实区域行
- [ ] 3. 建店铺组 `t_amazon_group`，逐店调 `saveAuth` 注入 SP refresh token
- [ ] 4. 写 `t_amz_adv_auth` 注入广告 refresh token，调 `captureProfile` 生成 Profile
- [ ] 5. 单店单域冒烟：`refreshOrderByAuth?authid=` 与 `requestReportByType?profileid=&typeid=` 验证数据落库
- [ ] 6. 配置 crontab / Quartz，按域依次调度 §4 端点，先串行后视限流放开
- [ ] 7. 验收：查各库表行数增长、日志无 401/`invalid_grant`、token 自动续期（opttime 更新）

## 7. 诚实的边界

- 以上"能"指的是**代码具备真实调用能力**，不代表已在真实 Amazon 账号验收过——本仓库此前仅做离线/浏览器验收，无任何真实账号数据入库证据。
- refresh token 有有效期与轮换规则（LWA refresh token 通常长期有效但可被撤销/轮换），失效后需重新获取并再次注入。
- "全量"受限于已绑定店铺、区域和 Amazon 官方限流/报告生成时效，不是无限速、无界历史回填。
- 广告 `t_amz_adv_auth` 直接写库为非标准路径（标准路径是 OAuth code 换 token），需自行保证 token 与 client 凭证、区域匹配，否则 401。

# Amazon SP-API / ADS-API 配置操作指引（上线即配即用）

> 面向使用者：拿到真实 Amazon 凭证后，按本文顺序填配置和页面，即可让系统自动拉取订单/广告数据。
> 全程**不走 OAuth 浏览器授权**，用 refresh token。所有字段名、端点均来自当前代码核实。
> 访问地址：`http://43.143.28.153:8080/`（需安全组放行 8080），账号 `admin@wimoor.com` / `123456`。

## 重要前提（先读）

1. 当前远程环境**没有任何真实凭证**：Nacos 的 clientId/Secret 是 `a123bcd...` 占位串，授权表全空。所以现在页面能打开、能保存，但**拉不回真实数据**——填上真实凭证才会真拉。
2. 配置有**严格的先后依赖**：先建店铺组 → 再配全局凭证（Nacos）→ 再到页面绑定每店授权 → 最后触发拉取。跳步会导致下拉选不到店铺或调 API 报 401。
3. 每改一次 Nacos 配置需要**重启对应服务**才生效（或走 Nacos 配置自动刷新，本项目数据源是启动时读取，建议重启）。

---

## 第 0 步：准备凭证清单

向 Amazon 收集以下值（在卖家后台 SP-API 应用与 LWA 页面获取，详见上传文档《亚马逊官方文档 SP-API新人配置指南.md》）：

| 凭证 | 来源 | 用途 |
|---|---|---|
| LWA `clientId` / `clientSecret` | 卖家后台 → SP-API 应用 → LWA 凭证 | 换 access token（SP 与 ADS 都用） |
| SP-API `appid`（Application ID） | SP-API 应用详情 | 请求头 |
| AWS `accessKeyId` / `secretKey` | IAM 用户（有 SP-API 权限） | 请求签名 |
| AWS `sqsArn` / `sqsPath`（可选） | SQS 队列 | 通知/报告 |
| 每店 `sellerid`（Seller ID / Merchant ID） | 卖家后台 → 账户信息 | 逐店拉数主键 |
| 每店 `refresh_token`（SP-API，通常 `Atnr\|` 开头） | LWA 授权后获得 | 逐店换 token |
| 每店广告 `refresh_token`（ADS，通常 `Atzr\|` 开头） | 广告 API 授权后获得 | 广告账户换 token |
| 店铺所属区域 | 判断：北美 NA / 欧洲 EU / 远东 FE | 决定 API 域名 |

---

## 第 1 步：建店铺组（必须最先做）

配置授权页的「店铺名称」下拉来自店铺组表，当前为空，不先建就没法选。

1. 登录系统 → 进入 **店铺授权** 页面（路径 `/amazon/storeAuth`）。
2. 点 **添加店铺**，填：
   - **店铺名称**：系统内自定义名（如 `US主店`），必填。
   - **海关DxpId** / **海关注册编号**：如无海关需求可留空。
   - 保存即可。是否财务账套/初始化财务账套按需开，非拉数必需。
3. **记住这个店铺组对应的区域**：北美=NA、欧洲=EU、远东=FE。

> 每要拉一个店铺，就先在这里建一个店铺组。

---

## 第 2 步：填 Nacos 全局凭证（SP 服务 + ADS 区域）

### 2.1 SP 服务全局凭证

进入 Nacos 控制台 `http://43.143.28.153:8848/nacos`（默认账号 nacos/nacos），找到配置 `wimoor-amazon`（DEFAULT_GROUP），把占位串替换为真实值：

```yaml
auth:
  accessKeyId: <真实 AWS AccessKeyId>
  secretKey: <真实 AWS SecretKey>
  clientId: amzn1.application-oa2-client.<真实LWA clientId后缀>
  clientSecret: <真实 LWA clientSecret>
  appid: amzn1.sellerapps.app.<真实 Application ID>
  sandbox: false          # 生产必须 false
  needproxy: false        # 不走代理就 false；走代理填 true 并另配代理
  redirecturl: https://<你的域名>/authresult   # 不走 OAuth 可保留
aws:
  region: us-west-2       # 按你的 AWS 区域
  accessKeyId: <同上 AWS AccessKeyId>
  secretKey: <同上 AWS SecretKey>
  sqsStandArn: <真实 SQS ARN，无则保留占位>
  sqsStandPath: <真实 SQS URL，无则保留占位>
```

发布后**重启 amazon 服务**。

### 2.2 ADS 区域表（广告必需）

广告 API 按区域取 client/端点，当前 `db_amazon_adv.t_amz_region` 是空的，必须先补区域行。连 MySQL（root/123456，端口 13306）执行：

```sql
INSERT INTO db_amazon_adv.t_amz_region (code,name,advname,advpoint,client_id,client_secret) VALUES
('NA','北美','North America','advertising-api.amazon.com','amzn1.application-oa2-client.<真实后缀>','<真实clientSecret>'),
('EU','欧洲','Europe','advertising-api-eu.amazon.com','amzn1.application-oa2-client.<真实后缀>','<真实clientSecret>'),
('FE','远东','Far East','advertising-api-fe.amazon.com','amzn1.application-oa2-client.<真实后缀>','<真实clientSecret>');
```

> 当前代码里 ADS 保存端点 `saveByRefreshToken` 会按 region 查这张表，查不到会报「区域 X 未在 t_amz_region 配置」——所以这步不能跳过。

---

## 第 3 步：页面绑定 SP 店铺授权（refresh token）

1. 进入 **店铺授权** 页面 → 选中刚才建的店铺 → 点 **添加授权/绑定授权**。
2. 弹出授权框后，把「类型」切到 **开发者模式**（`isdeveloper`）——只有切换后才会出现手动填 token 的字段。
3. 按真实值填：

   | 页面字段 | 填什么 |
   |---|---|
   | 店铺名称 | 第 1 步建的店铺组 |
   | 站点 | 按店铺区域选：北美=`us-east-1` / 欧洲=`eu-west-1` / 远东=`us-west-2` |
   | 类型 | **开发者模式** |
   | Seller Id | 真实 Seller ID |
   | Client Id | 真实 LWA clientId |
   | Client Secret | 真实 LWA clientSecret |
   | Refresh Token | 该店的 SP-API refresh token（`Atnr\|...`） |
   | Proxy Ip / Proxy Port | 不走代理则留空 |

   > 页面会自动按「站点」推导 region（us-east-1→NA 等），无需手填 region。

4. 保存。保存动作调 `saveAuth`，后端会写入授权并自动拉站点参与信息。
5. 重复 1-4，把每个店铺都绑一遍。

---

## 第 4 步：页面绑定 ADS 广告授权（refresh token）

1. 进入 **店铺授权** 页面 → 广告授权弹框（广告店铺授权入口）。
2. 找到分隔线 **「或：使用 Refresh Token 直接配置（无需登录亚马逊）」** 下面的表单，填：

   | 页面字段 | 填什么 |
   |---|---|
   | 店铺名称 | 第 1 步建的店铺组 |
   | 站点 | 北美=`NA` / 欧洲=`EU` / 远东=`FE` |
   | Refresh Token | 该店的广告 refresh token（`Atzr\|...`） |

3. 点 **「保存 Refresh Token」**。这一步后端会：落库 → 用 refresh token 换 access token → 自动抓广告 Profile。
   - 弹框内会显示「配置已保存：绑定成功」即成功。
   - 若报「区域 X 未在 t_amz_region 配置」→ 回第 2.2 步补区域行。
   - 若报 401/invalid_grant → token 与 client/区域不匹配，检查凭证来源。

---

## 第 5 步：触发拉取（当前为手动端点，无内置定时）

配好后不会自动定时拉，需手动调一次批量端点验证。用登录态 `jsessionid` 调（登录后从浏览器 localStorage 取，或直接浏览器访问带系统前缀的 URL）。常见端点：

| 数据域 | 端点（经网关 8080） | 说明 |
|---|---|---|
| 订单（全部授权） | `GET /amazon/api/v0/orders/refreshOrder` | 遍历所有已绑定店铺 |
| 订单（单店） | `GET /amazon/api/v0/orders/refreshOrderByAuth?authid=<授权id>` | 冒烟先测单店 |
| 库存/FBA | `GET /amazon/api/v0/inventry/syncInventorySupply` | |
| 广告报告 | `GET /amazonadv/api/v1/advschedule/requestReport` | 需先有 Profile |
| 广告 Profile | `GET /amazonadv/api/v1/advert/captureProfile?groupid=&region=` | 第 4 步已自动抓 |

> 报告类端点是**异步两阶段**（发起 → 后台下载解析入库），发起后需等待并观察日志/表数据。

---

## 第 6 步：验证是否真的拉到数据

连 MySQL 查表行数是否增长、日志有无 401：

```sql
-- SP 订单
SELECT COUNT(*) FROM db_amazon.t_amz_order_main;
SELECT COUNT(*) FROM db_amazon.t_amz_order_item;
-- 授权 token 是否在自动续期（opttime 更新）
SELECT sellerid, status, opttime, LENGTH(refresh_token) FROM db_amazon.t_amazon_auth;
-- 广告授权与 Profile
SELECT COUNT(*) FROM db_amazon_adv.t_amz_adv_auth;
SELECT COUNT(*) FROM db_amazon_adv.t_amz_adv_profile;
```

- 服务日志目录：`/tmp/wimoor-p0-logs/amazon.log`、`/tmp/wimoor-p0-logs/amazon-adv.log`。
- 若 `status` 变 `invalid` 或日志出现 `invalid_grant` → refresh token 已失效，需重新获取并在页面重填。

---

## 附：让拉取"自动定时"（可选，当前未配置）

系统 SP 应用有 `@EnableScheduling`，但 amazon / amazon-adv **没有内置定时拉取任务**，全靠手动/外部触发。两种方案：

- **简单**：在服务器 crontab 定时 `curl` 第 5 步端点（需带 jsessionid，或用带鉴权的内部调用）。
- **规范**：用系统自带 Quartz（`db_quartz` 表已建）注册 cron job 调这些端点。

首次建议**按域串行、按店分批**，避免触发 Amazon 限流（SP 有 `t_amz_auth_api_timelimit`，ADS 有忙时退避）。

---

## 配好即用的判定标准

- [ ] 店铺组已建，授权页下拉能选到店铺
- [ ] Nacos `wimoor-amazon` 已填真实凭证并重启服务，服务正常启动（不再 `clientId为空`）
- [ ] `t_amz_region`（adv）已补真实区域行
- [ ] 每个 SP 店铺已在开发者模式绑定 refresh token，保存成功
- [ ] 每个广告店铺已用 refresh token 直配成功，Profile 已抓到
- [ ] 手动触发订单/广告端点后，`t_amz_order_main` / 广告报告表行数增长，日志无 401/invalid_grant
- [ ] refresh token 到期前能自动续期（opttime 持续更新）

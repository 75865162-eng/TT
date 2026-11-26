# Wimoor 部署上线报告（43.143.28.153）

> 部署时间：2026-11-26　目标机：腾讯云 Ubuntu 24.04.4 LTS（4 核 / 7.5G 内存 / 52G 磁盘）
> 本报告只记录已实际验证的事实，未验证项明确标注。

## 一、部署结果（已完成）

| 项 | 状态 | 证据 |
|---|---|---|
| 基础设施容器 | 4 个全部 Up | mysql(healthy) / redis / nacos / nginx |
| Java 后端服务 | 10 个全部 Started | gateway/admin/erp/amazon/finance/data/amazon-adv/quote/gen/ai |
| 登录接口 | 200 | `POST /admin/api/v1/auth/login` 返回 200 |
| 前端登录页 | 200 | `GET /login` |
| 逐页验收 | **126/126 OK** | `outputs/page-acceptance-deployed.json`，无空白页、无 JS 错误、无跳登录、无首页兜底 |

## 二、部署路径与关键决策

1. **制品预编译上传**：本机已编译 26 个 jar + 前端 dist，远程不装 Maven/Node，直接上传解压（1.5G，字节数校验一致）。
2. **运行时**：JDK 11.0.33 + Docker 29.1.3 + Docker Compose v2.33.0；远程原缺 compose v2，从本机传二进制安装。
3. **镜像**：远程拉 Docker Hub 卡死（加速器不稳），改为本机 `docker save` mysql/nacos 镜像 → SFTP 上传 → `docker load`；nginx/redis 远程拉取成功。
4. **日志目录**：logback 写 `/logs/wimoor-*`，远程原无此目录导致服务启动即崩，已创建并授权。
5. **AI 服务端口冲突**：`wimoor-ai` 默认 8102 与 amazon 冲突，改用 `--server.port=8110` 启动，已 Started。

## 三、本次修复的代码问题

| 文件 | 问题 | 修复 |
|---|---|---|
| `wimoor-ui/src/views/finance/report/items/index.vue` | 无财务报表模板时 `templates[0].name` 空数组越界，产生 `Cannot read properties of undefined` JS 错误 | 改为 `templates.length > 0 ? templates[0].name : ''` |

修复后重新构建前端并部署，复测该页 OK，全量 126/126 通过。

## 四、遗留事项（需你确认/操作）

1. **公网访问被安全组拦截（关键）**：服务器系统防火墙未开启（ufw inactive / iptables ACCEPT），本机 `127.0.0.1:8080` 正常 200，但从公网访问 `http://43.143.28.153:8080` 超时。需在**腾讯云控制台安全组放行 TCP 8080**（如需直连网关再放行 8099）。放行后公网即可访问。
2. **SP-API / ADS-API 真实数据拉取**：本次部署只证明系统与页面可用。要真实拉取 Amazon 数据，仍需：替换 Nacos `wimoor-amazon`/`wimoor-amazon-adv` 中的占位凭证、按 `docs/refresh-token-full-pull.md` 注入 refresh token 与店铺授权、补 `t_amz_region` 区域数据、配置定时调度。当前无真实凭证，未做真实数据验收（这是 Amazon 授权要求，非系统缺陷）。
3. **API 配置保存页面**：SP 侧 `storeAuth` 已有手动填写 Seller Id / Client Id / Client Secret / Refresh Token 并保存的入口（`authstore.vue` 高级模式）；ADS 侧目前仅 OAuth 入口，缺手动填 refresh token 的表单——如需 ADS 也走 refresh token 直配，需后续补一个配置页字段。
4. **代码改动未提交推送**：财务报表修复 + 本部署报告 + `docs/refresh-token-full-pull.md` 目前在工作区，未 commit/push（按纪律需你明确授权）。

## 五、访问方式

- 前端入口：`http://43.143.28.153:8080/`（安全组放行 8080 后）
- 默认账号：`admin@wimoor.com` / `123456`
- 网关直连：`8099`；各后端服务：`8100-8107`、`8110`(ai)
- 基础设施端口：MySQL `13306`、Redis `16379`、Nacos `8848`

## 六、重启方式

```bash
cd /home/ubuntu/tt
bash deploy/wimoor-p0/start-all.sh
# ai 服务需单独用 8110 端口（避免与 amazon 的 8102 冲突）
```

## 七、四项诉求复核（2026-11-26 晚，重启后新鲜验证）

| 诉求 | 结论 | 证据 / 边界 |
|---|---|---|
| ① SP-API 能拉真实数据回来 | **代码链路真实可用，但当前环境无真实凭证，未做真实数据验收** | 源码核实为官方 `amazon-sp-api` SDK：`ApiBuildService` 用 `LWAAuthorizationCredentials.builder().refreshToken(...)` 换 token，订单链路 Controller→`executTask`→`runApi`→`getOrdersV0Api`→`getOrdersAsync`→`ApiClient.httpClient.newCall` 全通、无 stub；远程 `curl sellingpartnerapi-na.amazon.com` = 200（网络可达）。但 `db_amazon.t_amazon_auth` / `t_amazon_group` / `t_amz_region` / `t_amz_adv_auth` **均为 0 行**，Nacos clientId/Secret 为 `a123bcd...` 占位串——无任何真实 Amazon 凭证，故无法拉回真实订单。这是 Amazon 授权前提，非系统缺陷。 |
| ② 每页不空白 | **126/126 通过（重启后复验）** | `outputs/page-acceptance-reverify.json`：`{"OK":126}`，无空白、无跳登录、无首页兜底、无致命 JS 错误。新增测试授权已删除，环境无残留。 |
| ③ 有页面可配置保存 API | **已完成并部署** | SP：`authstore.vue` 开发者模式填 sellerid/clientId/clientSecret/refreshToken 保存；ADS：`authadv.vue`「Refresh Token 直接配置」表单 + 后端 `POST /amazonadv/api/v1/advert/saveByRefreshToken`（落库→换 token→抓 profile 全链路）。打包产物 JS 已确认含该端点。 |
| ④ 完善到上线即配即用 | **代码侧就绪；剩余两项需真实凭证/网页配置，无法代做** | 构建链路（前端 dist + 10 jar + 部署包）在 main 上实测 success。阻塞：(a) CI deploy 需 4 个 GitHub Secrets（当前 fine-grained token 无 Administration 权限，API 403，须网页配）；(b) 真实 Amazon 凭证需你提供并在配置页填入。详见 `docs/CI-CD-SETUP.md` 与 `docs/refresh-token-full-pull.md`。 |

### 本轮修复（工作区，未提交）
- `scripts/page-acceptance.mjs`：会话仍有效时访问 `/login` 被重定向到 `/home` 导致 `locator.fill` 超时——改为检测到非登录页即复用现有 session，不强制登录。修复前第 14 页崩溃，修复后 126/126。
- `.github/workflows/build.yml`：部署后探活登录参数 `username` → `account`（与 `LoginController.loginAction` 实际取值一致），避免探活误判登录失败。

### 需要你决策的两个阻塞项
1. **GitHub Secrets**（CI 自动部署必需）：网页打开 `https://github.com/75865162-eng/tt/settings/secrets/actions` 配 `SERVER_HOST=43.143.28.153`、`SERVER_USER=ubuntu`、`SERVER_PASSWORD=<密码>`、`SERVER_PORT=22`。当前 token 权限不足无法代配。
2. **真实 Amazon 凭证**（真实拉数必需）：LWA clientId/Secret、AWS AK/SK、appid（填 Nacos `wimoor-amazon`），每店 sellerid+refresh_token+region（配置页或 `saveAuth`），广告 refresh_token（`authadv.vue` 直配），`t_amz_region` 区域行。拿到后按 `docs/refresh-token-full-pull.md` §6 清单逐项注入并冒烟。

---

## 八、生产级闭环收口（2026-11-26 深夜，报错修复 + 逐页复验）

### 触发问题
用户反馈：点击部分页面报 SQL 错误 `Unknown column 't.groupid' in 'on clause'`，部分页面被强制退回登录页，要求逐页检查并生产级闭环。

### 根因与修复（逐项均已生产验证）

| # | 问题 | 根因 | 修复 | 生产验证 |
|---|---|---|---|---|
| 1 | 采购请款单列表 SQL 报错 | `PurchaseFinanceFormMapper.xml` 引用 `t.groupid`，但 `t_erp_purchase_fin_form` 表无此列（官方 schema 矛盾）；groupid 实际在关联采购单 `t_erp_purchase_form` 上 | GitHub main 上已有等价修复（远端提交 `053ce84` 给表加 `groupid` 列 + 幂等脚本 `03-fix`）；本轮生产 erp-boot.jar 采用改 SQL 方案（3 处 `t.groupid` → `f.groupid`），两套方案不冲突，生产库已加列 | 数据库直验：修复前 `ERROR 1054 Unknown column t.groupid`，修复后正常；接口 `POST /erp/api/v1/purchase_finance_form/list` 返回 `code:200 一切ok`。**erp 服务由 systemd 托管，须 `systemctl restart wimoor-erp`（直接 kill 会被自动拉起）** |
| 2 | 点击页面跳登录 | 验收/登录仍用旧账号 `admin@wimoor.com/123456`，该账号已改造为超级账号 `1/1` | 验收脚本账号改为环境变量 `ACCEPT_ACCOUNT/ACCEPT_PASSWORD`（默认 `1/1`）；新增 `--base` 支持指向生产 | 用 `1/1` 登录后 126 页全程无 `REDIRECT_LOGIN`，session 有效、菜单正常 |
| 3 | 账户/财务报表报错 | `t_erp_fin_account` 表缺 `findex` 列（实体 `@TableField` + Mapper `order by t.findex` 引用） | 新增幂等 DDL `04-fix-schema-gaps.sql` 补列 | 生产已执行，`getAccountAll` 返回 200；修复前 `Unknown column 'findex'` |
| 4 | AI 问答页报错 | `db_admin.t_sys_help_doc` 表不存在 | 同一 DDL 脚本补建表 | 生产已执行，`help-doc/get-by-path` 返回 200 |
| 5 | 系统角色/菜单管理 400 | `SysMenuServiceImpl` 菜单树递归 `menu.getParentId().equals(parentId)` 在 parentId 为 null 时 NPE（3 处） | 改 `java.util.Objects.equals(...)` null 安全，重新编译 admin-boot.jar 部署 | `menus/table`、`menus/companytree` 返回 200，菜单树正常 |

### 逐页验收数据（真实浏览器 + 账号 1/1 + 接口级错误捕获）

验收脚本 `scripts/page-acceptance.mjs` 本轮增强：新增 API 响应监听，捕获 4xx/5xx 与响应体中的 SQL/运行时异常（此前脚本仅看页面路由和正文长度，检测不到以 toast 呈现的接口报错，导致假阳性 PASS）。

| 阶段 | 结果 | 说明 |
|---|---|---|
| 基线（增强脚本，修复前） | OK 117 / API_ERROR 9 | 暴露 9 个接口级错误，含上述 findex×2、help_doc、菜单 NPE×2 |
| 应用 DDL 后 | OK 119 / API_ERROR 7 | findex×2、help_doc 消除 |
| 部署 admin(NPE修复) 后 | **OK 121 / API_ERROR 5** | 菜单 NPE×2 消除 |

最终数据见 `outputs/page-acceptance-prod-closed.json`。

### 最终结论：生产级闭环达成
- **126/126 页面全部正常渲染**：无空白页、无跳登录、无首页兜底、无致命 JS 错误。
- 用户报告的两个核心问题（SQL 报错、跳登录）均已修复并生产验证。
- 额外发现并修复 3 类确定性 schema/代码矛盾（findex、help_doc、菜单 NPE）。

### 剩余 5 项接口级提示（非致命，不影响页面主体，如实记录）
| 页面 | 错误 | 性质 |
|---|---|---|
| `/amazon/a/rules` | 404 `budgetRules/list//sp` | 前端路径拼接双斜杠，页面正常 |
| `/amazon/manager` | 400 搜索历史"无法获取表名" | 广告服务 tk.mybatis/MP ORM 混用历史遗留，需单独评估，未冒险改动 |
| `/erp/shipment/quote` | 400 `listall/undefined` | 前端 undefined 参数，页面正常 |
| `/fin/ledger/balance` | 400 "必填参数为空" | 会计期间未配置（业务初始化数据） |
| `/fin/voucher/create` | 400 "必填参数为空" | 会计期间未配置（业务初始化数据） |

### 本轮变更与部署清单
- 代码（已提交推送 main `046e8a2a`）：`SysMenuServiceImpl.java`（菜单 NPE null 安全）、`04-fix-schema-gaps.sql`（findex 列 + help_doc 表幂等迁移）、`scripts/page-acceptance.mjs`（账号环境变量化 + `--base` + 接口级错误捕获）。
- 采购请款单 `t.groupid` 的修复 main 上已由远端提交 `053ce84`（加列方案）覆盖，本轮未重复提交 mapper；生产 erp-boot.jar 为改 SQL 方案（`f.groupid`），功能已验证，与加列方案不冲突。
- 生产部署：erp-boot.jar、admin-boot.jar 重新编译替换并 `systemctl restart`；amazon-adv-boot.jar 曾部署但 `@TableName` 修复对 tk.mybatis 无效已撤销并重启回等价状态。
- 数据库：`04-fix-schema-gaps.sql` 幂等迁移已应用（findex 列 + help_doc 表）；groupid 列为远端 `03-fix` 脚本此前已应用。

---

## 九、五维深度逐页验收收口（2026-11-26 深夜，零报错达标）

### 验收标准升级
用户要求：每个页面可正常打开、数据正常落库、打开数据能正常显示、页面引用函数正常、不能报错、能正常使用。为此新增深度验收脚本 `scripts/page-acceptance-deep.mjs`，在路由检测基础上增加五维检测：
1. 页面打开（路由命中、非跳登录/首页兜底/空白/404）
2. 数据渲染（表格行数/明确空状态"暂无数据"，区分空库正常态与渲染失败）
3. JS 函数（捕获 pageerror/TypeError/is not a function）
4. 接口（捕获全部 4xx/5xx + 200 响应体 SQL 异常）
5. 可用性（DOM 可交互元素计数）

### 首轮深度验收基线（126 页）：120 OK + 5 API_ERROR + 1 FATAL_JS_ERROR
| 页面 | 错误 | 根因 |
|---|---|---|
| `/amazon/a/rules` | 404 `list//sp` | 未选广告档案（profileid 空）时前端仍拼接空路径发请求 |
| `/erp/shipment/quote` | 400 `listall/undefined` | 未选货代（token 空）时仍发请求 |
| `/fin/ledger/balance` | 400 必填参数为空 | 超管账号未绑集团，groupid 空仍发 summary 请求 |
| `/fin/voucher/create` | 400 必填参数为空 | 同上，getCurrentPeriod 无 groupId |
| `/amazon/manager` | 400 无法获取表名 | 广告服务 ORM 混用：org.mybatis MapperScan 导致 tk.mybatis EntityHelper 未初始化 |
| `/erp/purchase/paymentDetail` | FATAL JS: setting 'projectid' | `state.queryParam`（单数）拼写错误，state 实为 `queryParams` |

### 修复（逐项生产验证通过）
| # | 修复 | 文件 | 验证 |
|---|---|---|---|
| 1 | 未选广告档案时 API 层返回空分页，不发无效请求 | `advBudgetRuleApi.js` | `/amazon/a/rules` → OK，接口 11/11 |
| 2 | 未选货代时 API 层返回空数组 | `transchannelApi.js` | `/erp/shipment/quote` → OK，接口 11/11 |
| 3 | 财务 summary 加 groupid+period 空值守卫 | `balance/index.vue` | `/fin/ledger/balance` → OK，接口 15/15 |
| 4 | 会计期间加载加 groupid 空值守卫 | `vouchers/create/index.vue` | `/fin/voucher/create` → OK，接口 20/20 |
| 5 | JS 拼写 queryParam→queryParams（2 处） | `purchase/report/payment/index.vue` | `/erp/purchase/paymentDetail` → OK，FATAL_JS 消除 |
| 6 | 新增 `TkEntityNameInitConfig`：ApplicationRunner 启动后注册 JPA @Table 实体表名 + 用 tk MapperHelper.processConfiguration 处理通用 Mapper 的 SqlProvider（不改 MapperScan，避免破坏启动） | `TkEntityNameInitConfig.java`（新增） | `searchHistory` 400→200；广告服务启动 129s（processConfiguration 耗时，可接受） |

> 说明：广告服务问题是"tk.mybatis（134 个 @Table 实体走 Example）+ MyBatis-Plus（9 个 @TableName 实体）混用"的历史架构遗留。之前为修启动错误把 MapperScan 从 tk 换成 org.mybatis，导致 tk 实体表名/provider 失效。本方案不回退 MapperScan，而是在启动后补做 tk 的实体注册与 provider 处理，两者共存。曾尝试仅 @TableName 无效、仅 EntityHelper 不够（报 ExampleProvider 无参构造异常），最终 EntityHelper+MapperHelper 组合修复成功。

### 数据落库闭环验证（真实写入，可逆，无造假）
用系统备忘录 `t_sys_tool_notepad`（MyBatis-Plus 表）做完整写入链路验证：
- SAVE → 200，DB 确认记录落库（id/title/operator 正确）
- LIST → 200，标记内容正确回显（数据能显示）
- REMOVE → 200，DB 确认记录清零（可逆，无残留）

证明"数据正常落库 + 页面能显示"链路通畅。生产库当前为全新空库（业务表 0 行），各页面数据显示为 0 或"暂无数据"是空库正确态，非故障。

### 最终验收结果：126/126 全通过，零报错
| 指标 | 结果 |
|---|---|
| 页面 OK | 126/126 |
| 空白页 / 无交互元素页 | 0 / 0 |
| 接口调用总数 / 失败 | 1670 / 0 |
| API_ERROR / FATAL_JS_ERROR | 0 / 0 |
| 有数据行的页面 | 72（其余为明确空状态） |

结果文件：`outputs/page-acceptance-deep-final.json`。

### 本轮变更与部署
- 代码（提交推送 main `ebb3ca9b`）：6 个前端修复 + 广告服务 `TkEntityNameInitConfig.java` + 深度验收脚本。
- 生产部署：新前端 dist 全量覆盖（nginx bind mount 目录）；amazon-adv-boot.jar 重编译替换并 `systemctl restart`，接口验证通过。
- 10 个服务全部 active，网关 8080=200，超级账号 1/1 可用。

---

## 页面中文乱码修复（2026-11-26）

### 现象
角色权限（`/sys/ruleAuth`）、管理员号（`/sys/subuser`）、数据字典（`/sys/dict`）等页面中文显示为 `ä¸“ä¸šç‰ˆ`、`é‡‡è´­å¸ç§`、`å¯ç”¨` 等乱码；页面框架/表头/菜单树中文正常，仅**数据字段**乱码。

### 根因
SQL 源文件是干净 UTF-8（校验 0 乱码），但初始化导入时数据库连接字符集按 Latin1/CP1252 解读 UTF-8 字节后再以 UTF-8 写库，形成**双重编码**。
- 证据：`专业版` 正确字节 `E4B893E4B89AE78988`，库中实际为 `C3A4C2B8E2809CC3A4C2B8C5A1C3A7E280B0CB86`。
- 其中 `0x93→"`、`0x9A→š`、`0x88→ˆ` 为 CP1252 特殊映射，`0x81` 等未定义字节按 Latin1 直映射（混合编码）。

### 排查
- 全库扫描 8 个业务库 3261 个字符列（宽 Latin-1 pattern），命中乱码集中在 `db_admin` 6 个字段：
  `t_role.name`(5) / `t_sys_dict.name`(16) / `t_sys_dict_item.name`(39) / `t_sys_agent_learning.remark`(4) / `t_sys_tool_agent_context.context_key`(9) / `t_sys_tool_agent_context.remark`(3)，共 76 行。
- 前端源码 0 真乱码；dist vendor 命中的是压缩三方库误报。

### 修复
- 用完整 CP1252 反转映射表（含 `0x81/8D/8F/90/9D` 直映射兜底）本地确定性还原 76 行，全部校验通过（0 异常）。
- 备份原值到 `outputs/mojibake-backup-result.txt`（可回滚），执行 `HEX(col)=原值` 幂等 UPDATE 精确修复。
- 清 Redis 缓存 `dict:user_status:0/1/2`（缓存了旧乱码字典值，删除后自动重建为正确值）。
- 修复 SQL 固化为幂等迁移 `deploy/wimoor-p0/mysql-init/05-fix-mojibake.sql`，已验证重复执行安全。

### 验证
- 数据库：宽 pattern 全库复扫乱码 0；6 字段内容中文正常（标准版/专业版/性别/授权方式/性别/采购币种/个人买家/人民币…）。
- 页面：6 个系统页 DOM mojibake 计数全 0，截图视觉确认中文正常（角色列表、状态"启用/停用/过期"、字典名等）。
- 全量回归：`outputs/page-acceptance-after-mojibake-fix.json`，126/126 全 OK，无接口/JS 错误。

### 防复发
重建库时务必用 `--default-character-set=utf8mb4`（或 JDBC `characterEncoding=utf8&useUnicode=true`）连接导入；迁移脚本 `05-fix-mojibake.sql` 会在 init 阶段幂等兜底修复。

---

## 登录会话频繁掉线修复（2026-11-26）

### 现象
登录后用着用着几十秒就退出、跳登录页；多标签页/多设备尤其明显。

### 根因（决定性实验确认）
`LoginController.setSession()` 存在**单点登录互踢**逻辑：
```java
// accountkey = account + "{" + apptype + "}"  =>  "1{web}"
if (accountkey 有旧值 && 旧值 != 新 session) {
    redis.delete(旧 session);   // 后一次登录踢掉前一次
}
```
实验：账号1登录 A（正常）→ 再登录 B → A 立刻 403/401、跳登录页、sid 清空；单标签静置 141 秒不掉线。证据锁定为第二次登录踢出第一次。当前系统只有超级账号 `1`，多标签/多设备/SSO 静默重登都会互踢，表现为"几十秒掉线"。

加剧因素：Nacos `config.cas-server=https://sso.wimoor.com`（官方 SaaS 域名，私有部署无法解析，日志 75+ 次 UnknownHostException）、`config.authority-server=http://localhost:8087/auth`（无效）。掉线后 CAS 静默恢复失败，只能手动重登，且每次探测产生 3s 超时异常。

### 修复
1. **代码**：`LoginController.setSession()` 去掉删除旧 session 的互踢逻辑。各 token 以自身 sessionkey 为 key 独立存放/过期，互不干扰；`accountkey` 仅保留为"最新会话"索引，供在线用户/强制下线使用（已核对 `SysUserOnlineController` 不依赖互踢）。
2. **Nacos 配置**：`config.cas-server` 置空（关闭 CAS SSO）、`config.websit/ui-server/authority-server` 改为本机网关 `http://43.143.28.153:8080`。通过 Nacos API PUT 更新并回读确认。
3. **部署**：重新编译 admin-boot.jar（mvn -pl wimoor-admin/admin-boot -am package），备份旧 jar 为 `.bak-pre-multilogin` 后上传替换，`systemctl restart wimoor-admin.service`。

### 验证
- 服务 17.66s 启动正常，无异常。
- `ssologinisrun` 返回 `{"code":200,"data":"false"}`（走本地登录），调用前后 sso.wimoor.com UnknownHost 计数 133→133，**零新增**。
- 决定性实验复跑：A/B 两次登录均 200，A 不再被踢，A 页面停留 sid 保持。
- 稳定性探针：登录停留 122 秒，每 15s 探测 route 全 200，sid 不变，**零掉线、零 4xx**（修复前 77s 即 403）。
- 全量页面回归：`outputs/page-acceptance-after-session-fix.json`。

---

## 主动代码审计：同类隐患提前排查（2026-11-27）

### 为什么之前没提早发现
前两轮验收是"正向功能验证"（登录一次、串行点 126 页、看报错），天然覆盖不到三类盲区：
1. **状态/环境路径**：乱码只在"空库按字符集导入"时暴露，验收跑在已初始化好的库上。
2. **并发/多会话**：掉线只在"第二次登录"时暴露，验收是单会话串行。
3. **配置有效性**：只测"能打开"，不测配置指向是否真实存在、失效后能否恢复。
结论：缺的是**部署前的边界/并发/配置审计 + 可复用预检脚本**，不是多测几页。

### 审计方法
四个方向并行只读审计（编码/会话并发/配置有效性/数据契约ORM），逐条核对真实代码，区分客观 bug 与误报/需决策项。

### 已修（客观 bug，低风险防御性改动）
| 类别 | 文件:行 | 问题 | 修法 |
|---|---|---|---|
| 编码 | `wimoor-data:6,11` | `useUnicode=true@characterEncoding` 分隔符写错致编码参数丢失；slave 硬编码 `127.0.0.1:3306`（生产 MySQL 在 13306） | `@`→`&`，slave 改 `${mysql.host}:${mysql.port}` 占位符 |
| 会话 | `SystemLogoutFilter:23` | `delete(jsessionid)` 漏 `login_tokens:` 前缀，登出后 token 仍有效可重放 | 加 `CacheConstants.LOGIN_TOKEN_KEY` 前缀 |
| 并发 | `common-mvc/UserInfoFilter`、`amazon-adv/UserInfoFilter` | `UserInfoContext.set` 后无 `remove`，线程池复用串号/越权 | `UserInfoContext` 加 `remove()`，Filter 用 try/finally 清理 |
| NPE | `SecurityGlobalFilter:80` | `getUsertype().equals()` usertype 为 null 时 500 | 改 `"admin".equals(...)` |
| NPE | `UserController:497` | `findFirst().get()` 无匹配抛 500 | 改 `orElse(null)`+判空返回 |
| NPE | `SysMenuServiceImpl:199,200,508`、`SmsCodeGenerateController:29`、`SalesSummaryController:88` | 同类 `getUsertype().equals()` | 改常量 `.equals(变量)` |
| NPE | `ResearchBearerMcp:202`、`QuoteOrderServiceImpl:441` | `findFirst().get()` 无兜底 | 改 `orElse(null)`+判空 |

### 需决策（未擅改）
- **回调假域名**：`wimoor-amazon:9` `mywebsit.com/authresult`、`wimoor-amazon-adv:6` `app.wimoor.com/advresult`、`wimoor-commom-ext:2` `mywebsit.com`（图片服务）——需真实公网回调地址/图片反代路径，且 Amazon 回调需在开发者后台登记，不能盲改。
- **弱口令/占位凭证**：MySQL/Redis `123456`、DeepSeek `sk-EXAMPLE_PLACEHOLDER`、区域表广告 appKey 占位符——改强密码/填真实凭证属运维与产品决策。
- **StockTakingServiceImpl:258/464** 空 catch 吞 `BizException`（静默跳过错误行）——可能是"容错继续导入"的业务设计，改动会改变导入行为。
- **LoginController.verifyWechatApp:366** 删 oldjsessionid——第三方授权"换绑"语义，非普通登录互踢，保留。
- **TTL 不一致**：token 写 1h、网关续期 3h、账号索引 1h——改动影响在线时长语义。

### 防复发机制
新增 `scripts/preflight-audit.sh`（部署前自检）：静态扫描以上 6 类模式（编码分隔符、ThreadLocal 清理、登出前缀、登录互踢、无效域名、常见 NPE），FAIL 阻断、WARN 人工确认。当前跑结果：4 个硬 bug 全 OK、NPE 清零、仅剩需决策假配置 WARN。今后每次部署前先跑此脚本。

### 部署后回归发现（审计价值印证）
部署后全量验收出现 1 处 400：`/amazon/a/record` → `getNameMap` NPE。根因：Feign 内部调用 `getNameMap()` 时 `UserInfoContext.get()` 为 null（无上下文头），`userInfo.getCompanyid()` NPE。**这个 bug 一直存在，之前被 ThreadLocal 未清理的脏数据意外兜住**——修了串号后暴露。这正说明本次"并发/上下文"审计方向的必要性。
修复：`UserController.getNameMap/getIDMap` 加 `userInfo==null||companyid==null` 空守卫返回空 map（账号1下本就无用户数据，功能零降级）。预检脚本新增第 7 项：Feign 接口 UserInfoContext 空守卫检测。

---

## 第二轮深度审计：上下文 NPE / 集合越界全面排查（2026-11-27）

### 背景
首轮审计修复后，用户要求"不要等部署好了才发现"，遂启动第二轮更深、更广的只读审计：四路并行（空上下文 NPE / ThreadLocal 会话 / 编码配置外链 / 集合越界与异常吞噬），逐条核对真实代码，只修客观 bug，产品决策项保留。

### 关键根因
`UserInfoContext.get()` 底层是 `ThreadLocal.get()`。凡从**定时任务（Quartz 走 HTTP）、内部 Feign 调用链、无登录态接口**进入的端点，调用方自身无上下文，头里 `X-USERINFO` 为空 → 被调方解析出 `user==null`。首轮只修了 `getNameMap/getIDMap`，同类端点仍大量裸取。这与首轮 `/amazon/a/record` NPE 是**完全同一类 bug**。

### 已修（17 个文件，全部防御性空守卫/长度守卫，不改业务逻辑）
| 类别 | 文件 | 问题 | 修法 |
|---|---|---|---|
| 空上下文NPE | `LedgerFeignController`（13处） | 台账 Feign 端点裸取 `userinfo.getCompanyid()`，财务夜间凭证定时任务经 Feign 进入无上下文 → 500 | 统一 `putShopid(params)` 守卫 + 3 处直取点判空返回 failed |
| 空上下文NPE | `UserController` getEnable/findUserByOpenid/bindUserByOpenid | 与已修 getNameMap 紧邻的 Feign 端点同类裸取 | 判空返回空集合/失败 |
| 空上下文NPE | `FinClosingTemplateController`（11处） | Excel 模板导入内联 `UserInfoContext.get().getUserName()` | 抽出 `currentUserName()` 守卫助手，11 处统一替换 |
| 空上下文NPE | `FinVouchersServiceImpl:290` | 已取 user 却复用内联链式取 userName | 改用守卫后的 `operatorName` |
| 空上下文NPE | `AdvertManagerController:123` | 汇总端点经 Quartz 无上下文 | 判空返回空 map |
| 空上下文NPE | `CustomerController:256` | `beforeDelete` public 方法链式取 companyid | 先取局部变量判空 |
| 实体NPE | `SendEmailController:44` | `getById` 返回 null 直接 `.getBossEmail()` | 判空返回失败 |
| 实体NPE | `SysTagsController:202` | 标签已删除时 `tagItem.getName()` | 判空 continue |
| 集合越界 | `AdvertPortfoliosController:52` | `selectByExample().get(0)` 空结果越界 | 判空返回 null |
| 集合越界 | `ReportAmzProductRecommendedListServiceImpl:71,100` | 报表行 `split(",\"")[1/2]` 外部行无分隔符即崩 | 加 `parts.length<3` 守卫（非欧/欧两分支） |
| 集合越界 | `MerchantShippingGroupController:49,55`、`AmzProductListingController:131` | 请求体 `marketplaceids.get(0)` 空列表越界 | 判空返回失败/抛业务异常 |
| 拆箱NPE | `ErpDispatchOverseaFormController:153` | `statusList.get(0)` 元素为 null 拆箱崩 | 加 `get(0)!=null` |
| 集合越界 | `QuoteOrderServiceImpl:940` | `get("supplier").toString()` supplier 为 null | 改 null→空串 |
| 集合越界 | `SysDeepSeekServiceImpl:139,652` | AI 返回 `getChoices().get(0)` 空 choices 崩 | 加 choices 判空 |
| split越界 | `PictureServiceImpl:235`、`LargeFileServiceImpl:151` | 文件名无扩展名 `split(".")[1]` 崩 | 改 `length>1?ext:""` |

### 保留（产品/业务决策，未擅改）
- `mywebsit.com`/`app.wimoor.com` OAuth 回调假域名：用户走 refresh token 模式不走 OAuth，且需在开发者后台登记真实地址，盲改会破坏对接。
- 明文弱口令/占位密钥（MySQL/Redis 123456、邮件/短信/OSS 占位 secret）：更换属运维凭证决策。
- `wimoor-data` `serverTimezone=UTC` 与其他服务 `Asia/Shanghai` 不一致：改动影响时间语义，且当前日期显示正常，保留待评估。
- `DataSourceAspect` @Before/@After 嵌套调用清上下文：data 模块的 `@DataSource` 方法均为直接 JDBC 叶调用，无嵌套 service 触发路径，当前不会触发；改 @Around 属行为变更，保留。
- `verifyWechatApp` 删 oldjsessionid：换绑语义，非普通登录互踢，保留。

### 防复发机制升级
`scripts/preflight-audit.sh` 从 7 类扩到 10 类：新增 [8] Feign 台账端点守卫、[9] 内联上下文链式调用、[10] split 固定下标长度守卫。当前自检：全部 OK，仅 OAuth 假域名 WARN（refresh-token 模式下非生效路径）。

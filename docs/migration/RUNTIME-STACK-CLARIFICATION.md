# 运行栈澄清：Wimoor(Java+MySQL) 为唯一生产栈

> 记录时间：2026-11-26
> 背景：以 Wimoor/WMS 为标准的迁移完成后，运行态只剩一套栈；老 woker（条条）栈已清理。

## 一句话结论

**对外服务 = Java + MySQL（Wimoor 标准），PostgreSQL 已清除。** 仓库根目录的 `docker-compose.yml`（woker 老栈：postgres+redis）已于 2026-11-26 执行 `docker compose down -v` 彻底删除容器与数据卷，仅保留 `deploy/wimoor-p0/` 下的 Wimoor 栈。

## 当前唯一生产栈（deploy/wimoor-p0/）

| 层 | 技术 | 端口 | 数据 |
|---|---|---|---|
| 前端 | wimoor-ui (Vue/Vite) 静态 dist | nginx 8080 | — |
| 网关 | wimoor-gateway (Java 11) | 8099 | — |
| 业务 | admin-boot / erp-boot (Java 11) | 8100 / 8101 | MySQL |
| AI | wimoor-ai (Java 11, jdbc 模式) | 8102 | db_ai |
| 基础设施 | MySQL 8 / Redis / Nacos | 13306 / 16379 / 8848 | — |

- Nacos 配置全部指向 `jdbc:mysql://${mysql.host}:${mysql.port}/...`
- MySQL 实际库：`db_admin`(84表)、`db_erp`(191)、`db_amazon`(245)、`db_amazon_adv`(155)、`db_finance`(46)、`db_quartz`(11)、`db_quote`(22)、`db_ai`(9)
- 登录账号：`admin@wimoor.com` / `123456`

## 已清理的老栈（不要再启动）

- 容器：`woker-postgres-1`(postgres:16)、`woker-redis-1`(redis:8) —— 已删除
- 数据卷：`woker_postgres-data`、`woker_redis-data` —— 已删除
- 老库 `amazon_bulk_ad` 有 320 张表，但抽查 `AiChatConversation`/`AmazonAdsProfile` 等均为 0 行（只有表结构、无业务数据），符合"历史数据不要"的决定
- **不要再执行根目录的 `docker compose up`**，那会把老栈带回来

## 生产 bug 修复记录（2026-11-26）

### 前端 `/api/` 前缀调用在 nginx 下落不到后端

- **现象**：登录后菜单接口 `/api/admin/api/v1/menus/route` 返回 SPA 的 index.html（200 但非 JSON），前端解析失败 → 会话被清、跳 ssologin、疯狂重试。
- **根因**：前端有约 212 处 API 调用使用 `/api/...` 前缀（finance 模块、菜单路由等）。开发期靠 Vite `proxy./api` 的 rewrite 去掉前缀；生产构建由 nginx 托管，而 nginx 没有 `/api/` 转发规则，落到 `try_files → index.html`。
- **修复**：在 `deploy/wimoor-p0/nginx.conf` 增加：
  ```
  location /api/ {
      rewrite ^/api/(.*)$ /$1 break;
      proxy_pass http://host.docker.internal:8099;
      ...
  }
  ```
  并 `docker exec wm-p0-nginx nginx -s reload`。
- **验证**：带有效 jsessionid 请求 `/api/admin/api/v1/menus/route` 返回 JSON 菜单数据（与无前缀路径一致）。

## 逐页验收工具

- 脚本：`scripts/page-acceptance.mjs`（Playwright 真实浏览器，逐页打开 126 个菜单 href，检测白屏/报错/重定向/404）
- 用法：`node scripts/page-acceptance.mjs [--limit N] [--only 关键词] [--json 输出路径]`
- 登录方式：浏览器真实提交登录表单（不能只预置 localStorage，否则 SSO 校验会清会话）

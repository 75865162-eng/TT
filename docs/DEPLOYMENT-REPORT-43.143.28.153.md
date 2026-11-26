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

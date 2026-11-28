# CI/CD 自动部署配置指南

## 工作流说明

`.github/workflows/build.yml` 已升级为「构建 + 部署」全流程：

- **触发**：push 到 `main`、手动 `workflow_dispatch`、PR（PR 只构建不部署）；push 只构建，部署需 `workflow_dispatch` 勾选 `deploy_production`
- **build-backend**：JDK 11 编译全部 10 个后端 jar
- **build-frontend**：Node 20 + pnpm 10 构建 Vue 前端（用 pnpm-lock.yaml，不用 npm ci）
- **package-release**：组装部署包（10 jar + html + deploy 脚本 + Nacos 配置 + docs/scripts）
- **deploy**：SSH 直连服务器，上传部署包 → 解压 → 安装 jar → 同步 html/Nacos → 重启服务 → 探活

## 需要配置的 GitHub Secrets

> 2026-11-28 更正：Secret 名以 `.github/workflows/build.yml` 的 `deploy-production` job 为准，
> 统一为 `PRODUCTION_*`（旧文档的 `SERVER_*` 是错的，按旧名配了不会生效）。
> 登录方式：workflow 用 `root@$HOST` + SSH 私钥，不是密码、不是 ubuntu 用户。

在 GitHub 仓库 `Settings → Secrets and variables → Actions → New repository secret` 添加：

| Secret 名 | 值 | 说明 |
|---|---|---|
| `PRODUCTION_SERVER_HOST` | `43.143.28.153` | 部署服务器公网 IP |
| `PRODUCTION_SSH_PRIVATE_KEY` | `<root 用户的 SSH 私钥>` | workflow 用 `root@$HOST` 登录（OpenSSH 私钥全文） |
| `PRODUCTION_SERVER_DIR` | `/opt/wimoor` | 部署根目录；留空则 workflow 默认 `/opt/wimoor` |

> 私钥需是服务器上允许 root 登录的那一对；把公钥追加到 root 的 `~/.ssh/authorized_keys`。
> 本机 fine-grained token 无 `Administration:write`，无法用 API 写 Secrets，须网页手动配置。

## 部署前置条件（服务器，已就绪）

- Docker + Docker Compose v2 + JDK 11 已安装
- `/logs` 目录已创建并授权（logback 写日志用）
- mysql/redis/nacos/nginx 镜像已就位
- 数据库已初始化（9 个库），Nacos 配置由 start-all.sh 自动导入
- ai 服务单独用 8110 端口（避免与 amazon 8102 冲突，deploy 脚本已处理）

## 部署后的验证

CI deploy 末尾会打印登录接口 HTTP 状态码和 java 进程数。完整验收：

```bash
# 逐页验收（126 页）
node scripts/page-acceptance.mjs --json outputs/page-acceptance-latest.json
# 期望：通过 126/126
```

## 分支策略

- 主线：`main`（上一轮确立的交付分支）
- 推 `main` 触发 CI 构建；部署需在 Actions 手动 `workflow_dispatch` 勾选 `deploy_production=true` 后执行

## 本次 CI 验证状态（2026-11-26）

代码已 push 到 `main`（commit `876538a1`），CI run `45801030309` 实测结果：

| Job | 结果 |
|---|---|
| 构建 Vue 前端 dist | ✅ success |
| 编译全部 Java 后端 (JDK 11) | ✅ success |
| 组装一键部署包 | ✅ success |
| SSH 部署到服务器 | ❌ failure —— `can't connect without a private SSH key or password`（Secrets 未配） |

**结论：CI 构建链路已完全跑通，只差 4 个 Secrets 配置即可完成自动部署。**

## 网页配置 Secrets 的确切步骤

1. 打开 `https://github.com/75865162-eng/TT/settings/secrets/actions`
2. 点右上角 `New repository secret`，逐个添加：

   | Name | Secret 值 |
   |---|---|
   | `PRODUCTION_SERVER_HOST` | `43.143.28.153` |
   | `PRODUCTION_SSH_PRIVATE_KEY` | root 用户的 OpenSSH 私钥全文 |
   | `PRODUCTION_SERVER_DIR` | `/opt/wimoor` |

3. 配好后，用 `workflow_dispatch` 勾选 `deploy_production=true` 触发（push main 只构建不部署）。
4. 部署完成后验证：登录接口 200、`version.json` 为新版本、删除店铺功能可用。

> 说明：当前提供的 fine-grained token 只有 Contents:write 权限，无法用 API 写 Secrets（403）。
> 如需全自动配置 Secrets，可提供带 `Administration:write` 权限的 token；否则按上面网页步骤 1 分钟即可配好。

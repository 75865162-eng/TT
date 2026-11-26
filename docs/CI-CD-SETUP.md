# CI/CD 自动部署配置指南

## 工作流说明

`.github/workflows/build.yml` 已升级为「构建 + 部署」全流程：

- **触发**：push 到 `main` 或 `java-only`、手动 `workflow_dispatch`、PR（PR 只构建不部署）
- **build-backend**：JDK 11 编译全部 10 个后端 jar
- **build-frontend**：Node 20 + pnpm 10 构建 Vue 前端（用 pnpm-lock.yaml，不用 npm ci）
- **package-release**：组装部署包（10 jar + html + deploy 脚本 + Nacos 配置 + docs/scripts）
- **deploy**：SSH 直连服务器，上传部署包 → 解压 → 安装 jar → 同步 html/Nacos → 重启服务 → 探活

## 需要配置的 GitHub Secrets

在 GitHub 仓库 `Settings → Secrets and variables → Actions → New repository secret` 添加：

| Secret 名 | 值 | 说明 |
|---|---|---|
| `SERVER_HOST` | `43.143.28.153` | 部署服务器公网 IP |
| `SERVER_USER` | `ubuntu` | SSH 用户名 |
| `SERVER_PASSWORD` | `<服务器登录密码>` | SSH 密码 |
| `SERVER_PORT` | `22` | SSH 端口（可选，默认 22） |

> 安全提示：也可改用 SSH 私钥（在 Actions 里用 `appleboy/scp-action` 的 `key` 参数替换 `password`），更安全。当前按你提供的密码方式配置。

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
- 推 `main` 即触发 CI 自动部署到 43.143.28.153
- 当前工作分支 `java-only` 也会触发部署（按需保留）

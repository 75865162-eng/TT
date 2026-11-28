# CI 升级规划：高效率 · 无残留

> 基于 2026-11-28 代码审计（`.github/workflows/build.yml`、`scripts/change-tier.mjs`、
> `deploy/wimoor-p0/server-deploy.sh`、`scripts/server-artifact-release.sh`、
> `deploy/wimoor-p0/acceptance.sh`、`docs/CI-CD-SETUP.md`）与部署报告现状。
> 目标：在这个 Java+MySQL 单机栈上，把 CI 从"能构建但部署不稳"升级为
> "改哪构建哪、只重启受影响服务、版本可回滚且自动清理、无残留"。

---

## 一、现状诊断（每条都有代码/文档依据）

| # | 问题 | 证据 | 后果 |
|---|---|---|---|
| 1 | 前端构建 npm/pnpm 错配 | 本次已修：`build.yml` 原用 `npm ci + package-lock.json`，项目实为 pnpm + `pnpm-lock.yaml`，setup-node 缓存解析失败 | 已解决（commit `34741880`，CI 前端 job 转 success） |
| 2 | **Secrets 命名分裂** | 代码用 `PRODUCTION_SERVER_HOST` / `PRODUCTION_SSH_PRIVATE_KEY` / `PRODUCTION_SERVER_DIR`；`docs/CI-CD-SETUP.md` 写的是 `SERVER_HOST` / `SERVER_USER` / `SERVER_PASSWORD` / `SERVER_PORT` | 按文档配了也不生效，deploy 永远卡在"检查部署凭证" |
| 3 | **前端根本不在 CI 部署链路里（最致命）** | 2026-11-28 实机勘察：systemd 实际指向 `/opt/wimoor/current` → `/opt/wimoor/releases/initial`（后端**已**版本化+软链，与 CI 脚本一致）；但**前端零处理**——`server-deploy.sh` 全文不碰 html，nginx bind-mount 到 `/home/ubuntu/tt/deploy/wimoor-p0/html`（不在版本目录）。bundle 里虽打包了 html，却从不部署 | 每次前端改动（含本次删除弹窗）永远上不了线；html 走目录 swap，历史 `html.old`（02:04 残留仍在）与 docker bind-mount 旧 inode 问题反复出现 |
| 4 | **版本目录无清理** | `server-deploy.sh` 写 `releases/<sha>` 后从不删除；对照 `server-artifact-release.sh` 有 `KEEP_RELEASES=5` + 进程占用检查 + 清理 | `releases/` 无限累积，磁盘残留 |
| 5 | 每次全量重启 | `server-deploy.sh` 第 107-112 行无差别 stop/start 全部 10 个服务 | 全站停机、效率低；改一个前端也重启全部后端 |
| 6 | 变更识别形同虚设 | `change-tier.mjs` 白名单极窄（仅 UI 白名单可 fast），`server-deploy.sh` 里 `change_tier` 只写进报告，不参与裁剪 | 识别出 fast 也没用，构建/部署始终全量 |
| 7 | 临时产物不回收 | `build.yml` scp 到 `/tmp/wimoor-release-<sha>`，部署后无删除；`logs/` 下 `deployment-report-*` 无限增长 | /tmp 与 logs 残留 |
| 8 | 历史数据脏记录 | `t_amazon_auth` 存在 `sellerid=none` / `AUTO_` 占位 | 本次删除逻辑已兼容，但库里记录仍在，属数据残留 |

### 2026-11-28 实机勘察补充（用历史凭据登录 43.143.28.153 只读核验）

- SSH 可用：`ubuntu@43.143.28.153`（22 端口通，之前探测不通是网络抖动），11 个 `wimoor-*` 服务 active。
- **后端**：systemd `ExecStart` 全部指向 `/opt/wimoor/current/wimoor/...`，
  `current -> /opt/wimoor/releases/initial`，即**已是版本目录 + 软链形态，与 CI 脚本一致**。
- **前端**：nginx 容器 `wm-p0-nginx` bind-mount `/home/ubuntu/tt/deploy/wimoor-p0/html -> /usr/share/nginx/html`，
  **不在任何版本目录**；`/home/ubuntu/tt/deploy/wimoor-p0/html.old`（02:04）是历史 swap 残留，仍在。
- `releases/` 目前只有 `initial` 一个版本，尚未发生多次部署累积。
- 磁盘 `/` 59G，已用 30G，可用 27G（53%），版本保留策略可宽松。
- 附带发现（非本次范围）：`deploy/wimoor-p0/nginx.conf` 的 443 `server_name` 仍为旧品牌 `esmorah.com`，
  与已签发的 `aecob.com` 证书不一致，属品牌残留，建议后续单独处理。

**结论修正**：P0 的"确认部署目录"已完成——后端形态正确无需动；真正要解决的是**前端接入 CI 部署链路并版本化**。

---

## 二、升级目标

- **效率**：构建按变更裁剪；部署只重启受影响的 systemd 服务；缓存复用；健康检查有界。
- **无残留**：单一部署形态（版本目录 + 软链）；旧版本按保留策略自动清理；
  临时 bundle 部署后回收；失败版本即时清理；数据脏记录一次性收敛。

---

## 三、升级方案（四大支柱）

### 支柱 1：把前端纳入版本化部署（消残留根源）

后端已是 `releases/<sha>` + `current` 软链，**无需改动**。缺口只在前端，最小实现：

- 在版本目录 `releases/<sha>/` 下增加 `html/` 子目录，随 bundle 一起写入（bundle 已含 html）。
- nginx 挂载点改为**固定的软链**：`/home/ubuntu/tt/deploy/wimoor-p0/html -> /opt/wimoor/current/html`。
  - 这样切换 `current` 软链后，前端与后端同版本原子切换；
  - 部署末尾 `docker restart wm-p0-nginx` 让其重新挂载新 inode（历史 500 的已知坑）。
- **一次性迁移**：停 nginx → 备份/删除旧 `html/` 与 `html.old/` → 建软链指向 `current/html` → 重启 nginx。
- 迁移后不再有目录 swap、不再产生 `html.old`。

### 支柱 2：增量构建 + 增量重启（提效率）

- `change-tier.mjs` 的 `tier/types` 真正用起来：
  - 只有 `ui`/`docs` 变更 → 跳过后端编译，只构建前端；
  - `business`/`api` 变更 → 只编译受影响模块对应的 jar（按模块路径映射）。
- 部署端按 `types` 决定重启集合：前端变更只动 nginx，后端变更只重启对应服务，
  不再无差别 stop/start 10 个服务。
- 缓存：Maven `~/.m2` 与 pnpm store 已启用 Actions cache，保持；
  产物 bundle 用 `actions/upload-artifact` 带 SHA256 清单，部署端校验后使用。

### 支柱 3：版本保留与自动清理（无残留）

- 在 `server-deploy.sh` 增加 `prune_releases()`（移植 `server-artifact-release.sh` 的成熟逻辑）：
  - 保留最近 N 个版本（建议 N=3）+ `current` 指向版本；
  - 删除前检查每个 systemd 服务的 `/proc/<pid>/cwd`，**正在运行的版本不删**；
  - 失败切换的 `.extracting` 临时目录即时清理。
- 部署成功后，CI 通过 SSH 删除 `/tmp/wimoor-release-<sha>`；
  `logs/` 按时间轮转（保留最近 30 天）。
- 数据残留：`t_amazon_auth` 的 `none`/`AUTO_` 占位记录，用一次性幂等 SQL 收敛（需你确认业务是否允许）。

### 支柱 4：Secrets 与文档对齐（消除配错）

- 统一 Secret 名，CI 代码和 `docs/CI-CD-SETUP.md` 用同一套：
  - `PRODUCTION_SERVER_HOST` = `43.143.28.153`
  - `PRODUCTION_SSH_PRIVATE_KEY` = 服务器 SSH 私钥（workflow 用 `root@$HOST` 登录）
  - `PRODUCTION_SERVER_DIR` = 与服务器实际部署目录一致
- 文档删除过时的 `SERVER_PASSWORD` 方式，避免再次按错文档配置。

---

## 四、分阶段路线图

| 阶段 | 内容 | 风险 | 前置条件 |
|---|---|---|---|
| **P0 先决** | ✅ 已完成：勘察确认后端已版本化、前端未接入；Secrets 待你网页配置 | 低 | 你配好 `PRODUCTION_*` Secrets |
| **P1 低风险高收益** | ① `server-deploy.sh` 增加前端部署（写入 `releases/<sha>/html` + 切软链 + 重启 nginx）；② 版本保留清理 `KEEP_RELEASES=3`；③ 临时 bundle/logs 回收；④ Secrets 命名与文档对齐 | 中（动 nginx 挂载一次性迁移） | P0 |
| **P2 效率** | change-tier 驱动增量构建；部署端按 types 增量重启 | 中 | P1 稳定 |
| **P3 验证增强** | 部署后验收从 9 项轻量扩展到按需 --full 130 页；报告归档 | 低 | P2 |

---

## 五、需要拍板的决策点

1. **部署目录二选一**：统一到 `/opt/wimoor` 还是 `/home/ubuntu/tt/deploy/wimoor-p0`？（取决于服务器现状）
2. **登录方式**：CI 用 `root` + 私钥，还是 `ubuntu` + sudo？（workflow 当前写死 `root@$HOST`）
3. **版本保留数**：建议 3，内存/磁盘紧张可降到 2。
4. **数据脏记录清理**：是否允许一次性收敛 `t_amazon_auth` 占位记录？
5. **落地顺序**：先 P0+P1（稳），还是直接 P0→P2（效率优先）？

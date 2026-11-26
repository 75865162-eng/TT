# CI/CD 生产验证与耗时对比 Runbook（阶段 4 交付）

> 配套：`ERP-CI-CD改造方案-适配Wimoor-Java栈.md`、`.github/workflows/build.yml`、`server-deploy.sh`、`acceptance.sh`
> 状态：验证脚本已落地；**7 项生产真实验证需部署凭证（GitHub Secrets 或 43 服务器 SSH），当前未执行，不虚报**。

## 一、阶段 2–4 已落地的代码

| 阶段 | 交付物 | 关键能力 |
|---|---|---|
| 2 安全发布 | `setup-systemd.sh` | 10 个服务 systemd unit；SIGTERM 优雅停止（30s 超时 KILL）；`Restart=on-failure`；ExecStart 指向 `current` 软链 |
| 2 安全发布 | `server-deploy.sh` | flock 互斥；SHA256 校验；版本目录；DB 幂等迁移；原子切换；优先 systemd 优雅重启，回退 start-all.sh |
| 3 自动验收 | `acceptance.sh` | 9 项检查（Web/登录/菜单/MySQL/Redis/Nacos/Worker/文件存储/权限隔离），`--full` 追加 130 页业务回归 |
| 3 自动恢复 | `server-deploy.sh` | 健康/验收失败→切回旧版本软链+重启+二次检查；DB 迁移失败不回滚数据库，转人工；保留故障日志 |
| 4 自动报告 | `server-deploy.sh` + build.yml | `deployment-report-<时间>.json`（版本/分支/结果/回滚/失败原因/变更档位类型/各阶段耗时/验收统计）；CI Job 拉取写 Summary |

## 二、两个未决事实（已查证，影响验收口径）

- **Worker**：Java 栈**没有独立 Worker 进程**。定时任务/异步任务内嵌在各 boot 服务里（`@Scheduled`、`ReportWorker`、admin 里的 Quartz）。所以 Worker 验收=检查这些宿主服务进程存活（systemd 活跃数或 java 进程数）。
- **文件存储**：用的是**阿里云 OSS**（`FileController` 里 `oss-cn-shenzhen.aliyuncs.com`），不是本地磁盘/MinIO。无真实 OSS 凭证时，该项标 SKIP（"配置存在但未实连"），不假装通过。

## 三、7 项生产验证清单（需部署凭证后逐项执行）

> 前置：在 TT 仓库配好 Secrets `PRODUCTION_SERVER_HOST`、`PRODUCTION_SSH_PRIVATE_KEY`、`PRODUCTION_SERVER_USER`、`PRODUCTION_SERVER_DIR`；创建 `production` environment。

| # | 验证项 | 操作方法 | 通过标准 |
|---|---|---|---|
| 1 | 普通代码变更快速部署 | 只改 `wimoor-ui/src/views/**.vue`，push，观察 change-tier 判 fast，走快速发布 | 报告 `change_tier=fast`，部署成功，验收无 FAIL |
| 2 | 数据库变更安全部署 | 新增一个幂等 SQL 到 mysql-init，首次部署执行、二次部署 SKIP | 报告 `migrate` 首个执行、第二个 SKIP；`schema_migrations` 表有记录 |
| 3 | 部署失败快速停止 | 故意上传损坏产物（改 SHA256SUMS 一行），触发部署 | 校验阶段即失败退出，耗时 < 30s，旧版本未受影响 |
| 4 | 服务异常安全恢复 | 部署后手动停一个服务，或在验收阶段制造 FAIL | 报告 `rolled_back=true`，切回旧版本且 8080 恢复 200 |
| 5 | 重复触发不并发覆盖 | 同时触发两次 workflow_dispatch | 服务器端 flock 使第二次立即失败；CI concurrency 排队；无版本目录被覆盖 |
| 6 | Worker/Redis 队列不受破坏 | 部署前后对比 Redis 队列长度、检查 java 服务存活 | 队列长度不因部署归零（脚本无 FLUSHDB/FLUSHALL）；服务进程存活 |
| 7 | 生产真实业务验收通过 | 部署成功后跑 `acceptance.sh --full` | 130 页回归通过或明确列出失败页；9 项验收 FAIL=0 |

## 四、升级前后耗时对比采集方法（不估算，用实测填）

对每个场景，记录以下时间点（报告 JSON 已自动采集各阶段 `stages_seconds` 与 `total_seconds`）：

- **升级前基线**：用旧 build.yml（无 SHA256、无验收）手动跑一次，记录：SSH 连接耗时、构建耗时、上传耗时、盲等 sleep、人工操作步骤数。
- **升级后**：从 `deployment-report-*.json` 读取 `total_seconds` 与各阶段耗时。

对比表（完工后用实测填写，当前留空不虚报）：

| 场景 | 升级前基线 | 升级后实测 | 来源 |
|---|---|---|---|
| SSH 正常连接 | 待测 | 待测 | 手动计时 / 报告 |
| SSH 失败判定 | 待测（老脚本 60s 超时） | ≤30s（脚本强制） | 场景 3 |
| 普通变更部署总耗时 | 待测 | 待测 | 报告 `total_seconds` |
| 固定盲等 | 90s（start-all.sh 旧值） | 0（有上限轮询） | 代码确认 |
| 生产重复构建 | 待测 | 0（只部署产物） | 代码确认 |

## 五、诚实边界

- 以上 7 项**尚未在真实生产执行**（无 43 服务器凭证）。代码已就绪，凭证到位即可逐项验证。
- `server-deploy.sh`、`acceptance.sh`、`setup-systemd.sh` 已做 bash 语法检查，但未在 43 服务器真实跑过——不能用"语法通过"冒充"生产验证通过"。
- 阶段 4 的耗时对比表当前为空，等真实验证后填。

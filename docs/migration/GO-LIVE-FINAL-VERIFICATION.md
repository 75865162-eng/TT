# java-only 生产系统 · 上线前最终验证与收口报告

验证时间：2026-11-26　｜　分支：`main`（与 `java-only` 同步）　｜　最终 HEAD：`38d709ed`
验证方式：真实环境全链路——构建 → 部署 → 服务启动 → 真实浏览器逐页验收 → 服务端数据验收。
验收证据：`outputs/page-acceptance-final.json`（126 页全量）。

---

## 一、闭环结论

**上线条件全部达标，可以收口发布。**

| 验证维度 | 结果 | 证据 |
|---|---|---|
| 基线一致性 | ✅ 本地 = 远端 main = `38d709ed`，工作区干净 | git status / ls-remote |
| 构建完整性 | ✅ 7 个后端 jar 无损、前端 887 JS + 408 CSS | unzip -t / 产物清单 |
| 部署链路 | ✅ 4 容器健康、11 项 Nacos 配置、菜单收敛生效 | docker ps / Nacos API / SQL |
| 服务启动 | ✅ gateway/admin/erp/amazon/amazon-adv 全部 STARTED | 各服务日志 Started 标记 |
| 逐页验收 | ✅ **126/126 全通过**，零致命错误 | page-acceptance-final.json |
| 服务端验收 | ✅ 菜单 229/173/56、tiaotiao 4 入口可见 | t_sys_menu 查询 |

---

## 二、本次验证新发现并修复的问题

上线前验证不是复述旧结论，而是实测跑通全链路，过程中发现并修复了 2 个此前未暴露的真实问题：

### 1. 广告服务（amazon-adv）启动错误 —— 真正根因修复
- **现象**：广告服务启动即崩 `Failed to instantiate [PictureMapper]: Specified class is an interface`。
- **根因**：广告服务 dao 的 mapper 全部是 **mybatis-plus** 的 `@Mapper + BaseMapper`，但启动类误用了 **tk.mybatis** 的 `MapperScan`。两套框架扫描器错配，导致 common.mapper 下的接口被当普通 bean 实例化；jar 里同时存在 4 套 mybatis starter 加剧冲突。
- **修复**：`AmazonAdvApplication.java` 的 `@MapperScan` 从 `tk.mybatis.spring.annotation.MapperScan` 换成 `org.mybatis.spring.annotation.MapperScan`（与 finance/gen 一致）。2 行改动。
- **验证**：重编 jar 后实测 `Started AmazonAdvApplication in 9.669 seconds`，Tomcat 8103 `/amazonadv`。此前报告说的"Nacos 配置导入修复"只解决了 `jdbcUrl` 那一层，这层 mapper 冲突是更深的遗留问题，本次一并收口。

### 2. 广告概览页（overview）空值崩溃 —— 第 4 个前端空值 bug
- **现象**：`/amazon/a/summary` 报 `Cannot read properties of undefined (reading 'impco')`。
- **根因**：amazon 服务刚启动、数据未就绪时 `getProductWarningIndicator`/`getKeywordsWarningIndicator` 返回 `undefined`，`state.waringData=res.data` 后模板读 `waringData.impco` 崩溃。
- **修复**：两处赋值加 `|| {}` 兜底。
- **验证**：重新构建部署 + 重启服务后，最终全量验收 126/126 全通过。

---

## 三、全链路验证过程

### 阶段 1：基线确认
- 分支 `java-only`，HEAD `6cbd2515`，工作区干净，本地与远端 main 一致。
- 合并提交 `6cbd2515` 为纯新增 162 文件（Agent 文档/skills/specs/可移植脚本），0 修改现有源码，代码零 prisma/next 残留。

### 阶段 2：构建验证
- JDK 11 全量编译 9 个后端 jar 全部 `unzip -t` 通过（含 amazon-adv、quote）。
- 前端产物完整：index.html + 887 JS + 408 CSS，三处历史修复 chunk（tag/backup/payment）在位。

### 阶段 3：部署验证
- 4 个容器（mysql/redis/nacos/nginx）健康运行。
- 11 项 Nacos 配置导入成功（含 `wimoor-amazon-adv`）。
- 菜单收敛 SQL 执行：229 总 / 173 可见 / 56 隐藏，tiaotiao 4 入口可见。
- 5 个核心服务全部 STARTED，登录接口返回 200 真实数据。

### 阶段 4：逐页验收
- 真实 Chromium 登录，遍历 126 个菜单 URL。
- 过程中发现 amazon 服务数据未就绪导致 overview 空值崩溃（已修复），最终 **126/126 全通过**。

### 阶段 5：服务端验收 & 收口
- 菜单、tiaotiao 入口、Nacos 配置、容器健康全部达标。
- 3 个新提交（`769c2d5b` 广告修复、`38d709ed` overview 修复 + 验收）已推送 main，远端与本地一致，工作区干净。

---

## 四、交付清单（本次新增提交）

| 提交 | 内容 |
|---|---|
| `769c2d5b` | fix(adv)：MapperScan 换 org.mybatis，修复广告服务启动错误 |
| `38d709ed` | fix(overview)：广告概览空值兜底 + 126/126 验收证据 |

累计本会话已收口：菜单收敛、tiaotiao 4 入口落地、3+1 个前端空值 bug、报价 sxjd 依赖、广告启动双层根因、start-all.sh Nacos 导入、main/java-only 分支合并（剔除 postgresql 那套），全部有实测证据。

**状态：闭环完成，可上线。**

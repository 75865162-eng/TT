-- wimoor-ai 真实持久化表结构（生产级闭环）
-- 库：db_ai；由 ai.persistence=jdbc 启用。默认内存态不需要本库。
-- 约定：所有主键 VARCHAR；金额 DECIMAL(20,6)；时间戳 BIGINT(epoch ms) 便于跨库一致；
--       证据/载荷用 TEXT 存 JSON，避免为迁移期字段波动反复改表。
CREATE DATABASE IF NOT EXISTS db_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE db_ai;

-- 对话：租户三元组 + revision 乐观锁
CREATE TABLE IF NOT EXISTS ai_conversation (
  id            VARCHAR(64)  NOT NULL,
  org_id        VARCHAR(64)  NOT NULL,
  workspace_id  VARCHAR(64)  NOT NULL,
  user_id       VARCHAR(64)  NOT NULL,
  title         VARCHAR(512) NOT NULL DEFAULT '',
  status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  revision      INT          NOT NULL DEFAULT 0,
  created_at    BIGINT       NOT NULL,
  updated_at    BIGINT       NOT NULL,
  PRIMARY KEY (id),
  KEY idx_scope (org_id, workspace_id, user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消息：client_key / request_key 幂等唯一（租户内）
CREATE TABLE IF NOT EXISTS ai_message (
  id             VARCHAR(64)  NOT NULL,
  conversation_id VARCHAR(64) NOT NULL,
  org_id         VARCHAR(64)  NOT NULL,
  workspace_id   VARCHAR(64)  NOT NULL,
  user_id        VARCHAR(64)  NOT NULL,
  role           VARCHAR(16)  NOT NULL,
  content        TEXT         NOT NULL,
  client_key     VARCHAR(128) DEFAULT NULL,
  request_key    VARCHAR(160) DEFAULT NULL,
  skill_slug     VARCHAR(128) DEFAULT NULL,
  skill_hash     VARCHAR(128) DEFAULT NULL,
  status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
  created_at     BIGINT       NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_client_key (org_id, workspace_id, user_id, client_key),
  KEY idx_conv (conversation_id, created_at),
  KEY idx_request (request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 运行：状态机 + 幂等 + 乐观取消
CREATE TABLE IF NOT EXISTS ai_run (
  id             VARCHAR(64)  NOT NULL,
  conversation_id VARCHAR(64) NOT NULL,
  org_id         VARCHAR(64)  NOT NULL,
  workspace_id   VARCHAR(64)  NOT NULL,
  user_id        VARCHAR(64)  NOT NULL,
  message_id     VARCHAR(64)  NOT NULL,
  request_key    VARCHAR(160) NOT NULL,
  status         VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
  model          VARCHAR(128) DEFAULT NULL,
  skill_slug     VARCHAR(128) DEFAULT NULL,
  skill_hash     VARCHAR(128) DEFAULT NULL,
  answer_preview VARCHAR(512) DEFAULT NULL,
  error_code     VARCHAR(64)  DEFAULT NULL,
  answer_json    TEXT         DEFAULT NULL,
  finished_at    BIGINT       DEFAULT NULL,
  created_at     BIGINT       NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_request (request_key),
  KEY idx_scope (org_id, workspace_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 工具调用证据：run 内按 seq 升序
CREATE TABLE IF NOT EXISTS ai_tool_call (
  id            VARCHAR(64)  NOT NULL,
  run_id        VARCHAR(64)  NOT NULL,
  org_id        VARCHAR(64)  NOT NULL,
  seq           INT          NOT NULL,
  provider      VARCHAR(64)  NOT NULL,
  tool          VARCHAR(128) NOT NULL,
  args_summary  VARCHAR(512) DEFAULT NULL,
  result_source VARCHAR(64)  DEFAULT NULL,
  result_hash   VARCHAR(128) DEFAULT NULL,
  row_count     INT          DEFAULT NULL,
  status        VARCHAR(32)  NOT NULL,
  error_code    VARCHAR(64)  DEFAULT NULL,
  duration_ms   BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_run_seq (run_id, seq),
  KEY idx_org (org_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 积分账本：request_key 幂等；影子/强制双模式
CREATE TABLE IF NOT EXISTS ai_points_ledger (
  request_key  VARCHAR(200) NOT NULL,
  org_id       VARCHAR(64)  NOT NULL,
  user_id      VARCHAR(64)  NOT NULL,
  provider     VARCHAR(64)  NOT NULL,
  points       DECIMAL(20,6) NOT NULL,
  charged      TINYINT(1)   NOT NULL DEFAULT 0,
  blocked      TINYINT(1)   NOT NULL DEFAULT 0,
  released     TINYINT(1)   NOT NULL DEFAULT 0,
  created_at   BIGINT       NOT NULL,
  PRIMARY KEY (request_key),
  KEY idx_user (org_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 积分余额：按租户+用户
CREATE TABLE IF NOT EXISTS ai_points_balance (
  org_id     VARCHAR(64) NOT NULL,
  user_id    VARCHAR(64) NOT NULL,
  balance    DECIMAL(20,6) NOT NULL,
  enforce    TINYINT(1)   NOT NULL DEFAULT 0,
  updated_at BIGINT       NOT NULL,
  PRIMARY KEY (org_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 选品研究运行：crash-safe checkpoint（save 先于计费请求）
CREATE TABLE IF NOT EXISTS ai_research_run (
  id           VARCHAR(64)  NOT NULL,
  org_id       VARCHAR(64)  NOT NULL,
  user_id      VARCHAR(64)  NOT NULL,
  state_json   TEXT         NOT NULL,
  stage        VARCHAR(32)  NOT NULL DEFAULT 'INIT',
  status       VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
  checkpoint   INT          NOT NULL DEFAULT 0,
  created_at   BIGINT       NOT NULL,
  updated_at   BIGINT       NOT NULL,
  PRIMARY KEY (id),
  KEY idx_scope (org_id, user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Skill 运行历史：作用域隔离 + SHA-256 校验
CREATE TABLE IF NOT EXISTS ai_skill_run (
  id           VARCHAR(64)  NOT NULL,
  org_id       VARCHAR(64)  NOT NULL,
  workspace_id VARCHAR(64)  NOT NULL,
  user_id      VARCHAR(64)  NOT NULL,
  skill_slug   VARCHAR(128) NOT NULL,
  skill_hash   VARCHAR(128) NOT NULL,
  status       VARCHAR(32)  NOT NULL,
  payload_json TEXT         DEFAULT NULL,
  checksum     VARCHAR(128) DEFAULT NULL,
  created_at   BIGINT       NOT NULL,
  PRIMARY KEY (id),
  KEY idx_scope (org_id, workspace_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 报表任务：五阶段状态机 + 退避 + checkpoint + 发布版本
CREATE TABLE IF NOT EXISTS ai_report_task (
  id            VARCHAR(64)  NOT NULL,
  org_id        VARCHAR(64)  NOT NULL,
  report_type   VARCHAR(64)  NOT NULL,
  stage         VARCHAR(32)  NOT NULL DEFAULT 'CREATE',
  status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
  attempt       INT          NOT NULL DEFAULT 0,
  checkpoint    INT          NOT NULL DEFAULT 0,
  payload_json  TEXT         DEFAULT NULL,
  publication   INT          NOT NULL DEFAULT 0,
  error_code    VARCHAR(64)  DEFAULT NULL,
  next_retry_at BIGINT       DEFAULT NULL,
  created_at    BIGINT       NOT NULL,
  updated_at    BIGINT       NOT NULL,
  PRIMARY KEY (id),
  KEY idx_scope (org_id, status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

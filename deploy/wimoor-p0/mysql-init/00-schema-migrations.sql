-- 数据库迁移版本表：记录已执行的迁移脚本版本与校验和，避免无变化重复迁移。
-- 幂等：可重复执行。需在业务库执行（db_admin / db_ai 各自一份）。
CREATE TABLE IF NOT EXISTS schema_migrations (
  version     VARCHAR(64)  NOT NULL COMMENT '迁移版本号（通常为文件名）',
  checksum    VARCHAR(64)  NOT NULL COMMENT '迁移文件 SHA256 校验和',
  applied_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库迁移版本记录';

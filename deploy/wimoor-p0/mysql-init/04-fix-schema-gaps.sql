-- 修复官方 Wimoor 源码自带的 schema 矛盾（生产级闭环补丁，幂等可重复执行）
-- 背景：官方 Mapper/实体类引用了某些列/表，但官方建表脚本缺失，导致点击页面报
--   Unknown column 'findex' in 'field list' / Table 'db_admin.t_sys_help_doc' doesn't exist
-- 与 03-fix-purchase-fin-groupid.sql 属于同类修复。全部幂等，可重复执行。

-- ============================================================
-- 1) db_erp.t_erp_fin_account 补 findex 列
--    FinAccountMapper.xml(findAccountAll/saveAccountIndex) 与 FinAccount.java 引用 t.findex
-- ============================================================
DROP PROCEDURE IF EXISTS _add_fin_account_findex;
DELIMITER //
CREATE PROCEDURE _add_fin_account_findex()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'db_erp' AND TABLE_NAME = 't_erp_fin_account' AND COLUMN_NAME = 'findex'
  ) THEN
    ALTER TABLE db_erp.t_erp_fin_account
      ADD COLUMN `findex` INT DEFAULT 0 COMMENT '账户排序号(官方Mapper引用)' AFTER `paymeth`;
  END IF;
END //
DELIMITER ;
CALL _add_fin_account_findex();
DROP PROCEDURE IF EXISTS _add_fin_account_findex;

-- ============================================================
-- 2) db_admin.t_sys_help_doc 建表（帮助中心/AI问答页读取，缺失会报 Table doesn't exist）
-- ============================================================
CREATE TABLE IF NOT EXISTS `db_admin`.`t_sys_help_doc` (
  `id` BIGINT(19) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `doc_key` VARCHAR(100) NOT NULL COMMENT '文档标识',
  `title` VARCHAR(200) NOT NULL COMMENT '文档标题',
  `content` TEXT NOT NULL COMMENT '文档内容(Markdown)',
  `category` VARCHAR(50) DEFAULT NULL COMMENT '分类',
  `keywords` VARCHAR(500) DEFAULT NULL COMMENT '关键词',
  `path` VARCHAR(200) DEFAULT NULL COMMENT '页面路径',
  `url` VARCHAR(500) DEFAULT NULL COMMENT '外部链接',
  `related_docs` VARCHAR(500) DEFAULT NULL COMMENT '关联文档',
  `sort_order` INT(10) DEFAULT 0 COMMENT '排序号',
  `status` TINYINT(1) DEFAULT 1 COMMENT '状态(1启用)',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_key` (`doc_key`),
  KEY `idx_category` (`category`),
  KEY `idx_path` (`path`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帮助文档表';

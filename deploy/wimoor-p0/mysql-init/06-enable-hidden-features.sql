-- ============================================================
-- 06 - 启用隐藏功能所需的表/列（幂等，可重复执行）
-- 覆盖：交易报告主表、CSV列名映射表、账期末结转记录表、付款记录结转关联列
-- 依据：实体类 + Mapper.xml 反推，列与 MyBatis-Plus 驼峰转下划线一致
-- 执行：mysql -uroot -p < 06-enable-hidden-features.sql
-- ============================================================

USE db_amazon;

-- 1. 亚马逊交易报告主表（TransactionReport.java / TransactionReportMapper.xml）
CREATE TABLE IF NOT EXISTS `t_amz_transaction_report` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `amazonauthid` bigint unsigned DEFAULT NULL COMMENT '亚马逊授权ID',
    `marketplaceid` varchar(20) DEFAULT NULL COMMENT '站点ID',
    `data_hash` varchar(64) DEFAULT NULL COMMENT '数据哈希值(去重预留)',
    `date_time` datetime DEFAULT NULL COMMENT '交易日期时间',
    `settlement_id` varchar(64) DEFAULT NULL COMMENT '结算ID',
    `transaction_type` varchar(64) DEFAULT NULL COMMENT '交易类型',
    `description` varchar(512) DEFAULT NULL COMMENT '交易描述',
    `order_id` varchar(64) DEFAULT NULL COMMENT '订单ID',
    `sku` varchar(128) DEFAULT NULL COMMENT '商品SKU',
    `quantity` int DEFAULT NULL COMMENT '数量',
    `marketplace` varchar(64) DEFAULT NULL COMMENT '商城',
    `fulfillment` varchar(64) DEFAULT NULL COMMENT '履约方式',
    `order_city` varchar(128) DEFAULT NULL COMMENT '订单城市',
    `order_state` varchar(128) DEFAULT NULL COMMENT '订单州/省',
    `order_post_code` varchar(64) DEFAULT NULL COMMENT '订单邮政编码',
    `tax_collection_model` varchar(64) DEFAULT NULL COMMENT '征税模型',
    `product_sales` decimal(18,4) DEFAULT NULL COMMENT '产品销售金额',
    `product_sales_tax` decimal(18,4) DEFAULT NULL COMMENT '产品销售税',
    `shipping_credits` decimal(18,4) DEFAULT NULL COMMENT '运费抵扣',
    `shipping_credits_tax` decimal(18,4) DEFAULT NULL COMMENT '运费抵扣税',
    `giftwrap_credits` decimal(18,4) DEFAULT NULL COMMENT '礼品包装抵扣',
    `giftwrap_credits_tax` decimal(18,4) DEFAULT NULL COMMENT '礼品包装抵扣税',
    `promotional_rebates` decimal(18,4) DEFAULT NULL COMMENT '促销返利',
    `promotional_rebates_tax` decimal(18,4) DEFAULT NULL COMMENT '促销返利税',
    `marketplace_withheld_tax` decimal(18,4) DEFAULT NULL COMMENT '商城代扣税',
    `selling_fees` decimal(18,4) DEFAULT NULL COMMENT '销售费用',
    `fba_fees` decimal(18,4) DEFAULT NULL COMMENT 'FBA费用',
    `other_transaction_fees` decimal(18,4) DEFAULT NULL COMMENT '其他交易费用',
    `other` decimal(18,4) DEFAULT NULL COMMENT '其他',
    `total` decimal(18,4) DEFAULT NULL COMMENT '总计金额',
    `transaction_status` varchar(64) DEFAULT NULL COMMENT '交易状态',
    `transacton_release_date` datetime DEFAULT NULL COMMENT '交易发布日期',
    `createtime` datetime DEFAULT NULL COMMENT '创建时间',
    `opttime` datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_auth_market_date` (`amazonauthid`,`marketplaceid`,`date_time`),
    KEY `idx_auth_date_type` (`amazonauthid`,`date_time`,`transaction_type`),
    KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='亚马逊交易报告表';

-- 2. 交易报告 CSV 列名映射表（独立初始化脚本，若未执行则此处建空表）
CREATE TABLE IF NOT EXISTS `t_amz_transaction_report_column_mapping` (
    `id` bigint unsigned NOT NULL,
    `marketplaceid` varchar(20) NOT NULL COMMENT '站点ID，default表示通用兜底',
    `field_name` varchar(64) NOT NULL COMMENT 'Java实体字段名',
    `column_name` varchar(255) NOT NULL COMMENT 'CSV列名(lowercase后)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_market_field_col` (`marketplaceid`,`field_name`,`column_name`),
    KEY `idx_marketplaceid` (`marketplaceid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交易报告CSV列名映射表';

USE db_erp;

-- 3. 账期末结转记录表（FinAccountPeriodRollover.java / FinAccountPeriodRolloverMapper.xml）
CREATE TABLE IF NOT EXISTS `t_erp_fin_account_period_rollover` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `acct` varchar(64) DEFAULT NULL COMMENT '账户ID(t_erp_fin_account.id)',
    `total_amount` decimal(18,4) DEFAULT NULL COMMENT '本次结转费用',
    `operator` varchar(64) DEFAULT NULL COMMENT '操作人',
    `creator` varchar(64) DEFAULT NULL COMMENT '创建人',
    `opttime` datetime DEFAULT NULL COMMENT '操作时间',
    `createtime` datetime DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_acct_opttime` (`acct`,`opttime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='账期末结转记录表';

-- 4. 付款记录结转关联列（PurchaseFormPayment.java @TableField(period_rollover_id)）
--    实体为 String，用 varchar(40)；与结转表 id(bigint) JOIN 时 MySQL 隐式转换匹配
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='db_erp' AND TABLE_NAME='t_erp_purchase_form_payment'
      AND COLUMN_NAME='period_rollover_id'
);
SET @sql = IF(@col_exists=0,
    'ALTER TABLE `t_erp_purchase_form_payment` ADD COLUMN `period_rollover_id` varchar(40) DEFAULT NULL COMMENT ''关联结转记录ID'' AFTER `payment_method`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 启用交易报告菜单（用户确认要使用该功能）
USE db_admin;
UPDATE t_sys_menu SET visible = 1 WHERE id = 1012205 AND path = '/amazon/payment/transaction';

-- 6. 数据校验
SELECT '=== 补建结果校验 ===' AS info;
SELECT 't_amz_transaction_report' AS obj, COUNT(*) AS cols FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_transaction_report'
UNION ALL SELECT 't_amz_transaction_report_column_mapping', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_transaction_report_column_mapping'
UNION ALL SELECT 't_erp_fin_account_period_rollover', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_erp' AND TABLE_NAME='t_erp_fin_account_period_rollover'
UNION ALL SELECT 'period_rollover_id', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_erp' AND TABLE_NAME='t_erp_purchase_form_payment' AND COLUMN_NAME='period_rollover_id';

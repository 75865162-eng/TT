-- 修复 AUTO_ 占位卖家 ID 超出 sellerid 列长度导致报表落库失败（幂等，可重复执行）
-- 背景：店铺授权 Seller Id 留空时系统生成 AUTO_<groupid>_<region> 占位 ID（如
--   AUTO_102184196314759643_NA，长度 26）。但多张表的 sellerid 列只有 char(15/16/20)，
--   createReport 回调写 t_report_requestrecord 时抛
--   Data truncation: Data too long for column 'sellerid'，报表请求记录 0 条，
--   进而订单/FBA费用/仓储费/结算等报表类落地页全部无数据。
-- 同类问题：t_product_price.SellerId 此前已扩到 char(50)，本次补齐其余表。
-- 全部幂等：仅在列长度 < 50 时才 ALTER。字符集/可空属性保持各表原样。

DROP PROCEDURE IF EXISTS _widen_sellerid;
DELIMITER //
CREATE PROCEDURE _widen_sellerid()
BEGIN
  -- utf8mb4 / NULL
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_report_requestrecord' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_report_requestrecord MODIFY COLUMN `sellerid` char(50) NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_api_timelimit_seller_request' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amz_api_timelimit_seller_request MODIFY COLUMN `sellerid` char(50) NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amazon_auth_market_performance' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amazon_auth_market_performance MODIFY COLUMN `sellerid` char(50) NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_follow_offerchange' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amz_follow_offerchange MODIFY COLUMN `sellerid` char(50) NULL;
  END IF;
  -- utf8mb4 / NOT NULL
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_submitfeed' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amz_submitfeed MODIFY COLUMN `sellerid` char(50) NOT NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_epr_monthly_report' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amz_epr_monthly_report MODIFY COLUMN `sellerid` char(50) NOT NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_fbm_returns_report' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amz_fbm_returns_report MODIFY COLUMN `sellerid` char(50) NOT NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_returns_report' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amz_returns_report MODIFY COLUMN `sellerid` char(50) NOT NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='db_amazon' AND TABLE_NAME='t_amz_returns_report_summary' AND COLUMN_NAME='sellerid' AND COLUMN_TYPE <> 'char(50)') THEN
    ALTER TABLE db_amazon.t_amz_returns_report_summary MODIFY COLUMN `sellerid` char(50) NOT NULL;
  END IF;
END //
DELIMITER ;
CALL _widen_sellerid();
DROP PROCEDURE IF EXISTS _widen_sellerid;

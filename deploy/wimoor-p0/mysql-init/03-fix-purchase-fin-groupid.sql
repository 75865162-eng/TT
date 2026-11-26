-- 修复官方 Wimoor 源码自带的 schema 矛盾：
-- PurchaseFinanceFormMapper.xml 与 PurchaseFinanceForm.java 都引用 t.groupid，
-- 但官方建表脚本 t_erp_purchase_fin_form 缺该列，导致点击「采购请款单」列表报
-- Unknown column 't.groupid' in 'on clause'。
-- 幂等：仅当列不存在时添加，可重复执行。
-- 目标库：db_erp

DROP PROCEDURE IF EXISTS _add_groupid_if_missing;
DELIMITER //
CREATE PROCEDURE _add_groupid_if_missing()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 't_erp_purchase_fin_form'
      AND column_name = 'groupid'
  ) THEN
    ALTER TABLE t_erp_purchase_fin_form
      ADD COLUMN groupid bigint unsigned DEFAULT NULL
      COMMENT '亚马逊店铺组ID(对齐实体类/Mapper)' AFTER shopid;
  END IF;
END//
DELIMITER ;
CALL _add_groupid_if_missing();
DROP PROCEDURE IF EXISTS _add_groupid_if_missing;

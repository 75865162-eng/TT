-- ============================================================
-- 08-scheduler-tasks.sql
-- 目的：按官方文档 SP-API 限流 + 数据时效性，分层配置后台数据拉取调度任务
-- 复用系统既有 Quartz 体系（t_sys_quartz_task + SysTaskController + QuartzTaskFactory）
-- 依赖：SchedulerApiUtil 已修复"裸调 403"问题（自动登录拿 token 再调端点）
--
-- 频率设计依据（官方文档 §11.6 / 附录）：
--   - Orders getOrders v0: rate 0.0167/s(~1次/分), burst 20 → 订单 15 分钟
--   - getOrderItems: rate 0.5/s → 订单明细 20 分钟（比订单略疏，错峰）
--   - FBA Inventory / Listing / Pricing: 变化较慢 → 1h / 6h
--   - 财务结算: 报告按天/周生成 → 每天
--   - 报告下载解析 getReportDocument: 报告生成需等待 → 15 分钟高频轮询
--   - 广告报告/快照: T+1 为主 → 每天
-- 各域错峰分布（minute 字段错开），避免同一时刻并发触发限流。
--
-- 幂等：按 name 唯一，先删同名再插，可重复执行。
-- 默认 isdelete=1（暂停）：上线后请先核对，再在"系统任务"页面逐个启用。
-- 所有 path 走本机网关（127.0.0.1:8080，纯内网），数据链路本身直连亚马逊官方端点。
-- ============================================================

USE db_admin;

-- 清理同名任务（幂等）
DELETE FROM t_sys_quartz_task WHERE name LIKE 'AEC-SYNC-%';

-- ---------- 1. 订单域 ----------
INSERT INTO t_sys_quartz_task (name,fgroup,target,priority,description,cron,server,bean,method,parameter,path,isdelete,createdate) VALUES
('AEC-SYNC-ORDER','aec-sync','#',5,'订单全量拉取-15min','0 3/15 * * * ?','','','','','http://127.0.0.1:8080/amazon/api/v0/orders/refreshOrder',b'1',NOW()),
('AEC-SYNC-ORDER-ITEM','aec-sync','#',5,'订单明细拉取-20min','0 8/20 * * * ?','','','','','http://127.0.0.1:8080/amazon/api/v0/orders/refreshOrdersItem',b'1',NOW()),
('AEC-SYNC-FULFILLMENT','aec-sync','#',5,'多渠道订单拉取-30min','0 12/30 * * * ?','','','','','http://127.0.0.1:8080/amazon/api/v0/orders/fulfillment/refreshOrder',b'1',NOW()),

-- ---------- 2. 库存域 ----------
('AEC-SYNC-INVENTORY','aec-sync','#',5,'FBA库存拉取-1h','0 15 1 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v0/inventry/taskInventoryData',b'1',NOW()),

-- ---------- 3. 商品域 ----------
('AEC-SYNC-LISTING','aec-sync','#',5,'Listing拉取-6h','0 20 2,8,14,20 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/report/product/amzProductRefresh/refresh',b'1',NOW()),
('AEC-SYNC-PRICE','aec-sync','#',5,'价格拉取-6h','0 25 2,8,14,20 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/report/product/amzProductRefresh/refreshPrice',b'1',NOW()),
('AEC-SYNC-PRODUCT-TYPE','aec-sync','#',5,'产品类型拉取-每天','0 30 3 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/product/amzProductType/refreshProductType',b'1',NOW()),
('AEC-SYNC-SALES-PLAN','aec-sync','#',5,'销售计划拉取-6h','0 35 2,8,14,20 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/product/salesplan/refreshPlanData',b'1',NOW()),

-- ---------- 4. 财务域 ----------
('AEC-SYNC-FIN','aec-sync','#',5,'财务账期拉取-每天','0 40 4 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/amzFinAccount/refreshAmzFin',b'1',NOW()),
('AEC-SYNC-FIN-DATA','aec-sync','#',5,'结算数据拉取-每天','0 45 4 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/amzFinAccount/refreshAmzFinData',b'1',NOW()),

-- ---------- 5. 报告下载解析（核心高频轮询）----------
('AEC-SYNC-REPORT-REFRESH','aec-sync','#',5,'报告状态同步-15min','0 5/15 * * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/report/refreshReport',b'1',NOW()),
('AEC-SYNC-REPORT-PROCESS','aec-sync','#',6,'报告下载解析入库-15min','0 10/15 * * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/report/processReport',b'1',NOW()),

-- ---------- 6. 报告发起（关键 type，按天；type 来自 t_amz_report_request_type.code）----------
('AEC-SYNC-REQ-ORDER','aec-sync','#',5,'订单报告发起-每天','0 0 5 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/report/requestReport/OrdersByOrderDateReport',b'1',NOW()),
('AEC-SYNC-REQ-INVENTORY','aec-sync','#',5,'库存报告发起-每天','0 5 5 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/report/requestReport/InventoryReport',b'1',NOW()),
('AEC-SYNC-REQ-SETTLEMENT','aec-sync','#',5,'结算报告发起-每天','0 10 5 * * ?','','','','','http://127.0.0.1:8080/amazon/api/v1/report/requestReport/SettlementReport',b'1',NOW()),

-- ---------- 7. 广告域（T+1 为主）----------
('AEC-SYNC-ADV-REQ','aec-sync','#',5,'广告报告发起-每天','0 0 6 * * ?','','','','','http://127.0.0.1:8080/amazonadv/api/v1/advschedule/requestReport',b'1',NOW()),
('AEC-SYNC-ADV-READ','aec-sync','#',5,'广告报告下载解析-30min','0 15/30 * * * ?','','','','','http://127.0.0.1:8080/amazonadv/api/v1/advschedule/readReport',b'1',NOW()),
('AEC-SYNC-ADV-SNAPSHOT','aec-sync','#',5,'广告结构快照发起-每天','0 15 6 * * ?','','','','','http://127.0.0.1:8080/amazonadv/api/v1/advschedule/requestSnapshot',b'1',NOW()),
('AEC-SYNC-ADV-READSNAP','aec-sync','#',5,'广告结构快照解析-30min','0 25/30 * * * ?','','','','','http://127.0.0.1:8080/amazonadv/api/v1/advschedule/readSnapshot',b'1',NOW()),
('AEC-SYNC-ADV-PORTFOLIO','aec-sync','#',5,'广告组合/发票拉取-每天','0 30 6 * * ?','','','','','http://127.0.0.1:8080/amazonadv/api/v1/advschedule/refreshListPortfolios',b'1',NOW()),
('AEC-SYNC-ADV-SUMMARY','aec-sync','#',5,'广告汇总刷新-2h','0 40 1,3,5,7,9,11,13,15,17,19,21,23 * * ?','','','','','http://127.0.0.1:8080/amazonadv/api/v1/advschedule/refreshSummaryAll',b'1',NOW());

-- 统计
SELECT COUNT(*) AS 'AEC调度任务总数(默认暂停)' FROM t_sys_quartz_task WHERE name LIKE 'AEC-SYNC-%';

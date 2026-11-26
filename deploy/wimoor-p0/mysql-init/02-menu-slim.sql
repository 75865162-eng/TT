-- 菜单收敛：按《菜单href一二三级对照》白名单 + 条条4入口保留，其余隐藏
-- 生成 2026-11-26；幂等，可重复执行；restore 后自动生效
SET NAMES utf8mb4;
UPDATE db_admin.t_sys_menu SET visible=1 WHERE id>0;
-- 隐藏白名单外菜单（56 项：订制/计算器/VAT/台账/海外仓/chelsea定制/旧版重复页等）
UPDATE db_admin.t_sys_menu SET visible=0 WHERE id IN (1,6,21,29,35,36,42,47,48,49,56,71,81,85,94,102,108,111,123,124,163,212,213,220,225,226,1101,2002,2132,2133,2612,4000,4001,4002,6104,10121,66600,66601,66611,66612,101211,666114,1012154,1012158,1012159,1012160,1012161,1012169,1012198,1012199,1012200,1012201,1012202,1012203,1012204,1012205);

-- 条条迁移4入口（幂等补插；旧库快照可能缺失）
INSERT IGNORE INTO db_admin.t_sys_menu (`id`,`name`,`parent_id`,`path`,`component`,`icon`,`sort`,`visible`,`gmt_create`,`gmt_modified`)
VALUES
 (1012206,'AI技能中心',1012171,'/sys/ai/skills','sys/ai/skills/index','Tools',12,1,NOW(),NOW()),
 (1012207,'选品研究',1012171,'/sys/ai/research','sys/ai/research/index','Search',13,1,NOW(),NOW()),
 (1012208,'AI运行历史',1012171,'/sys/ai/history','sys/ai/history/index','Clock',14,1,NOW(),NOW()),
 (1012209,'销量统计',1012171,'/sys/ai/sellfox','sys/ai/sellfox/index','TrendCharts',15,1,NOW(),NOW());
-- 再次确保隐藏集合（补插不影响）
UPDATE db_admin.t_sys_menu SET visible=0 WHERE id IN (1,6,21,29,35,36,42,47,48,49,56,71,81,85,94,102,108,111,123,124,163,212,213,220,225,226,1101,2002,2132,2133,2612,4000,4001,4002,6104,10121,66600,66601,66611,66612,101211,666114,1012154,1012158,1012159,1012160,1012161,1012169,1012198,1012199,1012200,1012201,1012202,1012203,1012204,1012205);
UPDATE db_admin.t_sys_menu SET visible=1 WHERE id IN (1012206,1012207,1012208,1012209);

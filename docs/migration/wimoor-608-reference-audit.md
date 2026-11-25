# Wimoor 608 个清单外页面 · 被引用审计报告

> 扫描口径：解析 `wimoor-ui/src` 下全部 `.js/.ts/.vue` 的 `import / require / 动态 import`，
> 归一化到 `@/views/*` 目标；叠加 4 个静态路由模块 + `router/index.js` 静态组件；
> 再比对 `t_sys_menu.sql` 的 component 字段。待删页面 = 734 全量 − 126 菜单白名单 = 608。

> 分类：**🔴 静态路由** / **🟠 被保留页依赖** / **🟡 SQL 隐藏菜单** 归为「需谨慎」；**🟢 无在用引用** 归为「可安全删除」。

## 汇总

| 分类 | 数量 | 判定 |
| --- | ---: | --- |
| 🔴 静态路由引用（删除会 404，必须保留） | 61 | |
| 🟠 被保留/在用页面 import（删除会编译报错/功能缺失） | 134 | |
| 🟡 SQL 菜单仍配置（隐藏菜单，删前需确认是否恢复） | 24 | |
| 🟢 可安全删除（无在用引用） | 389 | |
| **合计** | **608** | |

> 说明：🟢 中「仅被待删页面引用」的页面，须与其引用方**同批删除**；本报告的 SAFE 已排除任何来自白名单页面/静态路由/在用视图的引用。


## 🔴 静态路由引用（删除会 404，必须保留）（61 个）

| 页面 | 引用来源 / 原因 |
| --- | --- |
| `amazon/advertisement/manager/components/hsa/create` | 静态路由引用: router/modules/amazon.js |
| `amazon/advertisement/manager/components/sd/create` | 静态路由引用: router/modules/amazon.js |
| `amazon/advertisement/manager/components/sp/create` | 静态路由引用: router/modules/amazon.js |
| `amazon/listing/product_edit/index` | 静态路由引用: router/modules/amazon.js |
| `amazon/payment/costSharing/components/create` | 静态路由引用: router/modules/amazon.js |
| `amazon/payment/jobCosting/components/edit` | 静态路由引用: router/modules/amazon.js |
| `amazon/profit/config/components/edit_plan` | 静态路由引用: router/modules/amazon.js |
| `amazon/storeAuth/advResult/index` | 静态路由引用: router/modules/sysRouter.js |
| `amazon/storeAuth/authResult/index` | 静态路由引用: router/modules/sysRouter.js |
| `dashboard/index` | 静态路由引用: router/index.js |
| `erp/baseinfo/material/details/index` | 静态路由引用: router/modules/erp.js |
| `erp/baseinfo/material/editinfo/index` | 静态路由引用: router/modules/erp.js |
| `erp/baseinfo/material/photoupload/index` | 静态路由引用: router/modules/erp.js |
| `erp/baseinfo/supplier/components/detail` | 静态路由引用: router/modules/erp.js |
| `erp/finance/alibabaSettlement/index` | 静态路由引用: router/modules/erp.js |
| `erp/finance/paymentRequest/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/purchase/change/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/purchase/change/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/purchase/orders/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/purchase/plan_amz/plan_submit/index` | 静态路由引用: router/modules/erp.js |
| `erp/purchase/process/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/purchase/process/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/ship/orderblank/index` | 静态路由引用: router/modules/erp.js, router/modules/sysRouter.js |
| `erp/ship/quota/index` | 静态路由引用: router/modules/erp.js |
| `erp/ship/ship_plan/plan_submit/eu_split` | 静态路由引用: router/modules/erp.js |
| `erp/ship/ship_plan/plan_submit/index` | 静态路由引用: router/modules/erp.js |
| `erp/ship/shipment_add/approve/index` | 静态路由引用: router/modules/amazon.js |
| `erp/ship/shipment_add/create/index` | 静态路由引用: router/modules/amazon.js |
| `erp/ship/shipment_handing/list/components/async` | 静态路由引用: router/modules/erp.js |
| `erp/ship/shipment_handing/shipstep/index` | 静态路由引用: router/modules/erp.js |
| `erp/ship/transportation/details_transportation` | 静态路由引用: router/modules/erp.js |
| `erp/ship/transportation/edit_transportation` | 静态路由引用: router/modules/erp.js |
| `erp/shipv2/quote/price` | 静态路由引用: router/modules/sysRouter.js |
| `erp/shipv2/shipment_add/approve/index` | 静态路由引用: router/modules/amazon.js |
| `erp/shipv2/shipment_add/create/index` | 静态路由引用: router/modules/amazon.js |
| `erp/shipv2/shipment_add/shipstep/four_end` | 静态路由引用: router/modules/erp.js |
| `erp/shipv2/shipment_add/shipstep/one_pick` | 静态路由引用: router/modules/erp.js |
| `erp/shipv2/shipment_add/shipstep/placement_confirm` | 静态路由引用: router/modules/erp.js |
| `erp/shipv2/shipment_add/shipstep/three_ship` | 静态路由引用: router/modules/erp.js |
| `erp/shipv2/shipment_add/shipstep/two_box` | 静态路由引用: router/modules/erp.js |
| `erp/shipv2/shipment_handing/shipstep/index_tab` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/exchange/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/exchange/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/inbound/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/inbound/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/outbound/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/outbound/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/overseas/stock/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/overseas/stock/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/stocktake/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/stocktake/components/details` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/transfer/components/create` | 静态路由引用: router/modules/erp.js |
| `erp/warehouse/transfer/components/details` | 静态路由引用: router/modules/erp.js |
| `sys/Login` | 静态路由引用: router/modules/sysRouter.js |
| `sys/Register` | 静态路由引用: router/modules/sysRouter.js |
| `sys/SSOLogin` | 静态路由引用: router/modules/sysRouter.js |
| `sys/blank/index` | 静态路由引用: router/index.js |
| `sys/gen/editTable` | 静态路由引用: router/modules/finance.js |
| `sys/userCenter/components/buypackage` | 静态路由引用: router/modules/erp.js |
| `sys/userCenter/index` | 静态路由引用: router/index.js |
| `sys/userCenter/resetPassword` | 静态路由引用: router/modules/sysRouter.js |

## 🟠 被保留/在用页面 import（删除会编译报错/功能缺失）（134 个）

| 页面 | 引用来源 / 原因 |
| --- | --- |
| `amazon/address/components/editdialog` | 被保留/在用页面引用: views/amazon/address/index.vue |
| `amazon/address/components/table` | 被保留/在用页面引用: views/amazon/address/index.vue |
| `amazon/advertisement/manager/components/common/ad_tree` | 被保留/在用页面引用: views/amazon/advertisement/manager/index.vue |
| `amazon/advertisement/manager/components/common/schedule_plan` | 被保留/在用页面引用: views/amazon/advertisement/rules/index.vue |
| `amazon/advertisement/operation_record/components/detail` | 被保留/在用页面引用: views/amazon/advertisement/operation_record/index.vue |
| `amazon/advertisement/operation_record/components/remarks_dialog` | 被保留/在用页面引用: views/amazon/advertisement/operation_record/index.vue |
| `amazon/advertisement/overview/components/adFunnel` | 被保留/在用页面引用: views/amazon/advertisement/overview/index.vue |
| `amazon/advertisement/overview/components/adStatistics` | 被保留/在用页面引用: views/amazon/advertisement/overview/index.vue |
| `amazon/advertisement/overview/components/indicator` | 被保留/在用页面引用: views/amazon/advertisement/overview/index.vue |
| `amazon/advertisement/overview/components/indicator_detail` | 被保留/在用页面引用: views/amazon/advertisement/overview/index.vue |
| `amazon/advertisement/overview/components/roasRank` | 被保留/在用页面引用: views/amazon/advertisement/overview/index.vue |
| `amazon/listing/common/goods_deatils` | 被保留/在用页面引用: views/amazon/sales/forecast/index.vue, views/erp/purchase/plan_amz/index.vue, views/erp/ship/ship_plan/index.vue |
| `amazon/listing/common/group` | 被保留/在用页面引用: views/amazon/listing/products/index.vue, views/amazon/payment/performanpce/index.vue, views/amazon/payment/profitReport/index.vue |
| `amazon/listing/common/group_only` | 被保留/在用页面引用: views/amazon/listing/product_customs/index.vue |
| `amazon/listing/common/salechart` | 被保留/在用页面引用: views/amazon/sales/forecast/index.vue, views/erp/purchase/plan_amz/index.vue, views/erp/ship/ship_plan/index.vue |
| `amazon/listing/product_customs/components/prostatus` | 被保留/在用页面引用: views/amazon/listing/product_customs/index.vue |
| `amazon/listing/product_customs/components/refreshProduct` | 被保留/在用页面引用: views/amazon/listing/product_customs/index.vue |
| `amazon/listing/products/components/modifypriceDialog` | 被保留/在用页面引用: views/amazon/listing/products/index.vue |
| `amazon/listing/products/components/prostatus` | 被保留/在用页面引用: views/amazon/listing/products/index.vue |
| `amazon/listing/products/components/refreshProduct` | 被保留/在用页面引用: views/amazon/listing/products/index.vue |
| `amazon/order/order_manager/components/table` | 被保留/在用页面引用: views/amazon/order/order_manager/index.vue |
| `amazon/order/refund/components/header` | 被保留/在用页面引用: views/amazon/order/refund/index.vue |
| `amazon/order/refund/components/summary_chart` | 被保留/在用页面引用: views/amazon/order/refund/index.vue |
| `amazon/order/refund/components/summary_sku` | 被保留/在用页面引用: views/amazon/order/refund/index.vue |
| `amazon/order/refund/components/table` | 被保留/在用页面引用: views/amazon/order/refund/index.vue |
| `amazon/order/remove/components/header` | 被保留/在用页面引用: views/amazon/order/remove/index.vue |
| `amazon/order/remove/components/table` | 被保留/在用页面引用: views/amazon/order/remove/index.vue |
| `amazon/order/today/components/header` | 被保留/在用页面引用: views/amazon/order/today/index.vue |
| `amazon/payment/common/refresh_data_dialog` | 被保留/在用页面引用: views/amazon/payment/performanpce/index.vue, views/amazon/payment/profitReport/index.vue |
| `amazon/payment/finance/index` | 被保留/在用页面引用: views/amazon/payment/profitReport/profitGroup/index.vue |
| `amazon/payment/monthReport/index` | 被保留/在用页面引用: views/amazon/payment/profitReport/profitGroup/index.vue |
| `amazon/payment/profitReport/components/accCalc_dialog` | 被保留/在用页面引用: views/amazon/payment/profitReport/index.vue |
| `amazon/payment/profitReport/components/formula` | 被保留/在用页面引用: views/amazon/payment/performanpce/index.vue, views/amazon/payment/profitReport/index.vue |
| `amazon/payment/profitReport/profitGroup/components/profitGroup` | 被保留/在用页面引用: views/amazon/payment/profitReport/profitGroup/index.vue |
| `amazon/payment/record/chart` | 被保留/在用页面引用: views/amazon/payment/record/index.vue |
| `amazon/payment/record/records` | 被保留/在用页面引用: views/amazon/payment/record/index.vue |
| `amazon/payment/settlement_sku/settlement` | 被保留/在用页面引用: views/amazon/payment/settlement_sku/index.vue |
| `amazon/payment/settlement_sku/skutable` | 被保留/在用页面引用: views/amazon/payment/settlement_sku/index.vue |
| `amazon/payment/summaryMonth/index` | 被保留/在用页面引用: views/amazon/payment/record/index.vue |
| `amazon/profit/fbafee/component/fba` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/fbasipp` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/inplace` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/inplacefee` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/referralfee` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/storagefee` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/tier` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/tierformat` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/profit/fbafee/component/tierweight` | 被保留/在用页面引用: views/amazon/profit/fbafee/index.vue |
| `amazon/report/fba_fee/header` | 被保留/在用页面引用: views/amazon/report/fba_fee/index.vue |
| `amazon/report/performance/coupon/index` | 被保留/在用页面引用: views/amazon/report/performance/index.vue |
| `amazon/report/performance/orderfeedback/header` | 被保留/在用页面引用: views/amazon/report/performance/orderfeedback/index.vue |
| `amazon/report/performance/promotions/index` | 被保留/在用页面引用: views/amazon/report/performance/index.vue |
| `amazon/report/performance/seller/header` | 被保留/在用页面引用: views/amazon/report/performance/seller/index.vue |
| `amazon/report/ship/fee_summary_new/detail_dialog` | 被保留/在用页面引用: views/amazon/report/ship/fee_summary_new/index.vue |
| `amazon/report/shipv2/ship_summary/component/piechart` | 被保留/在用页面引用: views/amazon/report/shipv2/ship_summary/index.vue |
| `amazon/report/storage_fee/index_fee` | 被保留/在用页面引用: views/amazon/report/storage_fee/index.vue |
| `amazon/report/storage_fee/index_long` | 被保留/在用页面引用: views/amazon/report/storage_fee/index.vue |
| `amazon/sales/forecast/components/sale_adjust_dialog` | 被保留/在用页面引用: views/amazon/sales/forecast/index.vue, views/erp/purchase/plan_amz/index.vue, views/erp/ship/ship_plan/index.vue |
| `amazon/sales/forecast/components/sale_adjust_upload_dialog` | 被保留/在用页面引用: views/amazon/sales/forecast/index.vue |
| `erp/baseinfo/material/components/header` | 被保留/在用页面引用: views/erp/baseinfo/material/consumable.vue, views/erp/baseinfo/material/index.vue, views/erp/baseinfo/material/package.vue |
| `erp/baseinfo/material/components/record` | 被保留/在用页面引用: views/erp/baseinfo/material/consumable.vue, views/erp/baseinfo/material/package.vue |
| `erp/baseinfo/material/materialDialog` | 被保留/在用页面引用: views/amazon/listing/relation_msku/index.vue, views/sys/notepad/index.vue |
| `erp/baseinfo/supplier/components/table` | 被保留/在用页面引用: views/erp/baseinfo/supplier/index.vue |
| `erp/components/assembly_dialog` | 被保留/在用页面引用: views/erp/purchase/plan_amz/index.vue, views/erp/ship/ship_plan/index.vue, views/erp/warehouse/inventory/report/base/index.vue |
| `erp/components/download_dialog` | 被保留/在用页面引用: views/erp/purchase/report/time/index.vue |
| `erp/finance/account/components/account_overview` | 被保留/在用页面引用: views/erp/finance/account/index.vue |
| `erp/finance/account/components/alibaba_settlement_tab` | 被保留/在用页面引用: views/erp/finance/account/index.vue |
| `erp/finance/account/components/bookkeeping` | 被保留/在用页面引用: views/erp/finance/account/index.vue |
| `erp/purchase/consumable/components/consumable_material` | 被保留/在用页面引用: views/erp/purchase/consumable/index.vue |
| `erp/purchase/orders/components/auto_price_dialog` | 被保留/在用页面引用: views/erp/purchase/orders/index.vue |
| `erp/purchase/orders/components/printip` | 被保留/在用页面引用: views/erp/purchase/orders/index.vue |
| `erp/purchase/orders/components/remark_dialog` | 被保留/在用页面引用: views/erp/purchase/orders/index.vue |
| `erp/purchase/orders/components/shipfee_dialog` | 被保留/在用页面引用: views/erp/purchase/orders/index.vue |
| `erp/purchase/plan_amz/components/expand_table` | 被保留/在用页面引用: views/erp/purchase/plan_amz/index.vue |
| `erp/purchase/plan_amz/components/header` | 被保留/在用页面引用: views/erp/purchase/plan_amz/index.vue |
| `erp/purchase/report/receive/components/storageDetail_table` | 被保留/在用页面引用: views/erp/purchase/report/receive/index.vue |
| `erp/ship/ship_plan/components/expand_table` | 被保留/在用页面引用: views/erp/ship/ship_plan/index.vue |
| `erp/ship/ship_plan/components/header` | 被保留/在用页面引用: views/erp/ship/ship_plan/index.vue |
| `erp/ship/ship_plan/components/remarks_dialog` | 被保留/在用页面引用: views/erp/purchase/plan_amz/index.vue, views/erp/ship/ship_plan/index.vue |
| `erp/ship/ship_plan/components/ship_record` | 被保留/在用页面引用: views/erp/purchase/plan_amz/index.vue, views/erp/ship/ship_plan/index.vue |
| `erp/ship/ship_plan/components/split_plan_dialog` | 被保留/在用页面引用: views/erp/ship/ship_plan/index.vue |
| `erp/ship/shipment_handing/list/components/header` | 被保留/在用页面引用: views/erp/ship/shipment_handing/list/index.vue |
| `erp/ship/shipment_handing/list/components/table` | 被保留/在用页面引用: views/erp/ship/shipment_handing/list/index.vue |
| `erp/ship/transportation/components/compare` | 被保留/在用页面引用: views/erp/ship/transportation/index.vue |
| `erp/ship/transportation/components/list` | 被保留/在用页面引用: views/erp/ship/transportation/index.vue |
| `erp/shipv2/quote/components/channel_type` | 被保留/在用页面引用: views/erp/shipv2/quote/channel.vue |
| `erp/shipv2/quote/components/create_dialog` | 被保留/在用页面引用: views/erp/shipv2/quote/setting.vue |
| `erp/shipv2/quote/components/price_dialog` | 被保留/在用页面引用: views/erp/shipv2/quote/order.vue |
| `erp/shipv2/quote/overview` | 被保留/在用页面引用: views/erp/shipv2/quote/order.vue |
| `erp/shipv2/quote/supplier` | 被保留/在用页面引用: views/erp/shipv2/quote/order.vue, views/erp/shipv2/quote/quote.vue |
| `erp/shipv2/shipment_add/list/components/header` | 被保留/在用页面引用: views/erp/shipv2/shipment_add/list/index.vue |
| `erp/shipv2/shipment_add/list/components/table` | 被保留/在用页面引用: views/erp/shipv2/shipment_add/list/index.vue |
| `erp/shipv2/shipment_handing/list/components/header` | 被保留/在用页面引用: views/erp/shipv2/shipment_handing/list/index.vue |
| `erp/shipv2/shipment_handing/list/components/table` | 被保留/在用页面引用: views/erp/shipv2/shipment_handing/list/index.vue |
| `erp/shipv2/shipment_handing/shipstep/components/customs_declaration_xml` | 被保留/在用页面引用: views/erp/shipv2/shipment_handing/shipstep/components/customs_summary.vue |
| `erp/shipv2/shipment_handing/shipstep/components/customs_inventory_xml` | 被保留/在用页面引用: views/erp/shipv2/shipment_handing/shipstep/components/customs_summary.vue |
| `erp/shipv2/shipment_handing/shipstep/components/customs_order_xml` | 被保留/在用页面引用: views/erp/shipv2/shipment_handing/shipstep/components/customs_summary.vue |
| `erp/thirdparty/components/edit_dialog` | 被保留/在用页面引用: views/erp/thirdparty/index.vue |
| `erp/warehouse/base/components/childrenlist` | 被保留/在用页面引用: views/erp/warehouse/base/index.vue |
| `erp/warehouse/base/components/parentlist` | 被保留/在用页面引用: views/erp/warehouse/base/index.vue |
| `erp/warehouse/base/components/thirdparty_oversea` | 被保留/在用页面引用: views/erp/warehouse/base/index.vue |
| `erp/warehouse/base/warehouseDialog` | 被保留/在用页面引用: views/erp/warehouse/address/index.vue |
| `erp/warehouse/shelf/base/shelf_tree` | 被保留/在用页面引用: views/erp/warehouse/shelf/create/index.vue, views/erp/warehouse/shelf/inv/index.vue |
| `erp/warehouse/shelf/create/components/shelf_card` | 被保留/在用页面引用: views/erp/warehouse/shelf/create/index.vue |
| `erp/warehouse/shelf/create/components/shelf_dialog` | 被保留/在用页面引用: views/erp/warehouse/shelf/create/index.vue |
| `erp/warehouse/shelf/inv/components/shelf_product` | 被保留/在用页面引用: views/erp/warehouse/shelf/inv/index.vue |
| `finance/auxiliary/types/index` | 被保留/在用页面引用: views/finance/auxiliary/items/index.vue |
| `finance/periods/components/periods` | 被保留/在用页面引用: views/finance/periods/index.vue |
| `finance/periods/components/template/feishu_table_bind` | 被保留/在用页面引用: views/sys/feishu/index.vue |
| `finance/periods/components/vouchers` | 被保留/在用页面引用: views/finance/periods/index.vue |
| `finance/report/items/components/report_balance` | 被保留/在用页面引用: views/finance/report/items/index.vue |
| `finance/report/items/components/report_cashflow` | 被保留/在用页面引用: views/finance/report/items/index.vue |
| `finance/report/items/components/report_equity` | 被保留/在用页面引用: views/finance/report/items/index.vue |
| `finance/report/items/components/report_income` | 被保留/在用页面引用: views/finance/report/items/index.vue |
| `finance/report/items/components/report_sheet` | 被保留/在用页面引用: views/finance/report/items/index.vue |
| `finance/report/templates/components/mapping_rules_panel` | 被保留/在用页面引用: views/finance/report/templates/index.vue |
| `finance/report/templates/components/template_items` | 被保留/在用页面引用: views/finance/report/templates/index.vue |
| `finance/vouchers/create/components/ImportVoucherDialog` | 被保留/在用页面引用: views/finance/vouchers/create/index.vue, views/finance/vouchers/list/index.vue |
| `finance/vouchers/create/components/VoucherTemplate` | 被保留/在用页面引用: views/finance/vouchers/create/index.vue |
| `sys/deepseek/components/MarkdownRenderer` | 被保留/在用页面引用: views/sys/deepseek/index.vue |
| `sys/dict/components/DictItem` | 被保留/在用页面引用: views/sys/dict/index.vue |
| `sys/dict/components/DictType` | 被保留/在用页面引用: views/sys/dict/index.vue |
| `sys/gen/importTable` | 被保留/在用页面引用: views/sys/gen/index.vue |
| `sys/menu/components/Menu` | 被保留/在用页面引用: views/sys/menu/index.vue |
| `sys/menu/components/Perm` | 被保留/在用页面引用: views/sys/menu/index.vue |
| `sys/movedata/components/backup` | 被保留/在用页面引用: views/sys/movedata/index.vue |
| `sys/movedata/components/movetable` | 被保留/在用页面引用: views/sys/movedata/index.vue |
| `sys/movedata/components/settlement` | 被保留/在用页面引用: views/sys/movedata/index.vue |
| `sys/notepad/content_dialog` | 被保留/在用页面引用: views/erp/ship/ship_plan/index.vue |
| `sys/ruleAuth/components/menu_list` | 被保留/在用页面引用: views/sys/ruleAuth/index.vue |
| `sys/ruleAuth/components/page_list` | 被保留/在用页面引用: views/sys/ruleAuth/index.vue |
| `sys/ruleAuth/components/role_list` | 被保留/在用页面引用: views/sys/ruleAuth/index.vue |
| `sys/tag/components/sysTagGroups` | 被保留/在用页面引用: views/sys/tag/index.vue |
| `sys/tag/components/sysTags` | 被保留/在用页面引用: views/sys/tag/index.vue |

## 🟡 SQL 菜单仍配置（隐藏菜单，删前需确认是否恢复）（24 个）

| 页面 | 引用来源 / 原因 |
| --- | --- |
| `amazon/listing/price_adjust/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/order/fbm/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/payment/transaction/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/profit/calculator/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/profit/config/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/report/ship/detail/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/report/ship/fee_summary/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/report/ship/ship_summary/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/temu/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `amazon/transparency/manager/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `erp/ship/shipment_add/list/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `erp/warehouse/fee/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `erp/warehouse/overseas/inv/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `erp/warehouse/overseas/order/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `erp/warehouse/overseas/plan/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `erp/warehouse/overseas/simpleplan/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `erp/warehouse/overseas/stock/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `finance/config/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `finance/ledger/inventory/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `finance/ledger/invoice/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `finance/ledger/purchase/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `finance/ledger/supplier/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `sys/fields/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |
| `sys/temu/index` | SQL t_sys_menu.component 仍配置（visible=0/不可达菜单） |

## 🟢 可安全删除（无在用引用）（389 个）


### `erp` — 165 个

- `erp/baseinfo/material/consumableDialog`
- `erp/baseinfo/material/details/components/listinfo`
- `erp/baseinfo/material/editinfo/components/assemble_info`
- `erp/baseinfo/material/editinfo/components/base`
- `erp/baseinfo/material/editinfo/components/consumables`
- `erp/baseinfo/material/editinfo/components/logistics`
- `erp/baseinfo/material/editinfo/components/parent_info`
- `erp/baseinfo/material/editinfo/components/purchase`
- `erp/baseinfo/material/editinfo/components/purchase_price_dialog`
- `erp/baseinfo/material/editinfo/components/specs`
- `erp/baseinfo/supplier/supplier_dialog`
- `erp/components/shelfInvOpt`
- `erp/finance/account/components/account_delete_dialog`
- `erp/finance/account/components/account_edit_dialog`
- `erp/finance/account/components/finItem`
- `erp/finance/account/components/finRecord`
- `erp/finance/account/components/lineChart`
- `erp/finance/account/components/payacc_index_dialog`
- `erp/finance/account/components/paymethod_index_dialog`
- `erp/finance/account/components/pieChart`
- `erp/finance/account/components/table`
- `erp/finance/alibabaSettlement/components/order`
- `erp/finance/alibabaSettlement/components/pay`
- `erp/finance/alibabaSettlement/components/returnorder`
- `erp/finance/alibabaSettlement/components/returnpay`
- `erp/finance/alibabaSettlement/components/settled`
- `erp/finance/alibabaSettlement/components/unsettled`
- `erp/finance/inventoryValue/components/fba`
- `erp/finance/inventoryValue/components/self`
- `erp/finance/paymentRequest/components/addPaymentDialog`
- `erp/finance/paymentRequest/components/table`
- `erp/purchase/change/components/header`
- `erp/purchase/components/preplan`
- `erp/purchase/open1688/order/open1688order`
- `erp/purchase/orders/components/arrival_record`
- `erp/purchase/orders/components/assembly`
- `erp/purchase/orders/components/change_records`
- `erp/purchase/orders/components/deliverydate`
- `erp/purchase/orders/components/details`
- `erp/purchase/orders/components/logistics_record`
- `erp/purchase/orders/components/order_record`
- `erp/purchase/orders/components/pay_record`
- `erp/purchase/orders/components/payment`
- `erp/purchase/orders/components/receipt`
- `erp/purchase/orders/components/skuTable`
- `erp/purchase/orders/components/sku_table_inner_dailog`
- `erp/purchase/orders/components/table`
- `erp/purchase/orders/order_dialog`
- `erp/purchase/orders/records`
- `erp/purchase/plan_amz/components/plan_select`
- `erp/purchase/plan_amz/plan_submit/detail`
- `erp/purchase/process/components/header`
- `erp/ship/quota/components/shelfproduct`
- `erp/ship/ship_plan/components/afterplan_dialog`
- `erp/ship/ship_plan/components/check_data_dialog`
- `erp/ship/ship_plan/components/delivery_dialog`
- `erp/ship/ship_plan/components/shipmarket_rank`
- `erp/ship/ship_plan/plan_submit/market_plan`
- `erp/ship/shipment_add/approve/components/list`
- `erp/ship/shipment_add/approve/components/split_dialog`
- `erp/ship/shipment_add/approve/components/table`
- `erp/ship/shipment_add/create/components/form`
- `erp/ship/shipment_add/create/components/mateiralDialog`
- `erp/ship/shipment_add/list/components/header`
- `erp/ship/shipment_add/list/components/table`
- `erp/ship/shipment_handing/list/components/asyncdailog`
- `erp/ship/shipment_handing/list/components/transinfo`
- `erp/ship/shipment_handing/list/components/transnumber`
- `erp/ship/shipment_handing/shipstep/components/four_receive`
- `erp/ship/shipment_handing/shipstep/components/one_picking`
- `erp/ship/shipment_handing/shipstep/components/print_dialog`
- `erp/ship/shipment_handing/shipstep/components/shipment_info`
- `erp/ship/shipment_handing/shipstep/components/shipment_operator`
- `erp/ship/shipment_handing/shipstep/components/three_deliver`
- `erp/ship/shipment_handing/shipstep/components/two_box`
- `erp/ship/transportation/components/trans_type_dialog`
- `erp/shipv2/quote/components/channel_dialog`
- `erp/shipv2/quote/components/protocol_dialog`
- `erp/shipv2/quote/components/reset_password_dialog`
- `erp/shipv2/quote/purchase/quote`
- `erp/shipv2/shipment_add/approve/components/list`
- `erp/shipv2/shipment_add/approve/components/split_dialog`
- `erp/shipv2/shipment_add/approve/components/table`
- `erp/shipv2/shipment_add/create/components/form`
- `erp/shipv2/shipment_add/create/components/mateiralDialog`
- `erp/shipv2/shipment_add/list/components/async`
- `erp/shipv2/shipment_add/list/components/asyncdailog`
- `erp/shipv2/shipment_add/shipstep/components/box_analysis_dialog`
- `erp/shipv2/shipment_add/shipstep/components/box_plan`
- `erp/shipv2/shipment_add/shipstep/components/box_table`
- `erp/shipv2/shipment_add/shipstep/components/box_table_case`
- `erp/shipv2/shipment_add/shipstep/components/consumable`
- `erp/shipv2/shipment_add/shipstep/components/footer`
- `erp/shipv2/shipment_add/shipstep/components/header`
- `erp/shipv2/shipment_add/shipstep/components/operation`
- `erp/shipv2/shipment_add/shipstep/components/planCancel`
- `erp/shipv2/shipment_add/shipstep/components/planCreate`
- `erp/shipv2/shipment_add/shipstep/components/planShipped`
- `erp/shipv2/shipment_add/shipstep/components/pre`
- `erp/shipv2/shipment_add/shipstep/components/table`
- `erp/shipv2/shipment_add/shipstep/components/trans`
- `erp/shipv2/shipment_handing/list/components/quoteDialog`
- `erp/shipv2/shipment_handing/list/components/transinfo`
- `erp/shipv2/shipment_handing/list/components/transnumber`
- `erp/shipv2/shipment_handing/shipstep/components/customs_clearance`
- `erp/shipv2/shipment_handing/shipstep/components/customs_data`
- `erp/shipv2/shipment_handing/shipstep/components/customs_declaration`
- `erp/shipv2/shipment_handing/shipstep/components/customs_picking`
- `erp/shipv2/shipment_handing/shipstep/components/destination`
- `erp/shipv2/shipment_handing/shipstep/components/four_receive`
- `erp/shipv2/shipment_handing/shipstep/components/ship_box`
- `erp/shipv2/shipment_handing/shipstep/components/ship_box_case`
- `erp/shipv2/shipment_handing/shipstep/components/shipment_info`
- `erp/shipv2/shipment_handing/shipstep/components/shipment_operator`
- `erp/shipv2/shipment_handing/shipstep/components/three_deliver`
- `erp/shipv2/shipment_handing/shipstep/components/two_box`
- `erp/shipv2/shipment_handing/shipstep/index`
- `erp/warehouse/base/components/edit`
- `erp/warehouse/base/components/thirdparty/k5`
- `erp/warehouse/base/components/thirdparty/ops`
- `erp/warehouse/base/components/thirdparty/xl`
- `erp/warehouse/base/components/thirdparty/yc`
- `erp/warehouse/base/components/thirdparty_bind_dialog`
- `erp/warehouse/exchange/components/header`
- `erp/warehouse/fee/detail_dialog`
- `erp/warehouse/inbound/components/create_dialog`
- `erp/warehouse/inbound/components/table`
- `erp/warehouse/outbound/components/create_dialog`
- `erp/warehouse/outbound/components/table`
- `erp/warehouse/overseas/inv/components/create`
- `erp/warehouse/overseas/inv/components/inv_header`
- `erp/warehouse/overseas/inv/components/inv_his_dialog`
- `erp/warehouse/overseas/inv/components/invoice_dialog`
- `erp/warehouse/overseas/inv/components/plan_create_dialog`
- `erp/warehouse/overseas/order/components/create`
- `erp/warehouse/overseas/order/components/invoice_dialog`
- `erp/warehouse/overseas/order/components/invoice_view_dialog`
- `erp/warehouse/overseas/order/components/platform`
- `erp/warehouse/overseas/order/components/sales_header`
- `erp/warehouse/overseas/plan/components/PurchaseDialog`
- `erp/warehouse/overseas/plan/components/ShipDialog`
- `erp/warehouse/overseas/plan/components/arrival_dialog`
- `erp/warehouse/overseas/plan/components/delivery_dialog`
- `erp/warehouse/overseas/plan/components/expand_table`
- `erp/warehouse/overseas/plan/components/header`
- `erp/warehouse/overseas/plan/components/plan_select`
- `erp/warehouse/overseas/plan/components/salechart`
- `erp/warehouse/overseas/plan/components/ship_record`
- `erp/warehouse/overseas/plan/plan_submit/detail`
- `erp/warehouse/overseas/plan/plan_submit/index`
- `erp/warehouse/overseas/simpleplan/components/plan_create_dialog`
- `erp/warehouse/overseas/simpleplan/components/plan_detail_dialog`
- `erp/warehouse/overseas/simpleplan/components/plan_header`
- `erp/warehouse/overseas/simpleplan/components/platform`
- `erp/warehouse/overseas/stock/components/box_table`
- `erp/warehouse/overseas/stock/components/create_dialog`
- `erp/warehouse/overseas/stock/components/header`
- `erp/warehouse/overseas/stock/components/logistics`
- `erp/warehouse/overseas/stock/components/split_dialog`
- `erp/warehouse/record/components/table`
- `erp/warehouse/stocktake/components/create_dialog`
- `erp/warehouse/stocktake/components/sku_inventory`
- `erp/warehouse/stocktake/components/table`
- `erp/warehouse/stocktake/components/uploadDialog`
- `erp/warehouse/transfer/components/header`

### `amazon` — 162 个

- `amazon/advertisement/manager/components/common/ad_chart`
- `amazon/advertisement/manager/components/common/ad_order_cycle`
- `amazon/advertisement/manager/components/common/data_filter`
- `amazon/advertisement/manager/components/common/date_select`
- `amazon/advertisement/manager/components/common/portfolios_plane`
- `amazon/advertisement/manager/components/hsa/components/adcreative`
- `amazon/advertisement/manager/components/hsa/components/bidding`
- `amazon/advertisement/manager/components/hsa/components/bidding_dialog`
- `amazon/advertisement/manager/components/hsa/components/bidding_tactics_sb`
- `amazon/advertisement/manager/components/hsa/components/bidding_tactics_sp`
- `amazon/advertisement/manager/components/hsa/components/bidding_type`
- `amazon/advertisement/manager/components/hsa/components/negative_keywords`
- `amazon/advertisement/manager/components/hsa/components/negative_target`
- `amazon/advertisement/manager/components/hsa/components/product_add`
- `amazon/advertisement/manager/components/hsa/components/target`
- `amazon/advertisement/manager/components/hsa/components/target_keyword`
- `amazon/advertisement/manager/components/hsa/components/target_product`
- `amazon/advertisement/manager/components/hsa/listAdgroups`
- `amazon/advertisement/manager/components/hsa/listCampNegativaKeywords`
- `amazon/advertisement/manager/components/hsa/listCampNegativaTarget`
- `amazon/advertisement/manager/components/hsa/listCampaigns`
- `amazon/advertisement/manager/components/hsa/listKeywords`
- `amazon/advertisement/manager/components/hsa/listKeywordsQuery`
- `amazon/advertisement/manager/components/hsa/listNegativaKeywords`
- `amazon/advertisement/manager/components/hsa/listNegativaTarget`
- `amazon/advertisement/manager/components/hsa/listProductAds`
- `amazon/advertisement/manager/components/hsa/listPurchaseProductAds`
- `amazon/advertisement/manager/components/hsa/listTarget`
- `amazon/advertisement/manager/components/hsa/listTargetQuery`
- `amazon/advertisement/manager/components/sd/components/adcreative`
- `amazon/advertisement/manager/components/sd/components/bidding`
- `amazon/advertisement/manager/components/sd/components/bidding_dialog`
- `amazon/advertisement/manager/components/sd/components/bidding_tactics_sp`
- `amazon/advertisement/manager/components/sd/components/bidding_type`
- `amazon/advertisement/manager/components/sd/components/negative_keywords`
- `amazon/advertisement/manager/components/sd/components/negative_target`
- `amazon/advertisement/manager/components/sd/components/product_add`
- `amazon/advertisement/manager/components/sd/components/target`
- `amazon/advertisement/manager/components/sd/components/target_product`
- `amazon/advertisement/manager/components/sd/listAdgroups`
- `amazon/advertisement/manager/components/sd/listCampaigns`
- `amazon/advertisement/manager/components/sd/listNegativaTarget`
- `amazon/advertisement/manager/components/sd/listProductAds`
- `amazon/advertisement/manager/components/sd/listPurchaseProductAds`
- `amazon/advertisement/manager/components/sd/listTarget`
- `amazon/advertisement/manager/components/sp/components/bidding`
- `amazon/advertisement/manager/components/sp/components/bidding_dialog`
- `amazon/advertisement/manager/components/sp/components/bidding_tactics_sp`
- `amazon/advertisement/manager/components/sp/components/bidding_type`
- `amazon/advertisement/manager/components/sp/components/negative_keywords`
- `amazon/advertisement/manager/components/sp/components/negative_target`
- `amazon/advertisement/manager/components/sp/components/product_add`
- `amazon/advertisement/manager/components/sp/components/target`
- `amazon/advertisement/manager/components/sp/components/target_keyword`
- `amazon/advertisement/manager/components/sp/components/target_product`
- `amazon/advertisement/manager/components/sp/listAdgroups`
- `amazon/advertisement/manager/components/sp/listCampNegativaKeywords`
- `amazon/advertisement/manager/components/sp/listCampNegativaTarget`
- `amazon/advertisement/manager/components/sp/listCampaigns`
- `amazon/advertisement/manager/components/sp/listKeywords`
- `amazon/advertisement/manager/components/sp/listKeywordsQuery`
- `amazon/advertisement/manager/components/sp/listNegativaKeywords`
- `amazon/advertisement/manager/components/sp/listNegativaTarget`
- `amazon/advertisement/manager/components/sp/listProductAds`
- `amazon/advertisement/manager/components/sp/listPurchaseProductAds`
- `amazon/advertisement/manager/components/sp/listTarget`
- `amazon/advertisement/manager/components/sp/listTargetQuery`
- `amazon/advertisement/manager/warn/components/create`
- `amazon/advertisement/manager/warn/index`
- `amazon/inventory/quantity/index`
- `amazon/inventory/worth/index`
- `amazon/listing/analysis/components/data_deatils`
- `amazon/listing/analysis/components/dialog`
- `amazon/listing/common/arrival_dialog`
- `amazon/listing/common/listings`
- `amazon/listing/price_adjust/components/table`
- `amazon/listing/product_customs/components/arrival_dialog`
- `amazon/listing/product_customs/components/batchmodifypriceDialog`
- `amazon/listing/product_customs/components/follow_dialog`
- `amazon/listing/product_customs/components/matching`
- `amazon/listing/product_customs/components/modifypriceDialog`
- `amazon/listing/product_customs/components/profit_details`
- `amazon/listing/product_customs/components/remarks_dialog`
- `amazon/listing/product_customs/components/sale_formula`
- `amazon/listing/product_customs/components/sale_status_dialog`
- `amazon/listing/product_customs/components/sale_status_select`
- `amazon/listing/product_customs/components/table`
- `amazon/listing/product_plus/index`
- `amazon/listing/products/components/arrival_dialog`
- `amazon/listing/products/components/batchmodifypriceDialog`
- `amazon/listing/products/components/fbainvhistory_dialog`
- `amazon/listing/products/components/feedback_dialog`
- `amazon/listing/products/components/follow_dialog`
- `amazon/listing/products/components/matching`
- `amazon/listing/products/components/productAIDialog`
- `amazon/listing/products/components/profit_details`
- `amazon/listing/products/components/remarks_dialog`
- `amazon/listing/products/components/sale_formula`
- `amazon/listing/products/components/sale_status_dialog`
- `amazon/listing/products/components/sale_status_select`
- `amazon/listing/products/components/table`
- `amazon/order/fbm/components/header`
- `amazon/order/fbm/components/invoice`
- `amazon/order/fbm/components/invoice_template`
- `amazon/order/fbm/components/invoice_view`
- `amazon/order/fbm/components/invoice_view_refund`
- `amazon/order/fbm/components/order_detail`
- `amazon/order/fbm/components/table`
- `amazon/order/order_manager/components/header`
- `amazon/order/order_manager/components/invoice`
- `amazon/order/order_manager/components/invoice_template`
- `amazon/order/order_manager/components/invoice_view`
- `amazon/order/order_manager/components/invoice_view_refund`
- `amazon/order/order_manager/components/message`
- `amazon/order/order_manager/components/order_detail`
- `amazon/order/refund/components/skudetail`
- `amazon/order/refund/components/summary_chart_line`
- `amazon/order/refund/components/summary_chart_pie`
- `amazon/order/summary_week_month/components/day`
- `amazon/order/summary_week_month/components/header`
- `amazon/order/summary_week_month/components/month`
- `amazon/order/summary_week_month/index`
- `amazon/payment/costSharing/components/otherFee`
- `amazon/payment/costSharing/components/shareRules`
- `amazon/payment/costSharing/components/table`
- `amazon/payment/costSharing/cost_dialog`
- `amazon/payment/finance/components/month`
- `amazon/payment/finance/components/other_fee_dialog`
- `amazon/payment/finance/components/record`
- `amazon/payment/item_fee/index`
- `amazon/payment/jobCosting/components/resetAddDialog`
- `amazon/payment/jobCosting/components/table`
- `amazon/payment/performanpce/components/monthReport`
- `amazon/payment/performanpce/components/table`
- `amazon/payment/profitReport/table/child`
- `amazon/payment/profitReport/table/table`
- `amazon/payment/record/detail`
- `amazon/payment/record/report`
- `amazon/payment/settlement_sku/account_dialog`
- `amazon/payment/settlement_sku/table`
- `amazon/payment/transaction/chart`
- `amazon/payment/transaction/detail`
- `amazon/payment/transaction/statistics`
- `amazon/profit/calculator/components/country_collapse`
- `amazon/profit/calculator/components/fba_size`
- `amazon/profit/calculator/components/profit_deatils`
- `amazon/profit/config/components/market_fee/form`
- `amazon/report/performance/coupon/header`
- `amazon/report/performance/promotions/header`
- `amazon/report/ship/fee_summary/detail_dialog`
- `amazon/report/ship/ship_fee_record/index`
- `amazon/report/ship/ship_summary/component/piechart`
- `amazon/service/VatInvioce/index`
- `amazon/storeAuth/components/advReport`
- `amazon/storeAuth/components/authadv`
- `amazon/storeAuth/components/authstore`
- `amazon/storeAuth/components/table`
- `amazon/storeAuth/components/taskData`
- `amazon/transparency/manager/components/skuinfo`
- `amazon/transparency/manager/components/task`
- `amazon/transparency/manager/components/tcode`
- `amazon/transparency/manager/components/uploadDialog`

### `finance` — 33 个

- `finance/category/index`
- `finance/config/components/inventoryconfig`
- `finance/config/components/invoiceconfig`
- `finance/config/components/payconfig`
- `finance/config/components/voucherlog`
- `finance/entries/index`
- `finance/ledger/carrier/components/detail-drawer`
- `finance/ledger/carrier/index`
- `finance/ledger/invoice/components/add-invoice-dialog`
- `finance/ledger/invoice/components/detail-drawer`
- `finance/ledger/invoice/components/import-dialog`
- `finance/ledger/invoice/components/invoice-posting-dialog`
- `finance/ledger/invoice/components/posting-dialog`
- `finance/ledger/invoice/components/relation-drawer`
- `finance/ledger/invoice/components/sync-dialog`
- `finance/ledger/purchase/components/pay-dialog`
- `finance/ledger/purchase/components/payment-drawer`
- `finance/ledger/purchase/components/upload-dialog`
- `finance/ledger/supplier/components/detail-drawer`
- `finance/log/index`
- `finance/periods/components/template`
- `finance/periods/components/template-tree`
- `finance/periods/components/template/add_subject_dialog`
- `finance/periods/components/template/amazon_fin_month_report`
- `finance/periods/components/template/feishu_table_record`
- `finance/periods/components/template/feishu_table_record_filter`
- `finance/periods/components/template/other_custom`
- `finance/periods/components/template/period_change_rate`
- `finance/periods/components/template/period_profit_loss_transfer`
- `finance/periods/components/voucher_log_dialog`
- `finance/report/templates/components/template_item_custom_dialog`
- `finance/report/templates/components/template_item_edit`
- `finance/vouchers/create/components/upload_status`

### `sys` — 14 个

- `sys/client/index`
- `sys/feishu/chat/index`
- `sys/fields/components/FieldPreview`
- `sys/fields/components/FieldTable`
- `sys/gen/basicInfoForm`
- `sys/gen/genInfoForm`
- `sys/swagger/amazon`
- `sys/swagger/erp`
- `sys/userCenter/components/accountSafe`
- `sys/userCenter/components/card`
- `sys/userCenter/components/logoff`
- `sys/userCenter/components/package`
- `sys/userCenter/components/unbind`
- `sys/userCenter/components/wechat`

### `dashboard` — 10 个

- `dashboard/components/adchart`
- `dashboard/components/comparechart`
- `dashboard/components/goodsrank`
- `dashboard/components/notice`
- `dashboard/components/personrank`
- `dashboard/components/piechart`
- `dashboard/components/salechart`
- `dashboard/components/service`
- `dashboard/components/stockchart`
- `dashboard/components/storeshow`

### `customized` — 5 个

- `customized/plantool/manplan/index`
- `customized/plantool/pickpay/index`
- `customized/plantool/purchaseplan/index`
- `customized/plantool/salemonth/index`
- `customized/plantool/shipplan/index`

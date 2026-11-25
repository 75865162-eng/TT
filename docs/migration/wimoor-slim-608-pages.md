# Wimoor 清单外页面（瘦身待删除）详细清单

> 口径：`wimoor-ui/src/views` 下全部 `.vue` 页面共 **734** 个，减去菜单对照表白名单命中的 **126** 个，剩余 **608** 个为菜单清单外页面（未挂菜单 / 历史遗留 / 重复 / 旧版），即方案 P1「页面瘦身」要归档再删除的部分。

> 生成方式：以 `t_sys_menu.component` 126 个值为白名单，对 `views` 目录做路径差集。白名单 126 个全部存在，差集无歧义。

> 归档策略：先移入归档目录并打 git tag（可回滚），验证无引用后再彻底删除；不直接 `rm`。


## 汇总

| 一级模块 | 说明 | 清单外页面数 |
| --- | --- | ---: |
| `erp` | ERP 业务（基础资料/采购/销售/仓储/物流等） | 262 |
| `amazon` | Amazon 运营/广告/报表/财务等 | 240 |
| `finance` | 财务管理 | 51 |
| `sys` | 系统管理 | 39 |
| `dashboard` | 首页/看板 | 11 |
| `customized` | 定制页面 | 5 |
| **合计** | | **608** |

## 逐模块明细


### `erp` — ERP 业务（基础资料/采购/销售/仓储/物流等）（262 个）


`erp/baseinfo/material/`（2）

- `erp/baseinfo/material/consumableDialog.vue`
- `erp/baseinfo/material/materialDialog.vue`

`erp/baseinfo/material/components/`（2）

- `erp/baseinfo/material/components/header.vue`
- `erp/baseinfo/material/components/record.vue`

`erp/baseinfo/material/details/`（1）

- `erp/baseinfo/material/details/index.vue`

`erp/baseinfo/material/details/components/`（1）

- `erp/baseinfo/material/details/components/listinfo.vue`

`erp/baseinfo/material/editinfo/`（1）

- `erp/baseinfo/material/editinfo/index.vue`

`erp/baseinfo/material/editinfo/components/`（8）

- `erp/baseinfo/material/editinfo/components/assemble_info.vue`
- `erp/baseinfo/material/editinfo/components/base.vue`
- `erp/baseinfo/material/editinfo/components/consumables.vue`
- `erp/baseinfo/material/editinfo/components/logistics.vue`
- `erp/baseinfo/material/editinfo/components/parent_info.vue`
- `erp/baseinfo/material/editinfo/components/purchase.vue`
- `erp/baseinfo/material/editinfo/components/purchase_price_dialog.vue`
- `erp/baseinfo/material/editinfo/components/specs.vue`

`erp/baseinfo/material/photoupload/`（1）

- `erp/baseinfo/material/photoupload/index.vue`

`erp/baseinfo/supplier/`（1）

- `erp/baseinfo/supplier/supplier_dialog.vue`

`erp/baseinfo/supplier/components/`（2）

- `erp/baseinfo/supplier/components/detail.vue`
- `erp/baseinfo/supplier/components/table.vue`

`erp/components/`（3）

- `erp/components/assembly_dialog.vue`
- `erp/components/download_dialog.vue`
- `erp/components/shelfInvOpt.vue`

`erp/finance/account/components/`（12）

- `erp/finance/account/components/account_delete_dialog.vue`
- `erp/finance/account/components/account_edit_dialog.vue`
- `erp/finance/account/components/account_overview.vue`
- `erp/finance/account/components/alibaba_settlement_tab.vue`
- `erp/finance/account/components/bookkeeping.vue`
- `erp/finance/account/components/finItem.vue`
- `erp/finance/account/components/finRecord.vue`
- `erp/finance/account/components/lineChart.vue`
- `erp/finance/account/components/payacc_index_dialog.vue`
- `erp/finance/account/components/paymethod_index_dialog.vue`
- `erp/finance/account/components/pieChart.vue`
- `erp/finance/account/components/table.vue`

`erp/finance/alibabaSettlement/`（1）

- `erp/finance/alibabaSettlement/index.vue`

`erp/finance/alibabaSettlement/components/`（6）

- `erp/finance/alibabaSettlement/components/order.vue`
- `erp/finance/alibabaSettlement/components/pay.vue`
- `erp/finance/alibabaSettlement/components/returnorder.vue`
- `erp/finance/alibabaSettlement/components/returnpay.vue`
- `erp/finance/alibabaSettlement/components/settled.vue`
- `erp/finance/alibabaSettlement/components/unsettled.vue`

`erp/finance/inventoryValue/components/`（2）

- `erp/finance/inventoryValue/components/fba.vue`
- `erp/finance/inventoryValue/components/self.vue`

`erp/finance/paymentRequest/components/`（3）

- `erp/finance/paymentRequest/components/addPaymentDialog.vue`
- `erp/finance/paymentRequest/components/details.vue`
- `erp/finance/paymentRequest/components/table.vue`

`erp/purchase/change/components/`（3）

- `erp/purchase/change/components/create.vue`
- `erp/purchase/change/components/details.vue`
- `erp/purchase/change/components/header.vue`

`erp/purchase/components/`（1）

- `erp/purchase/components/preplan.vue`

`erp/purchase/consumable/components/`（1）

- `erp/purchase/consumable/components/consumable_material.vue`

`erp/purchase/open1688/order/`（1）

- `erp/purchase/open1688/order/open1688order.vue`

`erp/purchase/orders/`（2）

- `erp/purchase/orders/order_dialog.vue`
- `erp/purchase/orders/records.vue`

`erp/purchase/orders/components/`（18）

- `erp/purchase/orders/components/arrival_record.vue`
- `erp/purchase/orders/components/assembly.vue`
- `erp/purchase/orders/components/auto_price_dialog.vue`
- `erp/purchase/orders/components/change_records.vue`
- `erp/purchase/orders/components/create.vue`
- `erp/purchase/orders/components/deliverydate.vue`
- `erp/purchase/orders/components/details.vue`
- `erp/purchase/orders/components/logistics_record.vue`
- `erp/purchase/orders/components/order_record.vue`
- `erp/purchase/orders/components/pay_record.vue`
- `erp/purchase/orders/components/payment.vue`
- `erp/purchase/orders/components/printip.vue`
- `erp/purchase/orders/components/receipt.vue`
- `erp/purchase/orders/components/remark_dialog.vue`
- `erp/purchase/orders/components/shipfee_dialog.vue`
- `erp/purchase/orders/components/skuTable.vue`
- `erp/purchase/orders/components/sku_table_inner_dailog.vue`
- `erp/purchase/orders/components/table.vue`

`erp/purchase/plan_amz/components/`（3）

- `erp/purchase/plan_amz/components/expand_table.vue`
- `erp/purchase/plan_amz/components/header.vue`
- `erp/purchase/plan_amz/components/plan_select.vue`

`erp/purchase/plan_amz/plan_submit/`（2）

- `erp/purchase/plan_amz/plan_submit/detail.vue`
- `erp/purchase/plan_amz/plan_submit/index.vue`

`erp/purchase/process/components/`（3）

- `erp/purchase/process/components/create.vue`
- `erp/purchase/process/components/details.vue`
- `erp/purchase/process/components/header.vue`

`erp/purchase/report/receive/components/`（1）

- `erp/purchase/report/receive/components/storageDetail_table.vue`

`erp/ship/orderblank/`（1）

- `erp/ship/orderblank/index.vue`

`erp/ship/quota/`（1）

- `erp/ship/quota/index.vue`

`erp/ship/quota/components/`（1）

- `erp/ship/quota/components/shelfproduct.vue`

`erp/ship/ship_plan/components/`（9）

- `erp/ship/ship_plan/components/afterplan_dialog.vue`
- `erp/ship/ship_plan/components/check_data_dialog.vue`
- `erp/ship/ship_plan/components/delivery_dialog.vue`
- `erp/ship/ship_plan/components/expand_table.vue`
- `erp/ship/ship_plan/components/header.vue`
- `erp/ship/ship_plan/components/remarks_dialog.vue`
- `erp/ship/ship_plan/components/ship_record.vue`
- `erp/ship/ship_plan/components/shipmarket_rank.vue`
- `erp/ship/ship_plan/components/split_plan_dialog.vue`

`erp/ship/ship_plan/plan_submit/`（3）

- `erp/ship/ship_plan/plan_submit/eu_split.vue`
- `erp/ship/ship_plan/plan_submit/index.vue`
- `erp/ship/ship_plan/plan_submit/market_plan.vue`

`erp/ship/shipment_add/approve/`（1）

- `erp/ship/shipment_add/approve/index.vue`

`erp/ship/shipment_add/approve/components/`（3）

- `erp/ship/shipment_add/approve/components/list.vue`
- `erp/ship/shipment_add/approve/components/split_dialog.vue`
- `erp/ship/shipment_add/approve/components/table.vue`

`erp/ship/shipment_add/create/`（1）

- `erp/ship/shipment_add/create/index.vue`

`erp/ship/shipment_add/create/components/`（2）

- `erp/ship/shipment_add/create/components/form.vue`
- `erp/ship/shipment_add/create/components/mateiralDialog.vue`

`erp/ship/shipment_add/list/`（1）

- `erp/ship/shipment_add/list/index.vue`

`erp/ship/shipment_add/list/components/`（2）

- `erp/ship/shipment_add/list/components/header.vue`
- `erp/ship/shipment_add/list/components/table.vue`

`erp/ship/shipment_handing/list/components/`（6）

- `erp/ship/shipment_handing/list/components/async.vue`
- `erp/ship/shipment_handing/list/components/asyncdailog.vue`
- `erp/ship/shipment_handing/list/components/header.vue`
- `erp/ship/shipment_handing/list/components/table.vue`
- `erp/ship/shipment_handing/list/components/transinfo.vue`
- `erp/ship/shipment_handing/list/components/transnumber.vue`

`erp/ship/shipment_handing/shipstep/`（1）

- `erp/ship/shipment_handing/shipstep/index.vue`

`erp/ship/shipment_handing/shipstep/components/`（7）

- `erp/ship/shipment_handing/shipstep/components/four_receive.vue`
- `erp/ship/shipment_handing/shipstep/components/one_picking.vue`
- `erp/ship/shipment_handing/shipstep/components/print_dialog.vue`
- `erp/ship/shipment_handing/shipstep/components/shipment_info.vue`
- `erp/ship/shipment_handing/shipstep/components/shipment_operator.vue`
- `erp/ship/shipment_handing/shipstep/components/three_deliver.vue`
- `erp/ship/shipment_handing/shipstep/components/two_box.vue`

`erp/ship/transportation/`（2）

- `erp/ship/transportation/details_transportation.vue`
- `erp/ship/transportation/edit_transportation.vue`

`erp/ship/transportation/components/`（3）

- `erp/ship/transportation/components/compare.vue`
- `erp/ship/transportation/components/list.vue`
- `erp/ship/transportation/components/trans_type_dialog.vue`

`erp/shipv2/quote/`（3）

- `erp/shipv2/quote/overview.vue`
- `erp/shipv2/quote/price.vue`
- `erp/shipv2/quote/supplier.vue`

`erp/shipv2/quote/components/`（6）

- `erp/shipv2/quote/components/channel_dialog.vue`
- `erp/shipv2/quote/components/channel_type.vue`
- `erp/shipv2/quote/components/create_dialog.vue`
- `erp/shipv2/quote/components/price_dialog.vue`
- `erp/shipv2/quote/components/protocol_dialog.vue`
- `erp/shipv2/quote/components/reset_password_dialog.vue`

`erp/shipv2/quote/purchase/`（1）

- `erp/shipv2/quote/purchase/quote.vue`

`erp/shipv2/shipment_add/approve/`（1）

- `erp/shipv2/shipment_add/approve/index.vue`

`erp/shipv2/shipment_add/approve/components/`（3）

- `erp/shipv2/shipment_add/approve/components/list.vue`
- `erp/shipv2/shipment_add/approve/components/split_dialog.vue`
- `erp/shipv2/shipment_add/approve/components/table.vue`

`erp/shipv2/shipment_add/create/`（1）

- `erp/shipv2/shipment_add/create/index.vue`

`erp/shipv2/shipment_add/create/components/`（2）

- `erp/shipv2/shipment_add/create/components/form.vue`
- `erp/shipv2/shipment_add/create/components/mateiralDialog.vue`

`erp/shipv2/shipment_add/list/components/`（4）

- `erp/shipv2/shipment_add/list/components/async.vue`
- `erp/shipv2/shipment_add/list/components/asyncdailog.vue`
- `erp/shipv2/shipment_add/list/components/header.vue`
- `erp/shipv2/shipment_add/list/components/table.vue`

`erp/shipv2/shipment_add/shipstep/`（5）

- `erp/shipv2/shipment_add/shipstep/four_end.vue`
- `erp/shipv2/shipment_add/shipstep/one_pick.vue`
- `erp/shipv2/shipment_add/shipstep/placement_confirm.vue`
- `erp/shipv2/shipment_add/shipstep/three_ship.vue`
- `erp/shipv2/shipment_add/shipstep/two_box.vue`

`erp/shipv2/shipment_add/shipstep/components/`（14）

- `erp/shipv2/shipment_add/shipstep/components/box_analysis_dialog.vue`
- `erp/shipv2/shipment_add/shipstep/components/box_plan.vue`
- `erp/shipv2/shipment_add/shipstep/components/box_table.vue`
- `erp/shipv2/shipment_add/shipstep/components/box_table_case.vue`
- `erp/shipv2/shipment_add/shipstep/components/consumable.vue`
- `erp/shipv2/shipment_add/shipstep/components/footer.vue`
- `erp/shipv2/shipment_add/shipstep/components/header.vue`
- `erp/shipv2/shipment_add/shipstep/components/operation.vue`
- `erp/shipv2/shipment_add/shipstep/components/planCancel.vue`
- `erp/shipv2/shipment_add/shipstep/components/planCreate.vue`
- `erp/shipv2/shipment_add/shipstep/components/planShipped.vue`
- `erp/shipv2/shipment_add/shipstep/components/pre.vue`
- `erp/shipv2/shipment_add/shipstep/components/table.vue`
- `erp/shipv2/shipment_add/shipstep/components/trans.vue`

`erp/shipv2/shipment_handing/list/components/`（5）

- `erp/shipv2/shipment_handing/list/components/header.vue`
- `erp/shipv2/shipment_handing/list/components/quoteDialog.vue`
- `erp/shipv2/shipment_handing/list/components/table.vue`
- `erp/shipv2/shipment_handing/list/components/transinfo.vue`
- `erp/shipv2/shipment_handing/list/components/transnumber.vue`

`erp/shipv2/shipment_handing/shipstep/`（2）

- `erp/shipv2/shipment_handing/shipstep/index.vue`
- `erp/shipv2/shipment_handing/shipstep/index_tab.vue`

`erp/shipv2/shipment_handing/shipstep/components/`（15）

- `erp/shipv2/shipment_handing/shipstep/components/customs_clearance.vue`
- `erp/shipv2/shipment_handing/shipstep/components/customs_data.vue`
- `erp/shipv2/shipment_handing/shipstep/components/customs_declaration.vue`
- `erp/shipv2/shipment_handing/shipstep/components/customs_declaration_xml.vue`
- `erp/shipv2/shipment_handing/shipstep/components/customs_inventory_xml.vue`
- `erp/shipv2/shipment_handing/shipstep/components/customs_order_xml.vue`
- `erp/shipv2/shipment_handing/shipstep/components/customs_picking.vue`
- `erp/shipv2/shipment_handing/shipstep/components/destination.vue`
- `erp/shipv2/shipment_handing/shipstep/components/four_receive.vue`
- `erp/shipv2/shipment_handing/shipstep/components/ship_box.vue`
- `erp/shipv2/shipment_handing/shipstep/components/ship_box_case.vue`
- `erp/shipv2/shipment_handing/shipstep/components/shipment_info.vue`
- `erp/shipv2/shipment_handing/shipstep/components/shipment_operator.vue`
- `erp/shipv2/shipment_handing/shipstep/components/three_deliver.vue`
- `erp/shipv2/shipment_handing/shipstep/components/two_box.vue`

`erp/thirdparty/components/`（1）

- `erp/thirdparty/components/edit_dialog.vue`

`erp/warehouse/base/`（1）

- `erp/warehouse/base/warehouseDialog.vue`

`erp/warehouse/base/components/`（5）

- `erp/warehouse/base/components/childrenlist.vue`
- `erp/warehouse/base/components/edit.vue`
- `erp/warehouse/base/components/parentlist.vue`
- `erp/warehouse/base/components/thirdparty_bind_dialog.vue`
- `erp/warehouse/base/components/thirdparty_oversea.vue`

`erp/warehouse/base/components/thirdparty/`（4）

- `erp/warehouse/base/components/thirdparty/k5.vue`
- `erp/warehouse/base/components/thirdparty/ops.vue`
- `erp/warehouse/base/components/thirdparty/xl.vue`
- `erp/warehouse/base/components/thirdparty/yc.vue`

`erp/warehouse/exchange/components/`（3）

- `erp/warehouse/exchange/components/create.vue`
- `erp/warehouse/exchange/components/details.vue`
- `erp/warehouse/exchange/components/header.vue`

`erp/warehouse/fee/`（2）

- `erp/warehouse/fee/detail_dialog.vue`
- `erp/warehouse/fee/index.vue`

`erp/warehouse/inbound/components/`（4）

- `erp/warehouse/inbound/components/create.vue`
- `erp/warehouse/inbound/components/create_dialog.vue`
- `erp/warehouse/inbound/components/details.vue`
- `erp/warehouse/inbound/components/table.vue`

`erp/warehouse/outbound/components/`（4）

- `erp/warehouse/outbound/components/create.vue`
- `erp/warehouse/outbound/components/create_dialog.vue`
- `erp/warehouse/outbound/components/details.vue`
- `erp/warehouse/outbound/components/table.vue`

`erp/warehouse/overseas/inv/`（1）

- `erp/warehouse/overseas/inv/index.vue`

`erp/warehouse/overseas/inv/components/`（5）

- `erp/warehouse/overseas/inv/components/create.vue`
- `erp/warehouse/overseas/inv/components/inv_header.vue`
- `erp/warehouse/overseas/inv/components/inv_his_dialog.vue`
- `erp/warehouse/overseas/inv/components/invoice_dialog.vue`
- `erp/warehouse/overseas/inv/components/plan_create_dialog.vue`

`erp/warehouse/overseas/order/`（1）

- `erp/warehouse/overseas/order/index.vue`

`erp/warehouse/overseas/order/components/`（5）

- `erp/warehouse/overseas/order/components/create.vue`
- `erp/warehouse/overseas/order/components/invoice_dialog.vue`
- `erp/warehouse/overseas/order/components/invoice_view_dialog.vue`
- `erp/warehouse/overseas/order/components/platform.vue`
- `erp/warehouse/overseas/order/components/sales_header.vue`

`erp/warehouse/overseas/plan/`（1）

- `erp/warehouse/overseas/plan/index.vue`

`erp/warehouse/overseas/plan/components/`（9）

- `erp/warehouse/overseas/plan/components/PurchaseDialog.vue`
- `erp/warehouse/overseas/plan/components/ShipDialog.vue`
- `erp/warehouse/overseas/plan/components/arrival_dialog.vue`
- `erp/warehouse/overseas/plan/components/delivery_dialog.vue`
- `erp/warehouse/overseas/plan/components/expand_table.vue`
- `erp/warehouse/overseas/plan/components/header.vue`
- `erp/warehouse/overseas/plan/components/plan_select.vue`
- `erp/warehouse/overseas/plan/components/salechart.vue`
- `erp/warehouse/overseas/plan/components/ship_record.vue`

`erp/warehouse/overseas/plan/plan_submit/`（2）

- `erp/warehouse/overseas/plan/plan_submit/detail.vue`
- `erp/warehouse/overseas/plan/plan_submit/index.vue`

`erp/warehouse/overseas/simpleplan/`（1）

- `erp/warehouse/overseas/simpleplan/index.vue`

`erp/warehouse/overseas/simpleplan/components/`（4）

- `erp/warehouse/overseas/simpleplan/components/plan_create_dialog.vue`
- `erp/warehouse/overseas/simpleplan/components/plan_detail_dialog.vue`
- `erp/warehouse/overseas/simpleplan/components/plan_header.vue`
- `erp/warehouse/overseas/simpleplan/components/platform.vue`

`erp/warehouse/overseas/stock/`（1）

- `erp/warehouse/overseas/stock/index.vue`

`erp/warehouse/overseas/stock/components/`（7）

- `erp/warehouse/overseas/stock/components/box_table.vue`
- `erp/warehouse/overseas/stock/components/create.vue`
- `erp/warehouse/overseas/stock/components/create_dialog.vue`
- `erp/warehouse/overseas/stock/components/details.vue`
- `erp/warehouse/overseas/stock/components/header.vue`
- `erp/warehouse/overseas/stock/components/logistics.vue`
- `erp/warehouse/overseas/stock/components/split_dialog.vue`

`erp/warehouse/record/components/`（1）

- `erp/warehouse/record/components/table.vue`

`erp/warehouse/shelf/base/`（1）

- `erp/warehouse/shelf/base/shelf_tree.vue`

`erp/warehouse/shelf/create/components/`（2）

- `erp/warehouse/shelf/create/components/shelf_card.vue`
- `erp/warehouse/shelf/create/components/shelf_dialog.vue`

`erp/warehouse/shelf/inv/components/`（1）

- `erp/warehouse/shelf/inv/components/shelf_product.vue`

`erp/warehouse/stocktake/components/`（6）

- `erp/warehouse/stocktake/components/create.vue`
- `erp/warehouse/stocktake/components/create_dialog.vue`
- `erp/warehouse/stocktake/components/details.vue`
- `erp/warehouse/stocktake/components/sku_inventory.vue`
- `erp/warehouse/stocktake/components/table.vue`
- `erp/warehouse/stocktake/components/uploadDialog.vue`

`erp/warehouse/transfer/components/`（3）

- `erp/warehouse/transfer/components/create.vue`
- `erp/warehouse/transfer/components/details.vue`
- `erp/warehouse/transfer/components/header.vue`

### `amazon` — Amazon 运营/广告/报表/财务等（240 个）


`amazon/address/components/`（2）

- `amazon/address/components/editdialog.vue`
- `amazon/address/components/table.vue`

`amazon/advertisement/manager/components/common/`（7）

- `amazon/advertisement/manager/components/common/ad_chart.vue`
- `amazon/advertisement/manager/components/common/ad_order_cycle.vue`
- `amazon/advertisement/manager/components/common/ad_tree.vue`
- `amazon/advertisement/manager/components/common/data_filter.vue`
- `amazon/advertisement/manager/components/common/date_select.vue`
- `amazon/advertisement/manager/components/common/portfolios_plane.vue`
- `amazon/advertisement/manager/components/common/schedule_plan.vue`

`amazon/advertisement/manager/components/hsa/`（13）

- `amazon/advertisement/manager/components/hsa/create.vue`
- `amazon/advertisement/manager/components/hsa/listAdgroups.vue`
- `amazon/advertisement/manager/components/hsa/listCampNegativaKeywords.vue`
- `amazon/advertisement/manager/components/hsa/listCampNegativaTarget.vue`
- `amazon/advertisement/manager/components/hsa/listCampaigns.vue`
- `amazon/advertisement/manager/components/hsa/listKeywords.vue`
- `amazon/advertisement/manager/components/hsa/listKeywordsQuery.vue`
- `amazon/advertisement/manager/components/hsa/listNegativaKeywords.vue`
- `amazon/advertisement/manager/components/hsa/listNegativaTarget.vue`
- `amazon/advertisement/manager/components/hsa/listProductAds.vue`
- `amazon/advertisement/manager/components/hsa/listPurchaseProductAds.vue`
- `amazon/advertisement/manager/components/hsa/listTarget.vue`
- `amazon/advertisement/manager/components/hsa/listTargetQuery.vue`

`amazon/advertisement/manager/components/hsa/components/`（12）

- `amazon/advertisement/manager/components/hsa/components/adcreative.vue`
- `amazon/advertisement/manager/components/hsa/components/bidding.vue`
- `amazon/advertisement/manager/components/hsa/components/bidding_dialog.vue`
- `amazon/advertisement/manager/components/hsa/components/bidding_tactics_sb.vue`
- `amazon/advertisement/manager/components/hsa/components/bidding_tactics_sp.vue`
- `amazon/advertisement/manager/components/hsa/components/bidding_type.vue`
- `amazon/advertisement/manager/components/hsa/components/negative_keywords.vue`
- `amazon/advertisement/manager/components/hsa/components/negative_target.vue`
- `amazon/advertisement/manager/components/hsa/components/product_add.vue`
- `amazon/advertisement/manager/components/hsa/components/target.vue`
- `amazon/advertisement/manager/components/hsa/components/target_keyword.vue`
- `amazon/advertisement/manager/components/hsa/components/target_product.vue`

`amazon/advertisement/manager/components/sd/`（7）

- `amazon/advertisement/manager/components/sd/create.vue`
- `amazon/advertisement/manager/components/sd/listAdgroups.vue`
- `amazon/advertisement/manager/components/sd/listCampaigns.vue`
- `amazon/advertisement/manager/components/sd/listNegativaTarget.vue`
- `amazon/advertisement/manager/components/sd/listProductAds.vue`
- `amazon/advertisement/manager/components/sd/listPurchaseProductAds.vue`
- `amazon/advertisement/manager/components/sd/listTarget.vue`

`amazon/advertisement/manager/components/sd/components/`（10）

- `amazon/advertisement/manager/components/sd/components/adcreative.vue`
- `amazon/advertisement/manager/components/sd/components/bidding.vue`
- `amazon/advertisement/manager/components/sd/components/bidding_dialog.vue`
- `amazon/advertisement/manager/components/sd/components/bidding_tactics_sp.vue`
- `amazon/advertisement/manager/components/sd/components/bidding_type.vue`
- `amazon/advertisement/manager/components/sd/components/negative_keywords.vue`
- `amazon/advertisement/manager/components/sd/components/negative_target.vue`
- `amazon/advertisement/manager/components/sd/components/product_add.vue`
- `amazon/advertisement/manager/components/sd/components/target.vue`
- `amazon/advertisement/manager/components/sd/components/target_product.vue`

`amazon/advertisement/manager/components/sp/`（13）

- `amazon/advertisement/manager/components/sp/create.vue`
- `amazon/advertisement/manager/components/sp/listAdgroups.vue`
- `amazon/advertisement/manager/components/sp/listCampNegativaKeywords.vue`
- `amazon/advertisement/manager/components/sp/listCampNegativaTarget.vue`
- `amazon/advertisement/manager/components/sp/listCampaigns.vue`
- `amazon/advertisement/manager/components/sp/listKeywords.vue`
- `amazon/advertisement/manager/components/sp/listKeywordsQuery.vue`
- `amazon/advertisement/manager/components/sp/listNegativaKeywords.vue`
- `amazon/advertisement/manager/components/sp/listNegativaTarget.vue`
- `amazon/advertisement/manager/components/sp/listProductAds.vue`
- `amazon/advertisement/manager/components/sp/listPurchaseProductAds.vue`
- `amazon/advertisement/manager/components/sp/listTarget.vue`
- `amazon/advertisement/manager/components/sp/listTargetQuery.vue`

`amazon/advertisement/manager/components/sp/components/`（10）

- `amazon/advertisement/manager/components/sp/components/bidding.vue`
- `amazon/advertisement/manager/components/sp/components/bidding_dialog.vue`
- `amazon/advertisement/manager/components/sp/components/bidding_tactics_sp.vue`
- `amazon/advertisement/manager/components/sp/components/bidding_type.vue`
- `amazon/advertisement/manager/components/sp/components/negative_keywords.vue`
- `amazon/advertisement/manager/components/sp/components/negative_target.vue`
- `amazon/advertisement/manager/components/sp/components/product_add.vue`
- `amazon/advertisement/manager/components/sp/components/target.vue`
- `amazon/advertisement/manager/components/sp/components/target_keyword.vue`
- `amazon/advertisement/manager/components/sp/components/target_product.vue`

`amazon/advertisement/manager/warn/`（1）

- `amazon/advertisement/manager/warn/index.vue`

`amazon/advertisement/manager/warn/components/`（1）

- `amazon/advertisement/manager/warn/components/create.vue`

`amazon/advertisement/operation_record/components/`（2）

- `amazon/advertisement/operation_record/components/detail.vue`
- `amazon/advertisement/operation_record/components/remarks_dialog.vue`

`amazon/advertisement/overview/components/`（5）

- `amazon/advertisement/overview/components/adFunnel.vue`
- `amazon/advertisement/overview/components/adStatistics.vue`
- `amazon/advertisement/overview/components/indicator.vue`
- `amazon/advertisement/overview/components/indicator_detail.vue`
- `amazon/advertisement/overview/components/roasRank.vue`

`amazon/inventory/quantity/`（1）

- `amazon/inventory/quantity/index.vue`

`amazon/inventory/worth/`（1）

- `amazon/inventory/worth/index.vue`

`amazon/listing/analysis/components/`（2）

- `amazon/listing/analysis/components/data_deatils.vue`
- `amazon/listing/analysis/components/dialog.vue`

`amazon/listing/common/`（6）

- `amazon/listing/common/arrival_dialog.vue`
- `amazon/listing/common/goods_deatils.vue`
- `amazon/listing/common/group.vue`
- `amazon/listing/common/group_only.vue`
- `amazon/listing/common/listings.vue`
- `amazon/listing/common/salechart.vue`

`amazon/listing/price_adjust/`（1）

- `amazon/listing/price_adjust/index.vue`

`amazon/listing/price_adjust/components/`（1）

- `amazon/listing/price_adjust/components/table.vue`

`amazon/listing/product_customs/components/`（13）

- `amazon/listing/product_customs/components/arrival_dialog.vue`
- `amazon/listing/product_customs/components/batchmodifypriceDialog.vue`
- `amazon/listing/product_customs/components/follow_dialog.vue`
- `amazon/listing/product_customs/components/matching.vue`
- `amazon/listing/product_customs/components/modifypriceDialog.vue`
- `amazon/listing/product_customs/components/profit_details.vue`
- `amazon/listing/product_customs/components/prostatus.vue`
- `amazon/listing/product_customs/components/refreshProduct.vue`
- `amazon/listing/product_customs/components/remarks_dialog.vue`
- `amazon/listing/product_customs/components/sale_formula.vue`
- `amazon/listing/product_customs/components/sale_status_dialog.vue`
- `amazon/listing/product_customs/components/sale_status_select.vue`
- `amazon/listing/product_customs/components/table.vue`

`amazon/listing/product_edit/`（1）

- `amazon/listing/product_edit/index.vue`

`amazon/listing/product_plus/`（1）

- `amazon/listing/product_plus/index.vue`

`amazon/listing/products/components/`（16）

- `amazon/listing/products/components/arrival_dialog.vue`
- `amazon/listing/products/components/batchmodifypriceDialog.vue`
- `amazon/listing/products/components/fbainvhistory_dialog.vue`
- `amazon/listing/products/components/feedback_dialog.vue`
- `amazon/listing/products/components/follow_dialog.vue`
- `amazon/listing/products/components/matching.vue`
- `amazon/listing/products/components/modifypriceDialog.vue`
- `amazon/listing/products/components/productAIDialog.vue`
- `amazon/listing/products/components/profit_details.vue`
- `amazon/listing/products/components/prostatus.vue`
- `amazon/listing/products/components/refreshProduct.vue`
- `amazon/listing/products/components/remarks_dialog.vue`
- `amazon/listing/products/components/sale_formula.vue`
- `amazon/listing/products/components/sale_status_dialog.vue`
- `amazon/listing/products/components/sale_status_select.vue`
- `amazon/listing/products/components/table.vue`

`amazon/order/fbm/`（1）

- `amazon/order/fbm/index.vue`

`amazon/order/fbm/components/`（7）

- `amazon/order/fbm/components/header.vue`
- `amazon/order/fbm/components/invoice.vue`
- `amazon/order/fbm/components/invoice_template.vue`
- `amazon/order/fbm/components/invoice_view.vue`
- `amazon/order/fbm/components/invoice_view_refund.vue`
- `amazon/order/fbm/components/order_detail.vue`
- `amazon/order/fbm/components/table.vue`

`amazon/order/order_manager/components/`（8）

- `amazon/order/order_manager/components/header.vue`
- `amazon/order/order_manager/components/invoice.vue`
- `amazon/order/order_manager/components/invoice_template.vue`
- `amazon/order/order_manager/components/invoice_view.vue`
- `amazon/order/order_manager/components/invoice_view_refund.vue`
- `amazon/order/order_manager/components/message.vue`
- `amazon/order/order_manager/components/order_detail.vue`
- `amazon/order/order_manager/components/table.vue`

`amazon/order/refund/components/`（7）

- `amazon/order/refund/components/header.vue`
- `amazon/order/refund/components/skudetail.vue`
- `amazon/order/refund/components/summary_chart.vue`
- `amazon/order/refund/components/summary_chart_line.vue`
- `amazon/order/refund/components/summary_chart_pie.vue`
- `amazon/order/refund/components/summary_sku.vue`
- `amazon/order/refund/components/table.vue`

`amazon/order/remove/components/`（2）

- `amazon/order/remove/components/header.vue`
- `amazon/order/remove/components/table.vue`

`amazon/order/summary_week_month/`（1）

- `amazon/order/summary_week_month/index.vue`

`amazon/order/summary_week_month/components/`（3）

- `amazon/order/summary_week_month/components/day.vue`
- `amazon/order/summary_week_month/components/header.vue`
- `amazon/order/summary_week_month/components/month.vue`

`amazon/order/today/components/`（1）

- `amazon/order/today/components/header.vue`

`amazon/payment/common/`（1）

- `amazon/payment/common/refresh_data_dialog.vue`

`amazon/payment/costSharing/`（1）

- `amazon/payment/costSharing/cost_dialog.vue`

`amazon/payment/costSharing/components/`（4）

- `amazon/payment/costSharing/components/create.vue`
- `amazon/payment/costSharing/components/otherFee.vue`
- `amazon/payment/costSharing/components/shareRules.vue`
- `amazon/payment/costSharing/components/table.vue`

`amazon/payment/finance/`（1）

- `amazon/payment/finance/index.vue`

`amazon/payment/finance/components/`（3）

- `amazon/payment/finance/components/month.vue`
- `amazon/payment/finance/components/other_fee_dialog.vue`
- `amazon/payment/finance/components/record.vue`

`amazon/payment/item_fee/`（1）

- `amazon/payment/item_fee/index.vue`

`amazon/payment/jobCosting/components/`（3）

- `amazon/payment/jobCosting/components/edit.vue`
- `amazon/payment/jobCosting/components/resetAddDialog.vue`
- `amazon/payment/jobCosting/components/table.vue`

`amazon/payment/monthReport/`（1）

- `amazon/payment/monthReport/index.vue`

`amazon/payment/performanpce/components/`（2）

- `amazon/payment/performanpce/components/monthReport.vue`
- `amazon/payment/performanpce/components/table.vue`

`amazon/payment/profitReport/components/`（2）

- `amazon/payment/profitReport/components/accCalc_dialog.vue`
- `amazon/payment/profitReport/components/formula.vue`

`amazon/payment/profitReport/profitGroup/components/`（1）

- `amazon/payment/profitReport/profitGroup/components/profitGroup.vue`

`amazon/payment/profitReport/table/`（2）

- `amazon/payment/profitReport/table/child.vue`
- `amazon/payment/profitReport/table/table.vue`

`amazon/payment/record/`（4）

- `amazon/payment/record/chart.vue`
- `amazon/payment/record/detail.vue`
- `amazon/payment/record/records.vue`
- `amazon/payment/record/report.vue`

`amazon/payment/settlement_sku/`（4）

- `amazon/payment/settlement_sku/account_dialog.vue`
- `amazon/payment/settlement_sku/settlement.vue`
- `amazon/payment/settlement_sku/skutable.vue`
- `amazon/payment/settlement_sku/table.vue`

`amazon/payment/summaryMonth/`（1）

- `amazon/payment/summaryMonth/index.vue`

`amazon/payment/transaction/`（4）

- `amazon/payment/transaction/chart.vue`
- `amazon/payment/transaction/detail.vue`
- `amazon/payment/transaction/index.vue`
- `amazon/payment/transaction/statistics.vue`

`amazon/profit/calculator/`（1）

- `amazon/profit/calculator/index.vue`

`amazon/profit/calculator/components/`（3）

- `amazon/profit/calculator/components/country_collapse.vue`
- `amazon/profit/calculator/components/fba_size.vue`
- `amazon/profit/calculator/components/profit_deatils.vue`

`amazon/profit/config/`（1）

- `amazon/profit/config/index.vue`

`amazon/profit/config/components/`（1）

- `amazon/profit/config/components/edit_plan.vue`

`amazon/profit/config/components/market_fee/`（1）

- `amazon/profit/config/components/market_fee/form.vue`

`amazon/profit/fbafee/component/`（9）

- `amazon/profit/fbafee/component/fba.vue`
- `amazon/profit/fbafee/component/fbasipp.vue`
- `amazon/profit/fbafee/component/inplace.vue`
- `amazon/profit/fbafee/component/inplacefee.vue`
- `amazon/profit/fbafee/component/referralfee.vue`
- `amazon/profit/fbafee/component/storagefee.vue`
- `amazon/profit/fbafee/component/tier.vue`
- `amazon/profit/fbafee/component/tierformat.vue`
- `amazon/profit/fbafee/component/tierweight.vue`

`amazon/report/fba_fee/`（1）

- `amazon/report/fba_fee/header.vue`

`amazon/report/performance/coupon/`（2）

- `amazon/report/performance/coupon/header.vue`
- `amazon/report/performance/coupon/index.vue`

`amazon/report/performance/orderfeedback/`（1）

- `amazon/report/performance/orderfeedback/header.vue`

`amazon/report/performance/promotions/`（2）

- `amazon/report/performance/promotions/header.vue`
- `amazon/report/performance/promotions/index.vue`

`amazon/report/performance/seller/`（1）

- `amazon/report/performance/seller/header.vue`

`amazon/report/ship/detail/`（1）

- `amazon/report/ship/detail/index.vue`

`amazon/report/ship/fee_summary/`（2）

- `amazon/report/ship/fee_summary/detail_dialog.vue`
- `amazon/report/ship/fee_summary/index.vue`

`amazon/report/ship/fee_summary_new/`（1）

- `amazon/report/ship/fee_summary_new/detail_dialog.vue`

`amazon/report/ship/ship_fee_record/`（1）

- `amazon/report/ship/ship_fee_record/index.vue`

`amazon/report/ship/ship_summary/`（1）

- `amazon/report/ship/ship_summary/index.vue`

`amazon/report/ship/ship_summary/component/`（1）

- `amazon/report/ship/ship_summary/component/piechart.vue`

`amazon/report/shipv2/ship_summary/component/`（1）

- `amazon/report/shipv2/ship_summary/component/piechart.vue`

`amazon/report/storage_fee/`（2）

- `amazon/report/storage_fee/index_fee.vue`
- `amazon/report/storage_fee/index_long.vue`

`amazon/sales/forecast/components/`（2）

- `amazon/sales/forecast/components/sale_adjust_dialog.vue`
- `amazon/sales/forecast/components/sale_adjust_upload_dialog.vue`

`amazon/service/VatInvioce/`（1）

- `amazon/service/VatInvioce/index.vue`

`amazon/storeAuth/advResult/`（1）

- `amazon/storeAuth/advResult/index.vue`

`amazon/storeAuth/authResult/`（1）

- `amazon/storeAuth/authResult/index.vue`

`amazon/storeAuth/components/`（5）

- `amazon/storeAuth/components/advReport.vue`
- `amazon/storeAuth/components/authadv.vue`
- `amazon/storeAuth/components/authstore.vue`
- `amazon/storeAuth/components/table.vue`
- `amazon/storeAuth/components/taskData.vue`

`amazon/temu/`（1）

- `amazon/temu/index.vue`

`amazon/transparency/manager/`（1）

- `amazon/transparency/manager/index.vue`

`amazon/transparency/manager/components/`（4）

- `amazon/transparency/manager/components/skuinfo.vue`
- `amazon/transparency/manager/components/task.vue`
- `amazon/transparency/manager/components/tcode.vue`
- `amazon/transparency/manager/components/uploadDialog.vue`

### `finance` — 财务管理（51 个）


`finance/auxiliary/types/`（1）

- `finance/auxiliary/types/index.vue`

`finance/category/`（1）

- `finance/category/index.vue`

`finance/config/`（1）

- `finance/config/index.vue`

`finance/config/components/`（4）

- `finance/config/components/inventoryconfig.vue`
- `finance/config/components/invoiceconfig.vue`
- `finance/config/components/payconfig.vue`
- `finance/config/components/voucherlog.vue`

`finance/entries/`（1）

- `finance/entries/index.vue`

`finance/ledger/carrier/`（1）

- `finance/ledger/carrier/index.vue`

`finance/ledger/carrier/components/`（1）

- `finance/ledger/carrier/components/detail-drawer.vue`

`finance/ledger/inventory/`（1）

- `finance/ledger/inventory/index.vue`

`finance/ledger/invoice/`（1）

- `finance/ledger/invoice/index.vue`

`finance/ledger/invoice/components/`（7）

- `finance/ledger/invoice/components/add-invoice-dialog.vue`
- `finance/ledger/invoice/components/detail-drawer.vue`
- `finance/ledger/invoice/components/import-dialog.vue`
- `finance/ledger/invoice/components/invoice-posting-dialog.vue`
- `finance/ledger/invoice/components/posting-dialog.vue`
- `finance/ledger/invoice/components/relation-drawer.vue`
- `finance/ledger/invoice/components/sync-dialog.vue`

`finance/ledger/purchase/`（1）

- `finance/ledger/purchase/index.vue`

`finance/ledger/purchase/components/`（3）

- `finance/ledger/purchase/components/pay-dialog.vue`
- `finance/ledger/purchase/components/payment-drawer.vue`
- `finance/ledger/purchase/components/upload-dialog.vue`

`finance/ledger/supplier/`（1）

- `finance/ledger/supplier/index.vue`

`finance/ledger/supplier/components/`（1）

- `finance/ledger/supplier/components/detail-drawer.vue`

`finance/log/`（1）

- `finance/log/index.vue`

`finance/periods/components/`（5）

- `finance/periods/components/periods.vue`
- `finance/periods/components/template-tree.vue`
- `finance/periods/components/template.vue`
- `finance/periods/components/voucher_log_dialog.vue`
- `finance/periods/components/vouchers.vue`

`finance/periods/components/template/`（8）

- `finance/periods/components/template/add_subject_dialog.vue`
- `finance/periods/components/template/amazon_fin_month_report.vue`
- `finance/periods/components/template/feishu_table_bind.vue`
- `finance/periods/components/template/feishu_table_record.vue`
- `finance/periods/components/template/feishu_table_record_filter.vue`
- `finance/periods/components/template/other_custom.vue`
- `finance/periods/components/template/period_change_rate.vue`
- `finance/periods/components/template/period_profit_loss_transfer.vue`

`finance/report/items/components/`（5）

- `finance/report/items/components/report_balance.vue`
- `finance/report/items/components/report_cashflow.vue`
- `finance/report/items/components/report_equity.vue`
- `finance/report/items/components/report_income.vue`
- `finance/report/items/components/report_sheet.vue`

`finance/report/templates/components/`（4）

- `finance/report/templates/components/mapping_rules_panel.vue`
- `finance/report/templates/components/template_item_custom_dialog.vue`
- `finance/report/templates/components/template_item_edit.vue`
- `finance/report/templates/components/template_items.vue`

`finance/vouchers/create/components/`（3）

- `finance/vouchers/create/components/ImportVoucherDialog.vue`
- `finance/vouchers/create/components/VoucherTemplate.vue`
- `finance/vouchers/create/components/upload_status.vue`

### `sys` — 系统管理（39 个）


`sys/`（3）

- `sys/Login.vue`
- `sys/Register.vue`
- `sys/SSOLogin.vue`

`sys/blank/`（1）

- `sys/blank/index.vue`

`sys/client/`（1）

- `sys/client/index.vue`

`sys/deepseek/components/`（1）

- `sys/deepseek/components/MarkdownRenderer.vue`

`sys/dict/components/`（2）

- `sys/dict/components/DictItem.vue`
- `sys/dict/components/DictType.vue`

`sys/feishu/chat/`（1）

- `sys/feishu/chat/index.vue`

`sys/fields/`（1）

- `sys/fields/index.vue`

`sys/fields/components/`（2）

- `sys/fields/components/FieldPreview.vue`
- `sys/fields/components/FieldTable.vue`

`sys/gen/`（4）

- `sys/gen/basicInfoForm.vue`
- `sys/gen/editTable.vue`
- `sys/gen/genInfoForm.vue`
- `sys/gen/importTable.vue`

`sys/menu/components/`（2）

- `sys/menu/components/Menu.vue`
- `sys/menu/components/Perm.vue`

`sys/movedata/components/`（3）

- `sys/movedata/components/backup.vue`
- `sys/movedata/components/movetable.vue`
- `sys/movedata/components/settlement.vue`

`sys/notepad/`（1）

- `sys/notepad/content_dialog.vue`

`sys/ruleAuth/components/`（3）

- `sys/ruleAuth/components/menu_list.vue`
- `sys/ruleAuth/components/page_list.vue`
- `sys/ruleAuth/components/role_list.vue`

`sys/swagger/`（2）

- `sys/swagger/amazon.vue`
- `sys/swagger/erp.vue`

`sys/tag/components/`（2）

- `sys/tag/components/sysTagGroups.vue`
- `sys/tag/components/sysTags.vue`

`sys/temu/`（1）

- `sys/temu/index.vue`

`sys/userCenter/`（2）

- `sys/userCenter/index.vue`
- `sys/userCenter/resetPassword.vue`

`sys/userCenter/components/`（7）

- `sys/userCenter/components/accountSafe.vue`
- `sys/userCenter/components/buypackage.vue`
- `sys/userCenter/components/card.vue`
- `sys/userCenter/components/logoff.vue`
- `sys/userCenter/components/package.vue`
- `sys/userCenter/components/unbind.vue`
- `sys/userCenter/components/wechat.vue`

### `dashboard` — 首页/看板（11 个）


`dashboard/`（1）

- `dashboard/index.vue`

`dashboard/components/`（10）

- `dashboard/components/adchart.vue`
- `dashboard/components/comparechart.vue`
- `dashboard/components/goodsrank.vue`
- `dashboard/components/notice.vue`
- `dashboard/components/personrank.vue`
- `dashboard/components/piechart.vue`
- `dashboard/components/salechart.vue`
- `dashboard/components/service.vue`
- `dashboard/components/stockchart.vue`
- `dashboard/components/storeshow.vue`

### `customized` — 定制页面（5 个）


`customized/plantool/manplan/`（1）

- `customized/plantool/manplan/index.vue`

`customized/plantool/pickpay/`（1）

- `customized/plantool/pickpay/index.vue`

`customized/plantool/purchaseplan/`（1）

- `customized/plantool/purchaseplan/index.vue`

`customized/plantool/salemonth/`（1）

- `customized/plantool/salemonth/index.vue`

`customized/plantool/shipplan/`（1）

- `customized/plantool/shipplan/index.vue`

package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库龄查询（对应源文件 stock-aging-query.ts）。
 * 移植了：按仓库×商品分组的可用性汇总、净命令出库窗口证据计算、
 * StockAgingRules 估算接入、COMPLETE/NEEDS_DATA 判定与游标构造。
 * 源端用两段 Prisma SQL；本类基于内存余额与流水真实实现聚合。
 */
public final class StockAgingQuery {

    private StockAgingQuery() {}

    /** 分组键。 */
    public static final class GroupKey {
        private final String warehouseId;
        private final String productRecordId;

        public GroupKey(String warehouseId, String productRecordId) {
            this.warehouseId = warehouseId;
            this.productRecordId = productRecordId;
        }

        public String getWarehouseId() { return warehouseId; }
        public String getProductRecordId() { return productRecordId; }
        public String id() { return WmsRules.stableJson(java.util.Arrays.asList(warehouseId, productRecordId)); }
    }

    /** 查询分组结果。 */
    public static final class AgingGroup {
        private final GroupKey key;
        private final String quantity;
        private final String availableQty;
        private final String warehouseCode;
        private final String sku;
        private final Map<String, Object> estimate;
        private final boolean complete;

        public AgingGroup(GroupKey key, String quantity, String availableQty, String warehouseCode,
                          String sku, Map<String, Object> estimate, boolean complete) {
            this.key = key;
            this.quantity = quantity;
            this.availableQty = availableQty;
            this.warehouseCode = warehouseCode;
            this.sku = sku;
            this.estimate = estimate;
            this.complete = complete;
        }

        public GroupKey getKey() { return key; }
        public String getQuantity() { return quantity; }
        public Map<String, Object> getEstimate() { return estimate; }
        public boolean isComplete() { return complete; }

        public Map<String, Object> toMap(Date asOf) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", key.id());
            view.put("warehouseId", key.getWarehouseId());
            view.put("productRecordId", key.getProductRecordId());
            view.put("warehouseCode", warehouseCode);
            view.put("sku", sku);
            view.put("quantity", quantity);
            view.put("availableQty", availableQty);
            view.put("asOf", asOf.toInstant().toString());
            view.put("basis", "WMS_NET_COMMAND_OUTBOUND");
            view.put("dataStatus", estimate != null ? "COMPLETE" : "NEEDS_DATA");
            view.put("estimate", estimate);
            return view;
        }
    }

    /** 基于内存余额与流水的库龄查询。 */
    public static List<AgingGroup> queryStockAging(List<Repository.Balance> balances,
                                                   List<Repository.Movement> movements,
                                                   java.util.function.Function<String, Repository.Lot> lotLookup,
                                                   String warehouseIdFilter, String productRecordIdFilter,
                                                   Date asOf) {
        Map<String, List<Repository.Balance>> grouped = new LinkedHashMap<>();
        Map<String, GroupKey> keyById = new LinkedHashMap<>();
        for (Repository.Balance balance : balances) {
            if (warehouseIdFilter != null && !warehouseIdFilter.equals(balance.getWarehouseId())) continue;
            Repository.Lot lot = lotLookup.apply(balance.getLotId());
            if (lot == null) continue;
            if (productRecordIdFilter != null && !productRecordIdFilter.equals(lot.getProductRecordId())) continue;
            GroupKey key = new GroupKey(balance.getWarehouseId(), lot.getProductRecordId());
            keyById.putIfAbsent(key.id(), key);
            grouped.computeIfAbsent(key.id(), k -> new ArrayList<>()).add(balance);
        }
        List<AgingGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<Repository.Balance>> entry : grouped.entrySet()) {
            GroupKey groupKey = keyById.get(entry.getKey());
            long quantity = 0, available = 0;
            for (Repository.Balance balance : entry.getValue()) {
                quantity += balance.getOnHand();
                available += WmsRules.availableQuantity(balance.snapshot());
            }
            String quantityText = String.valueOf(quantity);
            List<StockAgingRules.WindowEvidence> windows = new ArrayList<>();
            boolean complete = true;
            for (int days : StockAgingRules.AGING_WINDOWS) {
                long snapshot = 0, outbound = 0;
                for (Repository.Movement movement : movements) {
                    Repository.Balance balance = findBalanceById(entry.getValue(), movement.getBalanceId());
                    if (balance == null) continue;
                    long cutoff = asOf.getTime() - days * 86400000L;
                    if (movement.getOccurredAt().getTime() < cutoff) {
                        snapshot += movement.getDeltaOnHand();
                    } else if (movement.getDeltaOnHand() < 0) {
                        outbound -= movement.getDeltaOnHand();
                    }
                }
                windows.add(new StockAgingRules.WindowEvidence(days, String.valueOf(snapshot),
                        String.valueOf(outbound)));
            }
            Map<String, String> estimateRaw = StockAgingRules.estimate(quantityText, windows);
            Map<String, Object> estimate = estimateRaw == null ? null : new LinkedHashMap<>(estimateRaw);
            if (estimate == null) complete = false;
            Repository.Lot sample = lotLookup.apply(entry.getValue().get(0).getLotId());
            out.add(new AgingGroup(groupKey, quantityText, String.valueOf(available), "",
                    sample == null ? "" : sample.getProductRecordId(), estimate, complete));
        }
        return out;
    }

    private static Repository.Balance findBalanceById(List<Repository.Balance> balances, String id) {
        for (Repository.Balance balance : balances) if (balance.getId().equals(id)) return balance;
        return null;
    }

}

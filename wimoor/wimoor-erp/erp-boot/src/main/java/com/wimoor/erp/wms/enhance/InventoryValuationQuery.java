package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存计价查询（对应源文件 inventory-valuation-query.ts）。
 * 移植了 presentInventoryValuation：按原币的账实一致性判定（onHand 等于流水
 * 合计且无未计价流水才 COMPLETE），部分计价余额不显示合计。
 */
public final class InventoryValuationQuery {

    /** 计价流水。 */
    public static final class CostEntry {
        private final String balanceId;
        private final String currency;
        private final String amount;

        public CostEntry(String balanceId, String currency, String amount) {
            this.balanceId = balanceId;
            this.currency = currency;
            this.amount = amount;
        }
    }

    /** 余额流水统计。 */
    public static final class LedgerTotal {
        private final String balanceId;
        private final String ledgerQty;
        private final String unvalued;

        public LedgerTotal(String balanceId, String ledgerQty, String unvalued) {
            this.balanceId = balanceId;
            this.ledgerQty = ledgerQty;
            this.unvalued = unvalued;
        }
    }

    /** 余额输入。 */
    public static final class BalanceRow {
        private final String id;
        private final int onHand;
        private final Object stock;

        public BalanceRow(String id, int onHand, Object stock) {
            this.id = id;
            this.onHand = onHand;
            this.stock = stock;
        }
    }

    private InventoryValuationQuery() {}

    /** presentInventoryValuation。 */
    public static List<Map<String, Object>> presentInventoryValuation(List<BalanceRow> balances,
                                                                      List<LedgerTotal> totals,
                                                                      List<CostEntry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BalanceRow balance : balances) {
            LedgerTotal total = null;
            for (LedgerTotal row : totals) if (row.balanceId.equals(balance.id)) { total = row; break; }
            String ledgerQty = total == null ? "0" : total.ledgerQty;
            String unvalued = total == null ? "0" : total.unvalued;
            boolean complete = String.valueOf(balance.onHand).equals(ledgerQty) && "0".equals(unvalued);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", balance.id);
            view.put("stock", balance.stock);
            view.put("onHand", balance.onHand);
            view.put("dataStatus", complete ? "COMPLETE" : "NEEDS_DATA");
            view.put("unvaluedMovements", unvalued);
            List<Map<String, Object>> values = null;
            if (complete) {
                values = new ArrayList<>();
                for (CostEntry entry : entries) {
                    if (entry.balanceId.equals(balance.id)) {
                        Map<String, Object> value = new LinkedHashMap<>();
                        value.put("currency", entry.currency);
                        value.put("amount", entry.amount);
                        values.add(value);
                    }
                }
            }
            view.put("values", values);
            view.put("basis", "EXPLICIT_APPROVED_STANDARD_COST_LEDGER");
            view.put("postingExecuted", false);
            out.add(view);
        }
        return out;
    }
}

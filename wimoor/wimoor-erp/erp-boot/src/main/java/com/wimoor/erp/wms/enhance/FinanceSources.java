package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务来源统一视图（对应源文件 finance-source-query.ts、receipt-context-query.ts）。
 * 移植了：readWmsFinanceSource 的"只读取不可变计价分录、拒绝浏览器传入凭证或成本、
 * 多币种须先分别核算"校验与 FinanceSource 构造；presentReceipts 的收货单上下文富化。
 * 源端依赖 finance/source-rules 的 FinanceSource 类型，本类以 Map 表达同一结构。
 */
public final class FinanceSources {

    private FinanceSources() {}

    /** 计价分录。 */
    public static final class Entry {
        private final String movementId;
        private final String productRecordId;
        private final String lotId;
        private final String batchNo;
        private final String warehouseId;
        private final String ruleId;
        private final int quantity;
        private final String unitCost;
        private final String amount;
        private final String currency;
        private final String debitAccount;
        private final String creditAccount;

        public Entry(String movementId, String productRecordId, String lotId, String batchNo,
                     String warehouseId, String ruleId, int quantity, String unitCost, String amount,
                     String currency, String debitAccount, String creditAccount) {
            this.movementId = movementId;
            this.productRecordId = productRecordId;
            this.lotId = lotId;
            this.batchNo = batchNo;
            this.warehouseId = warehouseId;
            this.ruleId = ruleId;
            this.quantity = quantity;
            this.unitCost = unitCost;
            this.amount = amount;
            this.currency = currency;
            this.debitAccount = debitAccount;
            this.creditAccount = creditAccount;
        }
    }

    /** 财务草稿。 */
    public static final class Draft {
        private final String id;
        private final String commandId;
        private final String status;
        private final int revision;
        private final String sourceDate;
        private final List<Entry> entries;

        public Draft(String id, String commandId, String status, int revision, String sourceDate,
                     List<Entry> entries) {
            this.id = id;
            this.commandId = commandId;
            this.status = status;
            this.revision = revision;
            this.sourceDate = sourceDate;
            this.entries = entries;
        }
    }

    /** readWmsFinanceSource。 */
    public static Map<String, Object> readWmsFinanceSource(Draft draft) {
        if (draft == null) throw new WmsError("库存核算来源不存在。", 404);
        if (!"DRAFT".equals(draft.status) || draft.entries.isEmpty()) {
            throw new WmsError("库存来源尚未完成计价。", 409);
        }
        java.util.Set<String> currencies = new java.util.HashSet<>();
        for (Entry entry : draft.entries) currencies.add(entry.currency);
        if (currencies.size() != 1) throw new WmsError("库存来源包含多个币种，须先分别核算。", 422);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Entry entry : draft.entries) {
            Map<String, Object> debit = new LinkedHashMap<>();
            debit.put("account", entry.debitAccount);
            debit.put("debit", entry.amount);
            debit.put("credit", "0.000000");
            lines.add(debit);
            Map<String, Object> credit = new LinkedHashMap<>();
            credit.put("account", entry.creditAccount);
            credit.put("debit", "0.000000");
            credit.put("credit", entry.amount);
            lines.add(credit);
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Entry entry : draft.entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("movementId", entry.movementId);
            item.put("productRecordId", entry.productRecordId);
            item.put("lotId", entry.lotId);
            item.put("batchNo", entry.batchNo);
            item.put("warehouseId", entry.warehouseId);
            item.put("ruleId", entry.ruleId);
            item.put("quantity", entry.quantity);
            item.put("unitCost", entry.unitCost);
            item.put("amount", entry.amount);
            evidence.add(item);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "WMS_COMMAND");
        source.put("id", draft.id);
        source.put("sourceKey", "WMS-COMMAND:" + draft.commandId);
        source.put("currency", draft.entries.get(0).currency);
        source.put("sourceDate", draft.sourceDate);
        source.put("lines", lines);
        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("commandId", draft.commandId);
        evidenceMap.put("revision", draft.revision);
        evidenceMap.put("entries", evidence);
        source.put("evidence", evidenceMap);
        return source;
    }

    /** 收货单行（用于 presentReceipts）。 */
    public static final class ReceiptLine {
        private final String id;
        private final String lotId;

        public ReceiptLine(String id, String lotId) {
            this.id = id;
            this.lotId = lotId;
        }

        public String getId() { return id; }
        public String getLotId() { return lotId; }
    }

    /** 收货单。 */
    public static final class Receipt {
        private final String id;
        private final String receivingLocationId;
        private final List<ReceiptLine> lines;

        public Receipt(String id, String receivingLocationId, List<ReceiptLine> lines) {
            this.id = id;
            this.receivingLocationId = receivingLocationId;
            this.lines = lines;
        }
    }

    /** presentReceipts：仅富化可见收货单。 */
    public static List<Map<String, Object>> presentReceipts(
            List<Receipt> receipts,
            Map<String, Map<String, Object>> lotById,
            Map<String, WmsContracts.LocationContext> locationById) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Receipt receipt : receipts) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", receipt.id);
            view.put("receivingLocation", locationById.get(receipt.receivingLocationId));
            List<Map<String, Object>> lines = new ArrayList<>();
            for (ReceiptLine line : receipt.lines) {
                Map<String, Object> lineView = new LinkedHashMap<>();
                lineView.put("id", line.id);
                lineView.put("lotId", line.lotId);
                lineView.put("lot", lotById.get(line.lotId));
                lines.add(lineView);
            }
            view.put("lines", lines);
            out.add(view);
        }
        return out;
    }
}

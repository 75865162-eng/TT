package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.DiscrepancyItem;
import com.wimoor.erp.wms.enhance.WmsContracts.DiscrepancyLine;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.TransferOrderRules.ParsedPayload;
import com.wimoor.erp.wms.enhance.TransferOrderRules.TransferOrderLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调拨差异规则（对应源文件 transfer-order-discrepancy-rules.ts）。
 * 移植了：差异命令解析（分类枚举、明细/分类去重、排序）、sumDiscrepancyByCategory
 * 分类汇总、validateTransferDiscrepancy 整批校验（分类合计须等于净在途量）。
 * 注意：差异命令只重分类净在途量，不产生库存流水。
 */
public final class TransferOrderDiscrepancyRules {

    /** transferDiscrepancyCategories。 */
    public static final List<String> CATEGORIES = List.of("SHORT_RECEIVED", "DAMAGED", "REJECTED");

    private TransferOrderDiscrepancyRules() {}

    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map)) throw new WmsError.Input("差异资料须为对象。");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) raw;
        return value;
    }

    /** 解析后的差异命令。 */
    public static final class TransferDiscrepancyPayload {
        private final Action action;
        private final List<DiscrepancyLine> lines;
        private final String reference;
        private final String reason;

        public TransferDiscrepancyPayload(Action action, List<DiscrepancyLine> lines,
                                          String reference, String reason) {
            this.action = action;
            this.lines = lines;
            this.reference = reference;
            this.reason = reason;
        }

        public Action getAction() { return action; }
        public List<DiscrepancyLine> getLines() { return lines; }
        public String getReference() { return reference; }
        public String getReason() { return reason; }

        public ParsedPayload toParsedPayload() {
            return new ParsedPayload(action, new ArrayList<>(), reference, reason);
        }

        public CommandPayload toCommandPayload() {
            return new CommandPayload(action, reason, null, null, null, null, 0, null, null, 0,
                    null, null, reference, lines, null, null);
        }
    }

    /** parseTransferDiscrepancyPayload。 */
    @SuppressWarnings("unchecked")
    public static TransferDiscrepancyPayload parseTransferDiscrepancyPayload(Object raw) {
        Map<String, Object> value = object(raw);
        if (!"TRANSFER_ORDER_DISCREPANCY".equals(String.valueOf(value.get("action")))) {
            throw new WmsError.Input("差异动作无效。");
        }
        WmsRules.strictKeys(value, "action", "lines", "reference", "reason");
        Object rawLines = value.get("lines");
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > 100) {
            throw new WmsError.Input("差异明细须为 1-100 行。");
        }
        Set<String> seenLines = new HashSet<>();
        List<DiscrepancyLine> lines = new ArrayList<>();
        for (Object rawLine : (List<Object>) rawLines) {
            Map<String, Object> row = object(rawLine);
            WmsRules.strictKeys(row, "lineId", "discrepancies");
            String lineId = WmsRules.requireText(row.get("lineId"), "调拨明细");
            if (!seenLines.add(lineId)) throw new WmsError.Input("差异明细重复。");
            Object rawItems = row.get("discrepancies");
            if (!(rawItems instanceof List) || ((List<?>) rawItems).isEmpty()
                    || ((List<?>) rawItems).size() > 100) {
                throw new WmsError.Input("差异分类须为 1-100 行。");
            }
            Set<String> seenCategories = new HashSet<>();
            List<DiscrepancyItem> discrepancies = new ArrayList<>();
            for (Object rawItem : (List<Object>) rawItems) {
                Map<String, Object> item = object(rawItem);
                WmsRules.strictKeys(item, "category", "quantity", "evidence");
                String category = String.valueOf(item.get("category"));
                if (!CATEGORIES.contains(category)) throw new WmsError.Input("差异分类无效。");
                if (!seenCategories.add(category)) throw new WmsError.Input("同一明细差异分类重复。");
                discrepancies.add(new DiscrepancyItem(category,
                        WmsRules.requirePositiveQuantity(item.get("quantity"), "差异数量"),
                        WmsRules.requireText(item.get("evidence"), "差异凭据", 1000)));
            }
            discrepancies.sort(Comparator.comparing(DiscrepancyItem::getCategory));
            lines.add(new DiscrepancyLine(lineId, discrepancies));
        }
        lines.sort(Comparator.comparing(DiscrepancyLine::getLineId));
        return new TransferDiscrepancyPayload(Action.TRANSFER_ORDER_DISCREPANCY, lines,
                WmsRules.requireText(value.get("reference"), "签收差异凭据", 1000),
                WmsRules.requireText(value.get("reason"), "差异原因", 1000));
    }

    /** sumDiscrepancyByCategory：按分类求和。 */
    public static Map<String, Integer> sumDiscrepancyByCategory(List<DiscrepancyItem> items) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String category : CATEGORIES) totals.put(category, 0);
        for (DiscrepancyItem item : items) {
            totals.put(item.getCategory(), totals.get(item.getCategory()) + item.getQuantity());
        }
        return totals;
    }

    /** validateTransferDiscrepancy：每个明细分类合计须恰好等于当前净在途量。 */
    public static void validateTransferDiscrepancy(List<DiscrepancyLine> lines,
                                                    List<TransferOrderLine> current) {
        for (DiscrepancyLine line : lines) {
            TransferOrderLine state = null;
            for (TransferOrderLine row : current) {
                if (row.getId().equals(line.getLineId())) { state = row; break; }
            }
            if (state == null) throw new WmsError.Input("差异明细不属于本调拨单。", 422);
            int netInTransit = state.getShippedQty() - state.getReceivedQty() - state.resolvedDiscrepancy();
            int total = 0;
            for (DiscrepancyItem item : line.getDiscrepancies()) total += item.getQuantity();
            if (total != netInTransit) throw new WmsError.Input("分类合计须恰好等于当前净在途量。", 422);
        }
    }

    /** 供 WmsRules 复用的分类集合。 */
    public static boolean isCategory(String category) {
        return CATEGORIES.contains(category);
    }
}

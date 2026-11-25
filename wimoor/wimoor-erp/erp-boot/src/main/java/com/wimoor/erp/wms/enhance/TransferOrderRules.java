package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.LineQuantity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调拨单规则（对应源文件 transfer-order-rules.ts）。
 * 移植了：transferOrderStatus 状态机（COMPLETED/PARTIALLY_RECEIVED/IN_TRANSIT/
 * PARTIALLY_SHIPPED/DRAFT）、草稿与作业命令的严格解析（去重、排序、100 行上限）、
 * validateTransferQuantities 发出/签收剩余量校验。
 */
public final class TransferOrderRules {

    /** transfer-order-rules.ts: transferOrderActions。 */
    public static final List<Action> TRANSFER_ORDER_ACTIONS = List.of(
            Action.TRANSFER_ORDER_SHIP, Action.TRANSFER_ORDER_RECEIVE,
            Action.TRANSFER_ORDER_CANCEL, Action.TRANSFER_ORDER_DISCREPANCY);

    private TransferOrderRules() {}

    /** TransferOrderLine：id/quantity/shippedQty/receivedQty/discrepancyQty。 */
    public static final class TransferOrderLine {
        private final String id;
        private final int quantity;
        private final int shippedQty;
        private final int receivedQty;
        private final int discrepancyQty;

        public TransferOrderLine(String id, int quantity, int shippedQty, int receivedQty, int discrepancyQty) {
            this.id = id;
            this.quantity = quantity;
            this.shippedQty = shippedQty;
            this.receivedQty = receivedQty;
            this.discrepancyQty = discrepancyQty;
        }

        public String getId() { return id; }
        public int getQuantity() { return quantity; }
        public int getShippedQty() { return shippedQty; }
        public int getReceivedQty() { return receivedQty; }
        public int getDiscrepancyQty() { return discrepancyQty; }

        /** 兼容旧调用方：discrepancyQty 缺失按 0。 */
        public int resolvedDiscrepancy() { return discrepancyQty; }
    }

    /** 草稿行。 */
    public static final class DraftLine {
        private final String sourceBalanceId;
        private final String targetLocationId;
        private final int quantity;

        public DraftLine(String sourceBalanceId, String targetLocationId, int quantity) {
            this.sourceBalanceId = sourceBalanceId;
            this.targetLocationId = targetLocationId;
            this.quantity = quantity;
        }

        public String getSourceBalanceId() { return sourceBalanceId; }
        public String getTargetLocationId() { return targetLocationId; }
        public int getQuantity() { return quantity; }
    }

    /** 解析后的草稿。 */
    public static final class ParsedDraft {
        private final String number;
        private final String reason;
        private final String idempotencyKey;
        private final List<DraftLine> lines;

        public ParsedDraft(String number, String reason, String idempotencyKey, List<DraftLine> lines) {
            this.number = number;
            this.reason = reason;
            this.idempotencyKey = idempotencyKey;
            this.lines = lines;
        }

        public String getNumber() { return number; }
        public String getReason() { return reason; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public List<DraftLine> getLines() { return lines; }
    }

    /** 解析后的作业命令。 */
    public static final class ParsedPayload {
        private final Action action;
        private final List<LineQuantity> lines;
        private final String reference;
        private final String reason;

        public ParsedPayload(Action action, List<LineQuantity> lines, String reference, String reason) {
            this.action = action;
            this.lines = lines;
            this.reference = reference;
            this.reason = reason;
        }

        public Action getAction() { return action; }
        public List<LineQuantity> getLines() { return lines; }
        public String getReference() { return reference; }
        public String getReason() { return reason; }

        public CommandPayload toCommandPayload() {
            return new CommandPayload(action, reason != null ? reason : "",
                    lines, null, null, null, 0, null, null, 0, null, null, reference,
                    null, null, null);
        }
    }

    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map)) throw new WmsError.Input("调拨资料须为对象。");
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) raw;
        return value;
    }

    /** transferOrderStatus 状态机。 */
    public static String transferOrderStatus(List<TransferOrderLine> lines) {
        boolean allShippedAndSettled = true;
        boolean anyReceivedOrDiscrepancy = false;
        boolean allShipped = true;
        boolean anyShipped = false;
        for (TransferOrderLine line : lines) {
            if (line.getShippedQty() != line.getQuantity()
                    || line.getReceivedQty() + line.resolvedDiscrepancy() != line.getShippedQty()) {
                allShippedAndSettled = false;
            }
            if (line.getReceivedQty() > 0 || line.resolvedDiscrepancy() > 0) {
                anyReceivedOrDiscrepancy = true;
            }
            if (line.getShippedQty() != line.getQuantity()) allShipped = false;
            if (line.getShippedQty() > 0) anyShipped = true;
        }
        if (allShippedAndSettled) return "COMPLETED";
        if (anyReceivedOrDiscrepancy) return "PARTIALLY_RECEIVED";
        if (allShipped) return "IN_TRANSIT";
        return anyShipped ? "PARTIALLY_SHIPPED" : "DRAFT";
    }

    /** parseTransferOrderDraft：草稿解析，按来源/目的排序去重。 */
    @SuppressWarnings("unchecked")
    public static ParsedDraft parseTransferOrderDraft(Object raw) {
        Map<String, Object> input = object(raw);
        WmsRules.strictKeys(input, "number", "reason", "idempotencyKey", "lines");
        Object rawLines = input.get("lines");
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > 100) {
            throw new WmsError.Input("调拨明细须为 1-100 行。");
        }
        Set<String> seen = new HashSet<>();
        List<DraftLine> lines = new ArrayList<>();
        for (Object value : (List<Object>) rawLines) {
            Map<String, Object> row = object(value);
            WmsRules.strictKeys(row, "sourceBalanceId", "targetLocationId", "quantity");
            String sourceBalanceId = WmsRules.requireText(row.get("sourceBalanceId"), "来源余额");
            String targetLocationId = WmsRules.requireText(row.get("targetLocationId"), "目的库位");
            String key = WmsRules.stableJson(Arrays.asList(sourceBalanceId, targetLocationId));
            if (!seen.add(key)) throw new WmsError.Input("同一来源及目的库位请合并填写。");
            lines.add(new DraftLine(sourceBalanceId, targetLocationId,
                    WmsRules.requirePositiveQuantity(row.get("quantity"))));
        }
        lines.sort(Comparator.comparing(DraftLine::getSourceBalanceId)
                .thenComparing(DraftLine::getTargetLocationId));
        return new ParsedDraft(WmsRules.requireText(input.get("number"), "调拨单号"),
                WmsRules.requireText(input.get("reason"), "调拨依据", 1000),
                WmsRules.requireText(input.get("idempotencyKey"), "幂等键"), lines);
    }

    /** parseTransferOrderPayload：作业命令解析。 */
    @SuppressWarnings("unchecked")
    public static ParsedPayload parseTransferOrderPayload(Object raw) {
        Map<String, Object> value = object(raw);
        Action action = Action.of(String.valueOf(value.get("action")));
        if (action == Action.TRANSFER_ORDER_CANCEL) {
            WmsRules.strictKeys(value, "action", "reason");
            return new ParsedPayload(action, new ArrayList<>(), null,
                    WmsRules.requireText(value.get("reason"), "取消原因", 1000));
        }
        if (action == Action.TRANSFER_ORDER_DISCREPANCY) {
            return TransferOrderDiscrepancyRules.parseTransferDiscrepancyPayload(value).toParsedPayload();
        }
        if (action != Action.TRANSFER_ORDER_SHIP && action != Action.TRANSFER_ORDER_RECEIVE) {
            throw new WmsError.Input("调拨动作无效。");
        }
        WmsRules.strictKeys(value, "action", "lines", "reference");
        Object rawLines = value.get("lines");
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > 100) {
            throw new WmsError.Input("调拨作业须为 1-100 行。");
        }
        Set<String> seen = new HashSet<>();
        List<LineQuantity> lines = new ArrayList<>();
        for (Object item : (List<Object>) rawLines) {
            Map<String, Object> row = object(item);
            WmsRules.strictKeys(row, "lineId", "quantity");
            String lineId = WmsRules.requireText(row.get("lineId"), "调拨明细");
            if (!seen.add(lineId)) throw new WmsError.Input("作业明细重复。");
            lines.add(new LineQuantity(lineId, WmsRules.requirePositiveQuantity(row.get("quantity"))));
        }
        lines.sort(Comparator.comparing(LineQuantity::getLineId));
        String referenceLabel = action == Action.TRANSFER_ORDER_SHIP ? "发运凭据" : "签收凭据";
        return new ParsedPayload(action, lines,
                WmsRules.requireText(value.get("reference"), referenceLabel, 1000), null);
    }

    /** validateTransferQuantities：发出/签收数量不得超过剩余量。 */
    public static void validateTransferQuantities(Action action, List<LineQuantity> selected,
                                                  List<TransferOrderLine> lines) {
        for (LineQuantity item : selected) {
            TransferOrderLine line = null;
            for (TransferOrderLine candidate : lines) {
                if (candidate.getId().equals(item.getLineId())) { line = candidate; break; }
            }
            if (line == null) throw new WmsError.Input("明细不属于本调拨单。", 422);
            int remaining = action == Action.TRANSFER_ORDER_SHIP
                    ? line.getQuantity() - line.getShippedQty()
                    : line.getShippedQty() - line.getReceivedQty() - line.resolvedDiscrepancy();
            if (item.getQuantity() > remaining) {
                throw new WmsError.Input(action == Action.TRANSFER_ORDER_SHIP
                        ? "发出超过计划剩余量。" : "签收超过在途剩余量。", 422);
            }
        }
    }
}

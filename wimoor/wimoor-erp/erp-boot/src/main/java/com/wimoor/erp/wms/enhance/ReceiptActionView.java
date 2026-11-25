package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.ActionValue;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.InspectLine;
import com.wimoor.erp.wms.enhance.WmsContracts.LineQuantity;
import com.wimoor.erp.wms.enhance.WmsContracts.PutawayLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 收货作业表单规则（对应源文件 receipt-action-view.ts）。
 * 移植了 receiptActionRemaining 四种动作的剩余量计算，以及 receiptActionPayload
 * 的表单校验（数量须为 0..2^31-1 安全整数、作业量不超剩余量、至少一行有效、
 * 上架库位必填）。仅表单校验，库存权威仍在 application-service。
 */
public final class ReceiptActionView {

    private ReceiptActionView() {}

    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return (long) Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    /** receiptActionRemaining。 */
    public static int receiptActionRemaining(Map<String, Object> line, Action action) {
        switch (action) {
            case RECEIVE:
                return (int) (number(line.get("expectedQty")) - number(line.getOrDefault("receivedQty", 0)));
            case INSPECT:
                return (int) (number(line.get("receivedQty")) - number(line.get("acceptedQty"))
                        - number(line.get("rejectedQty")));
            case RETURN_REJECTED:
                return (int) (number(line.get("rejectedQty")) - number(line.getOrDefault("returnedQty", 0)));
            default: // PUTAWAY
                return (int) (number(line.get("acceptedQty")) - number(line.get("putawayQty")));
        }
    }

    /** 表单数量解析：非负安全整数且 <= 2^31-1。 */
    private static int quantity(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new WmsError("数量必须是有效的非负整数。");
        long n;
        try {
            n = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new WmsError("数量必须是有效的非负整数。");
        }
        if (n < 0 || n > WmsRules.WMS_QUANTITY_LIMIT) throw new WmsError("数量必须是有效的非负整数。");
        return (int) n;
    }

    /** receiptActionPayload。 */
    public static CommandPayload receiptActionPayload(List<Map<String, Object>> lines, Action action,
                                                      Map<String, ActionValue> values) {
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            String lineId = String.valueOf(line.get("id"));
            ActionValue value = values.get(lineId);
            int remaining = receiptActionRemaining(line, action);
            int amount;
            if (action == Action.INSPECT) {
                amount = quantity(value == null ? null : value.getAcceptedQty())
                        + quantity(value == null ? null : value.getRejectedQty());
            } else {
                amount = quantity(value == null ? null : value.getQuantity());
            }
            if (remaining < 0 || amount > remaining) {
                throw new WmsError("作业数量不能超过该明细的剩余数量，请刷新单据后重试。");
            }
            if (amount > 0) selected.add(line);
        }
        if (selected.isEmpty()) {
            throw new WmsError(action == Action.RECEIVE
                    ? "至少填写一行大于零的实收数量；零收明细将记录为短收。"
                    : "请填写至少一行有效数量。");
        }
        if (action == Action.INSPECT) {
            List<InspectLine> inspectLines = new ArrayList<>();
            for (Map<String, Object> line : selected) {
                String lineId = String.valueOf(line.get("id"));
                ActionValue value = values.get(lineId);
                inspectLines.add(new InspectLine(lineId, quantity(value.getAcceptedQty()),
                        quantity(value.getRejectedQty())));
            }
            return new CommandPayload(action, null, null, inspectLines, null, null, 0, null, null, 0,
                    null, null, null, null, null, null);
        }
        if (action == Action.PUTAWAY) {
            List<PutawayLine> putawayLines = new ArrayList<>();
            for (Map<String, Object> line : selected) {
                String lineId = String.valueOf(line.get("id"));
                ActionValue value = values.get(lineId);
                if (value == null || value.getLocationId() == null || value.getLocationId().trim().isEmpty()) {
                    throw new WmsError("请选择有上架数量的明细的目标库位。");
                }
                putawayLines.add(new PutawayLine(lineId, value.getLocationId(), quantity(value.getQuantity())));
            }
            return new CommandPayload(action, null, null, null, putawayLines, null, 0, null, null, 0,
                    null, null, null, null, null, null);
        }
        List<LineQuantity> simpleLines = new ArrayList<>();
        for (Map<String, Object> line : selected) {
            String lineId = String.valueOf(line.get("id"));
            simpleLines.add(new LineQuantity(lineId, quantity(values.get(lineId).getQuantity())));
        }
        return new CommandPayload(action, null, simpleLines, null, null, null, 0, null, null, 0,
                null, null, null, null, null, null);
    }
}

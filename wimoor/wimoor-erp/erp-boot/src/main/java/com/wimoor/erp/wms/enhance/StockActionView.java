package com.wimoor.erp.wms.enhance;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存动作展示限额（对应源文件 stock-action-view.ts）。
 * 移植了 stockActionLabels 文案与 stockActionLimit：
 * 冻结库位返回 0；字段非法返回 null；各动作的可操作上限按数量桶计算。
 * 仅作展示限制，事务权威仍在 application-service。
 */
public final class StockActionView {

    /** StockAction 枚举。 */
    public enum StockAction {
        SKU_CONVERT, RESERVE, TRANSFER, PICK, UNPICK, DISPATCH, RELEASE
    }

    private static final Map<StockAction, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(StockAction.SKU_CONVERT, "SKU 转换");
        LABELS.put(StockAction.RESERVE, "预占");
        LABELS.put(StockAction.TRANSFER, "调拨");
        LABELS.put(StockAction.PICK, "拣货");
        LABELS.put(StockAction.UNPICK, "取消拣货");
        LABELS.put(StockAction.DISPATCH, "出库");
        LABELS.put(StockAction.RELEASE, "释放预占");
    }

    private StockActionView() {}

    public static Map<StockAction, String> stockActionLabels() {
        return LABELS;
    }

    /** stockActionLimit。 */
    public static Integer stockActionLimit(Map<String, Object> item, StockAction action) {
        Object stock = item.get("stock");
        if (stock instanceof Map) {
            Map<?, ?> stockMap = (Map<?, ?>) stock;
            if (Boolean.TRUE.equals(stockMap.get("locationFrozen"))) return 0;
        }
        List<String> keys;
        boolean stockSide = action == StockAction.SKU_CONVERT || action == StockAction.RESERVE
                || action == StockAction.TRANSFER;
        if (stockSide) {
            keys = Arrays.asList("onHand", "pendingQc", "pendingPutaway", "rejected", "reserved");
        } else {
            keys = Arrays.asList("requestedQty", "releasedQty", "dispatchedQty", "pickedQty");
        }
        long[] values = new long[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            Object value = item.get(keys.get(i));
            if (!(value instanceof Number)) return null;
            double d = ((Number) value).doubleValue();
            if (d != Math.floor(d) || ((Number) value).longValue() < 0) return null;
            values[i] = ((Number) value).longValue();
        }
        long limit;
        if (stockSide) {
            limit = values[0] - values[1] - values[2] - values[3] - values[4];
        } else if (action == StockAction.PICK || action == StockAction.RELEASE) {
            limit = values[0] - values[1] - values[2] - values[3];
        } else {
            limit = values[3];
        }
        return limit < 0 ? null : (int) limit;
    }
}

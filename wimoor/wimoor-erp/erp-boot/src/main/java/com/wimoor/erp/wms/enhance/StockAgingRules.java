package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库龄分桶估算（移植自条条 ERP src/lib/wms/stock-aging-rules.ts）。
 * 算法来自 Wimoor WhseUnsalableReportMapper.findByCondition 的"快照−出库"估算：
 * 这是一个仓库周转估算，不是 FIFO 批次日期，也不是亚马逊零售销量。
 * 窗口：30/60/90/180/365 天 + 365+，共 6 个桶。
 */
public final class StockAgingRules {

    public static final List<Integer> AGING_WINDOWS = List.of(30, 60, 90, 180, 365);

    public static final class WindowEvidence {
        private final int days;
        private final String snapshotQty;   // null 表示缺失
        private final String outboundQty;   // null 表示缺失
        public WindowEvidence(int days, String snapshotQty, String outboundQty) {
            this.days = days; this.snapshotQty = snapshotQty; this.outboundQty = outboundQty;
        }
        public int getDays() { return days; }
    }

    private StockAgingRules() {}

    private static BigInteger parse(String v) {
        if (v == null || !v.matches("\\d+")) throw new IllegalArgumentException("周转数量须为非负整数字符串");
        return new BigInteger(v);
    }

    /** 数据不完整或桶校验失败时返回 null（与源端一致，绝不编造）。 */
    public static Map<String, String> estimate(String quantityRaw, List<WindowEvidence> windows) {
        BigInteger quantity = parse(quantityRaw);
        BigInteger[] survivors = new BigInteger[5];
        for (int i = 0; i < AGING_WINDOWS.size(); i++) {
            int days = AGING_WINDOWS.get(i);
            WindowEvidence row = null;
            for (WindowEvidence w : windows) if (w.getDays() == days) { row = w; break; }
            if (row == null || row.snapshotQty == null || row.outboundQty == null) return null;
            BigInteger diff = parse(row.snapshotQty).subtract(parse(row.outboundQty));
            survivors[i] = diff.signum() > 0 ? diff : BigInteger.ZERO;
        }
        BigInteger[] bands = new BigInteger[6];
        bands[0] = quantity.subtract(survivors[0]);
        for (int i = 0; i < 4; i++) {
            bands[i + 1] = survivors[i].compareTo(survivors[i + 1]) >= 0
                    ? survivors[i].subtract(survivors[i + 1]) : survivors[i];
        }
        bands[5] = survivors[4];
        BigInteger sum = BigInteger.ZERO;
        for (BigInteger b : bands) {
            if (b.signum() < 0) return null;
            sum = sum.add(b);
        }
        if (!sum.equals(quantity)) return null;

        Map<String, String> out = new LinkedHashMap<>();
        out.put("days0To30", bands[0].toString());
        out.put("days30To60", bands[1].toString());
        out.put("days60To90", bands[2].toString());
        out.put("days90To180", bands[3].toString());
        out.put("days180To365", bands[4].toString());
        out.put("days365Plus", bands[5].toString());
        // 90 天以上超量 = max(总量 − 90 天窗口出库量, 0)
        String out90 = null;
        for (WindowEvidence w : windows) if (w.getDays() == 90) out90 = w.outboundQty;
        BigInteger excess = quantity.subtract(parse(out90));
        out.put("excessOver90DayOutbound", (excess.signum() > 0 ? excess : BigInteger.ZERO).toString());
        return out;
    }
}

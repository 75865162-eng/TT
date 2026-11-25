package com.wimoor.ai.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derived advertising metrics, ported from the tiaotiao ERP amazon-ads/metrics.ts.
 *
 * <p>Rules preserved exactly:
 * <ul>
 *   <li>null / undefined / empty / non-numeric input -> null (never 0, never throw)</li>
 *   <li>ratio = numerator / denominator * multiplier, returned as a transport-safe
 *       decimal string with at most 6 decimal places and trailing zeros stripped</li>
 *   <li>zero denominator -> null</li>
 *   <li>percentage metrics are in percentage points (12.5 means 12.5%)</li>
 * </ul>
 * These rules matter: a missing source metric must not be reported as a real 0.
 */
public final class AdsMetrics {

    private static final int SCALE = 6;

    private AdsMetrics() {}

    /** Input metrics; all fields optional, unknown strings are treated as missing. */
    public static final class Input {
        public Object impressions;
        public Object clicks;
        public Object spend;
        public Object orders;
        public Object sales;
    }

    /** Derived metrics; null means the source value or denominator was unavailable. */
    public static final class Derived {
        public final String ctr;
        public final String cpc;
        public final String cvr;
        public final String acos;
        public final String roas;

        Derived(String ctr, String cpc, String cvr, String acos, String roas) {
            this.ctr = ctr;
            this.cpc = cpc;
            this.cvr = cvr;
            this.acos = acos;
            this.roas = roas;
        }

        public Map<String, String> toMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("ctr", ctr);
            m.put("cpc", cpc);
            m.put("cvr", cvr);
            m.put("acos", acos);
            m.put("roas", roas);
            return m;
        }
    }

    public static Derived derive(Input in) {
        if (in == null) in = new Input();
        return new Derived(
                ratio(in.clicks, in.impressions, 100),
                ratio(in.spend, in.clicks, 1),
                ratio(in.orders, in.clicks, 100),
                ratio(in.spend, in.sales, 100),
                ratio(in.sales, in.spend, 1));
    }

    private static String ratio(Object numerator, Object denominator, int multiplier) {
        BigDecimal top = decimal(numerator);
        BigDecimal bottom = decimal(denominator);
        if (top == null || bottom == null || BigDecimal.ZERO.compareTo(bottom) == 0) return null;
        BigDecimal value = top.divide(bottom, SCALE + 6, RoundingMode.HALF_UP)
                              .multiply(BigDecimal.valueOf(multiplier))
                              .setScale(SCALE, RoundingMode.HALF_UP);
        String s = value.stripTrailingZeros().toPlainString();
        return s.isEmpty() ? "0" : s;
    }

    /** Mirrors Prisma.Decimal(value) + isFinite(): non-numeric -> null.
     *  BigDecimal cannot represent Infinity/NaN (constructor throws), so any
     *  successfully constructed value is finite by definition. */
    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number || value instanceof String) {
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

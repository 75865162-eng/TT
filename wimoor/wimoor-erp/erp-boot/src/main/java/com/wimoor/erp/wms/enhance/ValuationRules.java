package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;

/**
 * 货值计价规则（移植自条条 ERP src/lib/wms/valuation-rules.ts）。
 * 金额一律用BigInteger微单位（1e-6）精确计算，避免浮点误差；
 * 单位成本最多六位小数，计价金额有上限（10^30 微单位）。
 */
public final class ValuationRules {

    private static final BigInteger MICROS = BigInteger.valueOf(1_000_000L);
    private static final BigInteger LIMIT = new BigInteger("1000000000000000000000000000000");

    private ValuationRules() {}

    /** 单位成本字符串 → 微单位整数。如 "1.5" → 1500000。 */
    public static BigInteger unitCostMicros(String raw) {
        if (raw == null || !raw.matches("\\d{1,18}(?:\\.\\d{1,6})?")) {
            throw new IllegalArgumentException("单位成本须为非负十进制字符串，最多六位小数");
        }
        String[] parts = raw.split("\\.");
        String whole = parts[0];
        String frac = parts.length > 1 ? parts[1] : "";
        while (frac.length() < 6) frac += "0";
        return new BigInteger(whole + frac);
    }

    /** 微单位整数 → 定点字符串（六位小数，保留前导零）。 */
    public static String moneyString(BigInteger micros) {
        String sign = micros.signum() < 0 ? "-" : "";
        BigInteger v = micros.abs();
        return sign + v.divide(MICROS) + "." + String.format("%06d", v.remainder(MICROS));
    }

    /** 计价金额 = 单位成本 × |数量|；数量须为安全整数。 */
    public static String valueQuantity(String unitCost, long quantity) {
        BigInteger value = unitCostMicros(unitCost).multiply(BigInteger.valueOf(Math.abs(quantity)));
        if (value.compareTo(LIMIT) >= 0) throw new IllegalArgumentException("计价金额超出范围");
        return moneyString(value);
    }
}

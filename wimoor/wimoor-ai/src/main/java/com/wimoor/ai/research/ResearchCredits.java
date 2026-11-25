package com.wimoor.ai.research;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 研究服务积分规则（移植自条条 ERP src/lib/selection-research/credits.ts）。
 * 注意：仅研究服务成本；采购、关税、物流属于 economics 域，不在此处。
 * 1 研究积分 = ¥0.01；单次按 0.001 积分精度四舍五入，聚合用整数 milli-credits。
 */
public final class ResearchCredits {

    public static final int VERSION = 1;
    public static final double CNY_PER_CREDIT = 0.01;
    public static final int PRECISION = 3;

    private static final Set<String> ALLOWED_UNITS = Set.of("CNY", "USD", "supplier_credits");
    private static final Set<String> ALLOWED_STATUS = Set.of("estimated", "confirmed");
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "amount", "unit", "cnyPerUnit", "rateDate", "rateSource", "receipt", "status");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    private ResearchCredits() {}

    /** 研究服务收费记录。保留原始收据与换算，绝不把"一次请求"等同于"一分积分"。 */
    public static final class ResearchCharge {
        private final double amount;
        private final String unit;
        private final double cnyPerUnit;
        private final String rateDate;
        private final String rateSource;
        private final String receipt;
        private final String status;

        public ResearchCharge(double amount, String unit, double cnyPerUnit, String rateDate,
                              String rateSource, String receipt, String status) {
            this.amount = amount;
            this.unit = unit;
            this.cnyPerUnit = cnyPerUnit;
            this.rateDate = rateDate;
            this.rateSource = rateSource;
            this.receipt = receipt;
            this.status = status;
        }
    }

    /** 校验收费记录并换算为积分；非法输入抛 IllegalArgumentException(INVALID_RESEARCH_CHARGE)。 */
    public static double chargeCredits(ResearchCharge c) {
        if (c == null) throw invalid();
        if (!Double.isFinite(c.amount) || c.amount < 0) throw invalid();
        if (!Double.isFinite(c.cnyPerUnit) || c.cnyPerUnit <= 0) throw invalid();
        if (!ALLOWED_UNITS.contains(c.unit)) throw invalid();
        if ("CNY".equals(c.unit) && c.cnyPerUnit != 1.0d) throw invalid();
        if (!ALLOWED_STATUS.contains(c.status)) throw invalid();
        if (!validDate(c.rateDate)) throw invalid();
        if (!validText(c.rateSource) || !validText(c.receipt)) throw invalid();
        // 单次四舍五入到 0.001 积分
        long milliCredits = Math.round(c.amount * c.cnyPerUnit * 100_000d);
        if (!isSafeInteger(milliCredits)) throw invalid();
        return milliCredits / 1000.0d;
    }

    private static boolean validDate(String s) {
        if (s == null || !s.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            LocalDate d = LocalDate.parse(s, DATE);
            return DATE.format(d).equals(s);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validText(String s) {
        return s != null && !s.trim().isEmpty() && s.length() <= 2000;
    }

    private static boolean isSafeInteger(long v) {
        return Math.abs(v) <= 9007199254740991L;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_RESEARCH_CHARGE");
    }
}

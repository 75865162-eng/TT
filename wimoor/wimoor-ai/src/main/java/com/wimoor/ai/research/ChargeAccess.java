package com.wimoor.ai.research;

import java.lang.reflect.Field;

/**
 * 读取已移植 ResearchCharge 的字段（该类保持原样、不新增 getter）。
 * 用于预算汇总与报告渲染区分 confirmed / estimated。
 */
public final class ChargeAccess {

    private ChargeAccess() {}

    public static String status(ResearchCredits.ResearchCharge charge) {
        return read(charge, "status");
    }

    public static String unit(ResearchCredits.ResearchCharge charge) {
        return read(charge, "unit");
    }

    public static double amount(ResearchCredits.ResearchCharge charge) {
        return Double.parseDouble(read(charge, "amount"));
    }

    public static double cnyPerUnit(ResearchCredits.ResearchCharge charge) {
        return Double.parseDouble(read(charge, "cnyPerUnit"));
    }

    public static String rateDate(ResearchCredits.ResearchCharge charge) {
        return read(charge, "rateDate");
    }

    public static String rateSource(ResearchCredits.ResearchCharge charge) {
        return read(charge, "rateSource");
    }

    public static String receipt(ResearchCredits.ResearchCharge charge) {
        return read(charge, "receipt");
    }

    private static String read(ResearchCredits.ResearchCharge charge, String fieldName) {
        if (charge == null) return null;
        try {
            Field f = ResearchCredits.ResearchCharge.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(charge);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            throw new ResearchError("INVALID_RESEARCH_CHARGE", e);
        }
    }
}

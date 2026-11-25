package com.wimoor.ai.research;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResearchRulesTest {

    @Test
    void callPlanTotalsMatchSource() {
        assertEquals(18, ResearchCallPlan.totalMin());
        assertEquals(30, ResearchCallPlan.totalMax());
        assertEquals(0, ResearchCallPlan.forSkill("tariff-calculator-amazon").getMax());
    }

    @Test
    void cnyChargeConversion() {
        // 1 CNY = 100 积分（cnyPerCredit=0.01）
        ResearchCredits.ResearchCharge c =
                new ResearchCredits.ResearchCharge(1.0, "CNY", 1.0, "2026-11-25", "receipt#1", "供应商回执A", "confirmed");
        assertEquals(100.0, ResearchCredits.chargeCredits(c), 1e-9);
    }

    @Test
    void usdChargeConversionRounded() {
        ResearchCredits.ResearchCharge c =
                new ResearchCredits.ResearchCharge(0.123, "USD", 7.2, "2026-11-25", "rate src", "receipt#2", "estimated");
        // 0.123*7.2=0.8856 CNY /0.01 = 88.56 积分 → 88.56
        assertEquals(88.56, ResearchCredits.chargeCredits(c), 1e-9);
    }

    @Test
    void invalidChargesRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ResearchCredits.chargeCredits(new ResearchCredits.ResearchCharge(-1, "CNY", 1, "2026-11-25", "s", "r", "confirmed")));
        assertThrows(IllegalArgumentException.class, () ->
                ResearchCredits.chargeCredits(new ResearchCredits.ResearchCharge(1, "CNY", 2, "2026-11-25", "s", "r", "confirmed")));
        assertThrows(IllegalArgumentException.class, () ->
                ResearchCredits.chargeCredits(new ResearchCredits.ResearchCharge(1, "BTC", 1, "2026-11-25", "s", "r", "confirmed")));
        assertThrows(IllegalArgumentException.class, () ->
                ResearchCredits.chargeCredits(new ResearchCredits.ResearchCharge(1, "CNY", 1, "2026-13-40", "s", "r", "confirmed")));
    }

    @Test
    void feeReviewOnlyWhenExplicitlyRequired() {
        assertTrue(FeePolicy.feeReviewRequired("付费调用需先审批", ""));
        assertTrue(FeePolicy.feeReviewRequired("", "paid tools require approval before use"));
        assertFalse(FeePolicy.feeReviewRequired("无需费用审批", ""));
        assertFalse(FeePolicy.feeReviewRequired("查询库存和广告数据", "分析竞品差评"));
        assertFalse(FeePolicy.feeReviewRequired("do not need fee confirmation", ""));
    }
}

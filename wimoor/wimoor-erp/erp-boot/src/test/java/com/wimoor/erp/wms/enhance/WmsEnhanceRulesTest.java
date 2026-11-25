package com.wimoor.erp.wms.enhance;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WmsEnhanceRulesTest {

    @Test
    void unitCostMicrosPrecision() {
        assertEquals(BigInteger.valueOf(1_000_000L), ValuationRules.unitCostMicros("1"));
        assertEquals(BigInteger.valueOf(1_500_000L), ValuationRules.unitCostMicros("1.5"));
        assertEquals(BigInteger.valueOf(123456L), ValuationRules.unitCostMicros("0.123456"));
        assertThrows(IllegalArgumentException.class, () -> ValuationRules.unitCostMicros("1.1234567"));
        assertThrows(IllegalArgumentException.class, () -> ValuationRules.unitCostMicros("-1"));
    }

    @Test
    void moneyStringKeepsSixDecimals() {
        assertEquals("1.500000", ValuationRules.moneyString(BigInteger.valueOf(1_500_000L)));
        assertEquals("0.000001", ValuationRules.moneyString(BigInteger.valueOf(1L)));
        assertEquals("-2.000000", ValuationRules.moneyString(BigInteger.valueOf(-2_000_000L)));
    }

    @Test
    void valueQuantity() {
        // 1.5 * 10 = 15.000000
        assertEquals("15.000000", ValuationRules.valueQuantity("1.5", 10));
        assertEquals("0.000000", ValuationRules.valueQuantity("1.5", 0));
    }

    @Test
    void stockAgingBuckets() {
        // 总量 100；各窗口 survivor（snapshot-outbound）依次：70/50/30/10/5
        List<StockAgingRules.WindowEvidence> w = List.of(
                new StockAgingRules.WindowEvidence(30, "170", "100"),  // survivor 70
                new StockAgingRules.WindowEvidence(60, "150", "100"),  // 50
                new StockAgingRules.WindowEvidence(90, "130", "100"),  // 30
                new StockAgingRules.WindowEvidence(180, "110", "100"), // 10
                new StockAgingRules.WindowEvidence(365, "105", "100")  // 5
        );
        Map<String, String> r = StockAgingRules.estimate("100", w);
        assertNotNull(r);
        // bands: [100-70=30, 70-50=20, 50-30=20, 30-10=20, 10-5=5, 5]
        assertEquals("30", r.get("days0To30"));
        assertEquals("20", r.get("days30To60"));
        assertEquals("20", r.get("days60To90"));
        assertEquals("20", r.get("days90To180"));
        assertEquals("5", r.get("days180To365"));
        assertEquals("5", r.get("days365Plus"));
        // excessOver90 = max(100 - outbound90(100), 0) = 0
        assertEquals("0", r.get("excessOver90DayOutbound"));
    }

    @Test
    void stockAgingIncompleteReturnsNull() {
        List<StockAgingRules.WindowEvidence> w = List.of(
                new StockAgingRules.WindowEvidence(30, "170", "100")
                // 缺 60/90/180/365
        );
        assertNull(StockAgingRules.estimate("100", w));
    }
}

package com.wimoor.ai.report;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies P4 report rules against the source (TS) definitions' exact expected values. */
class ReportRulesTest {

    @Test
    void derivesPercentageAndRatioMetrics() {
        AdsMetrics.Input in = new AdsMetrics.Input();
        in.impressions = "10000";
        in.clicks = "500";
        in.spend = "100";
        in.orders = "25";
        in.sales = "500";

        AdsMetrics.Derived d = AdsMetrics.derive(in);
        assertEquals("5", d.ctr, "CTR = 500/10000*100 = 5%");
        assertEquals("0.2", d.cpc, "CPC = 100/500 = 0.2");
        assertEquals("5", d.cvr, "CVR = 25/500*100 = 5%");
        assertEquals("20", d.acos, "ACOS = 100/500*100 = 20%");
        assertEquals("5", d.roas, "ROAS = 500/100 = 5");
    }

    @Test
    void missingOrZeroDenominatorYieldsNull() {
        AdsMetrics.Input partial = new AdsMetrics.Input();
        partial.impressions = "";   // empty -> null
        partial.clicks = "not-a-number";
        partial.spend = 100;
        partial.sales = null;
        AdsMetrics.Derived d = AdsMetrics.derive(partial);
        assertNull(d.ctr, "empty impressions -> missing");
        assertNull(d.cpc, "non-numeric clicks -> missing");
        assertNull(d.acos, "null sales -> missing");
        assertNull(d.roas, "null sales -> roas missing");
    }

    @Test
    void zeroDenominatorIsMissingNotZero() {
        AdsMetrics.Input in = new AdsMetrics.Input();
        in.clicks = "10";
        in.impressions = 0;
        in.spend = "5";
        AdsMetrics.Derived d = AdsMetrics.derive(in);
        assertNull(d.ctr, "zero impressions -> null, NOT 0");
        assertEquals("0.5", d.cpc, "CPC = 5/10 = 0.5");
    }

    @Test
    void completenessStatusesFollowObservedFacts() {
        Map<String, BigInteger> provided = new HashMap<>();
        for (String f : ReportCompleteness.METRIC_FIELDS) provided.put(f, BigInteger.ZERO);

        ReportCompleteness.Report r0 = ReportCompleteness.evaluate(BigInteger.ZERO, provided);
        assertEquals("CURRENT_OBSERVED_FACTS", r0.basis);
        assertEquals("NO_FACTS", r0.metrics.get("clicks").status.name());

        provided.put("clicks", BigInteger.valueOf(3));
        provided.put("spend", BigInteger.TEN);
        ReportCompleteness.Report r = ReportCompleteness.evaluate(BigInteger.TEN, provided);
        assertEquals("PARTIAL", r.metrics.get("clicks").status.name());
        assertEquals("7", r.metrics.get("clicks").missingFactCount);
        assertEquals("COMPLETE", r.metrics.get("spend").status.name());
        assertEquals("MISSING", r.metrics.get("orders").status.name());
    }

    @Test
    void completenessRejectsInvalidCounts() {
        Map<String, BigInteger> provided = new HashMap<>();
        for (String f : ReportCompleteness.METRIC_FIELDS) provided.put(f, BigInteger.ZERO);
        provided.put("clicks", BigInteger.valueOf(11)); // > observed
        assertThrows(IllegalArgumentException.class,
                () -> ReportCompleteness.evaluate(BigInteger.TEN, provided),
                "provided count cannot exceed observed facts");
        assertThrows(IllegalArgumentException.class,
                () -> ReportCompleteness.evaluate(BigInteger.valueOf(-1), provided));
    }
}

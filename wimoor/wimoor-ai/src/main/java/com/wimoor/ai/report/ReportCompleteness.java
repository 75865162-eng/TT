package com.wimoor.ai.report;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Report completeness rules, ported from tiaotiao ERP amazon-ads/report-completeness.ts.
 *
 * <p>Key invariant (preserved verbatim): counts describe ONLY currently saved observed
 * facts — they never assert anything about missing dates or unseen Amazon rows.
 * A field is COMPLETE only when provided == observed; PARTIAL when 0 < provided < observed;
 * MISSING when provided == 0 and observed > 0; NO_FACTS when observed == 0.
 */
public final class ReportCompleteness {

    public enum Status { COMPLETE, PARTIAL, MISSING, NO_FACTS }

    public static final String[] METRIC_FIELDS =
            {"impressions", "clicks", "orders", "units", "spend", "sales"};

    public static final class FieldStatus {
        public final String providedFactCount;
        public final String missingFactCount;
        public final Status status;

        FieldStatus(String provided, String missing, Status status) {
            this.providedFactCount = provided;
            this.missingFactCount = missing;
            this.status = status;
        }
    }

    public static final class Report {
        public final String basis = "CURRENT_OBSERVED_FACTS";
        public final String observedFactCount;
        public final Map<String, FieldStatus> metrics;

        Report(String observed, Map<String, FieldStatus> metrics) {
            this.observedFactCount = observed;
            this.metrics = metrics;
        }
    }

    /** provided must contain every METRIC_FIELDS key; values must be 0 <= count <= observed. */
    public static Report evaluate(BigInteger observed, Map<String, BigInteger> provided) {
        if (observed == null || observed.signum() < 0) throw new IllegalArgumentException("ADS_REPORT_COMPLETENESS_INVALID");
        Map<String, FieldStatus> metrics = new LinkedHashMap<>();
        for (String field : METRIC_FIELDS) {
            BigInteger count = provided == null ? null : provided.get(field);
            if (count == null || count.signum() < 0 || count.compareTo(observed) > 0) {
                throw new IllegalArgumentException("ADS_REPORT_COMPLETENESS_INVALID");
            }
            Status status;
            if (observed.signum() == 0) status = Status.NO_FACTS;
            else if (count.equals(observed)) status = Status.COMPLETE;
            else if (count.signum() == 0) status = Status.MISSING;
            else status = Status.PARTIAL;
            metrics.put(field, new FieldStatus(count.toString(), observed.subtract(count).toString(), status));
        }
        return new Report(observed.toString(), metrics);
    }
}

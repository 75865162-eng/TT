package com.wimoor.ai.report;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Ported from {@code amazon-ads/report-deadline.ts}.
 *
 * <p>A task deadline is immutable work authorization that also covers local
 * stage/publish steps. It must parse as a finite timestamp AND still be in the
 * future; otherwise every further operation is rejected. The check is
 * deliberately strict so a corrupted or expired deadline can never authorize a
 * late retry that outlives its lease window.
 */
public final class ReportDeadline {

    private ReportDeadline() {}

    public static void assertDeadline(String deadline, long nowMillis) {
        long end;
        if (deadline == null) {
            throw new AdsQueryError("ADS_REPORT_DEADLINE_EXCEEDED");
        }
        try {
            end = Instant.parse(deadline).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new AdsQueryError("ADS_REPORT_DEADLINE_EXCEEDED");
        }
        if (end <= nowMillis) throw new AdsQueryError("ADS_REPORT_DEADLINE_EXCEEDED");
    }

    /** Non-throwing variant used by retry/deadline decisions. */
    public static boolean isFuture(String deadline, long nowMillis) {
        if (deadline == null) return false;
        try {
            return Instant.parse(deadline).toEpochMilli() > nowMillis;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** True when the value is any finite, parseable ISO timestamp. */
    public static boolean isFutureOrParseable(String deadline) {
        return parse(deadline) != Long.MIN_VALUE;
    }

    public static long parse(String deadline) {
        if (deadline == null) return Long.MIN_VALUE;
        try {
            return Instant.parse(deadline).toEpochMilli();
        } catch (DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }
}

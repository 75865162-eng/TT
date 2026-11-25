package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/report-freshness.ts}.
 *
 * <p>A structure-plan cadence is NOT a report freshness SLA. With no report-specific
 * contract the best status is OBSERVED_PUBLISHED — never CURRENT. Exact-domain state
 * takes precedence over empty-wildcard state; all-domain reads keep the worst known
 * state. Selection order: severity rank, newest attempt, reportType, lastTaskId.
 */
public final class ReportFreshness {

    private ReportFreshness() {}

    public enum Status { CURRENT, STALE, FAILED, SYNCING, MISSING, OBSERVED_PUBLISHED }

    private static final Set<String> FAILED = new HashSet<>(Arrays.asList("FAILED", "BLOCKED", "CANCELLED"));
    private static final Set<String> SYNCING = new HashSet<>(Arrays.asList(
            "RUNNING", "SYNCING", "RETRY_WAIT", "WAITING", "QUEUED", "WAITING_FOR_ADAPTER", "WAITING_FOR_CONFIG"));

    /** Domain state row as persisted in the sync-state table. */
    public static final class DomainState {
        public String profileId;
        public String reportType;
        public String status;
        public List<String> warningCodes = new ArrayList<>();
        /** epoch millis, null when never attempted */
        public Long lastAttemptAt;
        public String lastTaskId;
    }

    public static Status publishedReportFreshness(boolean hasPublishedFacts, String domainStatus) {
        String s = domainStatus == null ? "" : domainStatus;
        if (FAILED.contains(s)) return Status.FAILED;
        if (SYNCING.contains(s)) return Status.SYNCING;
        if (!hasPublishedFacts) return Status.MISSING;
        if ("STALE".equals(s)) return Status.STALE;
        return Status.OBSERVED_PUBLISHED;
    }

    /** Returns the worst/ranked state, or null when none matches the profile/type. */
    public static <T extends DomainState> T select(List<T> states, String profileId, String reportType) {
        List<T> candidates = new ArrayList<>();
        for (T state : states) {
            if (state == null || !profileId.equals(state.profileId)) continue;
            if (reportType == null || reportType.isEmpty()
                    || reportType.equals(state.reportType)
                    || (state.reportType != null && state.reportType.isEmpty())) {
                candidates.add(state);
            }
        }
        final String wanted = reportType;
        if (wanted != null && !wanted.isEmpty()) {
            boolean exact = candidates.stream().anyMatch(s -> wanted.equals(s.reportType));
            if (exact) {
                candidates.removeIf(s -> !wanted.equals(s.reportType));
            }
        }
        candidates.sort(Comparator
                .comparingInt((T s) -> rank(publishedReportFreshness(true, s.status)))
                .thenComparing((T s) -> -(s.lastAttemptAt == null ? 0L : s.lastAttemptAt))
                .thenComparing(s -> s.reportType == null ? "" : s.reportType)
                .thenComparing(s -> s.lastTaskId == null ? "" : s.lastTaskId));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int rank(Status status) {
        switch (status) {
            case FAILED: return 0;
            case SYNCING: return 1;
            case STALE: return 2;
            case MISSING: return 3;
            case OBSERVED_PUBLISHED:
            case CURRENT: return 4;
            default: return 4;
        }
    }

    /** Aggregates a set of per-profile statuses into a single read status. */
    public static String summarize(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return "MISSING";
        if (statuses.contains("FAILED")) return "FAILED";
        if (statuses.contains("SYNCING")) return "SYNCING";
        if (statuses.stream().allMatch("MISSING"::equals)) return "MISSING";
        if (statuses.contains("MISSING")) return "PARTIAL";
        if (statuses.contains("STALE")) return "STALE";
        return "OBSERVED_PUBLISHED";
    }
}

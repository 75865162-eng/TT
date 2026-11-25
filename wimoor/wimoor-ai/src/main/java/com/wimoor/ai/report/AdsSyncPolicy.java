package com.wimoor.ai.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code amazon-ads/sync-policy.ts}.
 *
 * <p>Stage indexes 0-5 are preserved for backward compatibility with existing
 * encrypted checkpoints (index 5 remains the unsupported reports stage); Product
 * Ads uses the new index 6. Failure classification is separate from read/query and
 * live-account acceptance.
 */
public final class AdsSyncPolicy {

    private AdsSyncPolicy() {}

    public enum Stage { campaigns, ad_groups, keywords, targets, negatives, reports, product_ads;

        public String id() { return name().replace('_', '-'); }

        public static Stage from(String value) {
            if (value == null) return null;
            for (Stage stage : values()) if (stage.id().equals(value)) return stage;
            return null;
        }
    }

    public static final List<String> ADS_SYNC_STAGES = Collections.unmodifiableList(Arrays.asList(
            "campaigns", "ad-groups", "keywords", "targets", "negatives", "reports", "product-ads"));
    public static final int STRUCTURE_COMPLETE_STAGE = 5;

    public static int nextStructureStage(int stage) {
        if (stage == 4) return 6;
        if (stage == 6) return STRUCTURE_COMPLETE_STAGE;
        if (stage >= 0 && stage < 4) return stage + 1;
        throw new AdsSyncError("ADS_REPORT_ADAPTER_NOT_IMPLEMENTED");
    }

    public static String syncKey(List<Object> parts) { return AdsCanonical.digest(parts); }

    /** Splits [from,to] into inclusive windows of at most maxDays days. */
    public static List<Map<String, String>> reportWindows(String from, String to, int maxDays) {
        LocalDate start = parse(from);
        LocalDate end = parse(to);
        if (start == null || end == null || start.isAfter(end)
                || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 3650
                || maxDays < 1 || maxDays > 366) {
            throw new IllegalArgumentException("Ads report window is invalid.");
        }
        List<Map<String, String>> windows = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate windowEnd = cursor.plusDays(maxDays - 1L);
            if (windowEnd.isAfter(end)) windowEnd = end;
            Map<String, String> window = new java.util.LinkedHashMap<>();
            window.put("from", cursor.toString());
            window.put("to", windowEnd.toString());
            windows.add(window);
            cursor = cursor.plusDays(maxDays);
        }
        return windows;
    }

    public static final class Checkpoint {
        public int stage;
        public String cursor;
        public int pages;

        public Checkpoint() {}

        public Checkpoint(int stage, String cursor, int pages) {
            this.stage = stage;
            this.cursor = cursor;
            this.pages = pages;
        }
    }

    public static Checkpoint parseCheckpoint(Object value) {
        if (value == null) return new Checkpoint(0, null, 0);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Ads checkpoint is invalid.");
        Map<?, ?> map = (Map<?, ?>) value;
        Object stage = map.get("stage");
        Object pages = map.get("pages");
        Object cursor = map.get("cursor");
        if (!(stage instanceof Integer) || (Integer) stage < 0 || (Integer) stage >= ADS_SYNC_STAGES.size()
                || !(pages instanceof Integer) || (Integer) pages < 0 || (Integer) pages > 10000
                || (cursor != null && (!(cursor instanceof String) || ((String) cursor).isEmpty()
                || ((String) cursor).length() > 16384))) {
            throw new IllegalArgumentException("Ads checkpoint is invalid.");
        }
        return new Checkpoint((Integer) stage, (String) cursor, (Integer) pages);
    }

    public static String failureStatus(boolean adapterReady, boolean retryable, boolean exhausted) {
        if (!adapterReady) return "WAITING_FOR_ADAPTER";
        if (retryable && !exhausted) return "RETRY_WAIT";
        return exhausted ? "FAILED" : "BLOCKED";
    }

    private static LocalDate parse(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

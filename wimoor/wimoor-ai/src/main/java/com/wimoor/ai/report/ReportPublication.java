package com.wimoor.ai.report;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code amazon-ads/report-publication.ts}.
 *
 * <p>Offline-only manifest validation, staging and publication. The whole batch is
 * validated before any write so a bad row can never partially stage a page. A fact's
 * key is a SHA-256 of the frozen manifest binding plus date and dimensions; metric
 * contract drift, dimension mismatch and row-count drift all block publication.
 * DB/storage calls are represented by {@link VersionStore}.
 */
public final class ReportPublication {

    private ReportPublication() {}

    public static final List<String> INTEGER_METRICS = Collections.unmodifiableList(Arrays.asList(
            "impressions", "clicks", "orders", "units"));
    public static final List<String> DIMENSION_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "campaignId", "adGroupId", "targetingId", "advertisedAsin", "searchTerm", "placement"));
    private static final BigInteger MAX_INT64 = new BigInteger("9223372036854775807");

    public static final class Manifest {
        public String evidence = "ISOLATED_FIXTURE_ONLY";
        public String reportType;
        public String adProduct;
        public String apiVersion;
        public String currencyCode;
        public String attributionWindow;
        public String timezone;
        public String from;
        public String to;
        public final List<String> dimensions = new ArrayList<>();
        public final List<String> metrics = new ArrayList<>();
    }

    public static final class Fence {
        public String taskId;
        public int dispatchVersion;
        public int fenceVersion;
        public String leaseOwner;
    }

    public static final class Fact {
        public String reportDate;
        public final Map<String, String> dimensions = new LinkedHashMap<>();
        public String impressions;
        public String clicks;
        public String spend;
        public String orders;
        public String sales;
        public String units;
    }

    /** Version store replacing Prisma; publication order is preserved. */
    public interface VersionStore {
        String versionId(String taskId);
        void stage(String taskId, String versionId, List<Fact> facts);
        int count(String versionId);
        void publish(String versionId, int rowCount);
    }

    public static final class StagedVersion {
        public String id;
        public String status = "STAGING";
        public int rowCount;
        public final List<Fact> facts = new ArrayList<>();
    }

    public static final class InMemoryStore implements VersionStore {
        private final Map<String, StagedVersion> versions = new ConcurrentHashMap<>();
        private final Map<String, String> byTask = new ConcurrentHashMap<>();

        @Override
        public String versionId(String taskId) {
            return byTask.get(taskId);
        }

        @Override
        public void stage(String taskId, String versionId, List<Fact> facts) {
            StagedVersion version = new StagedVersion();
            version.id = versionId;
            version.facts.addAll(facts);
            version.rowCount = facts.size();
            versions.put(versionId, version);
            byTask.put(taskId, versionId);
        }

        @Override
        public int count(String versionId) {
            StagedVersion version = versions.get(versionId);
            return version == null ? 0 : version.facts.size();
        }

        @Override
        public void publish(String versionId, int rowCount) {
            StagedVersion version = versions.get(versionId);
            if (version != null) {
                version.status = "PUBLISHED";
                version.rowCount = rowCount;
            }
        }
    }

    public static void validateManifest(Manifest manifest) {
        if (manifest == null || !"ISOLATED_FIXTURE_ONLY".equals(manifest.evidence)
                || !text(manifest.reportType) || !text(manifest.adProduct) || !text(manifest.apiVersion)
                || !text(manifest.currencyCode) || !text(manifest.attributionWindow) || !text(manifest.timezone)) {
            throw new AdsQueryError("ADS_REPORT_CONTRACT_UNVERIFIED");
        }
        if (manifest.dimensions.isEmpty() || manifest.metrics.isEmpty()
                || manifest.dimensions.stream().anyMatch(d -> !DIMENSION_FIELDS.contains(d))
                || new LinkedHashSet<>(manifest.dimensions).size() != manifest.dimensions.size()
                || manifest.metrics.stream().anyMatch(m -> !(INTEGER_METRICS.contains(m) || "spend".equals(m) || "sales".equals(m)))
                || new LinkedHashSet<>(manifest.metrics).size() != manifest.metrics.size()) {
            throw new AdsQueryError("ADS_REPORT_FIELDS_UNVERIFIED");
        }
        if (!manifest.currencyCode.matches("[A-Z]{3}")) throw new AdsQueryError("ADS_REPORT_CURRENCY_INVALID");
        try {
            java.time.ZoneId.of(manifest.timezone);
        } catch (RuntimeException e) {
            throw new AdsQueryError("ADS_REPORT_TIMEZONE_INVALID");
        }
        LocalDate from = date(manifest.from);
        LocalDate to = date(manifest.to);
        if (from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) {
            throw new AdsQueryError("ADS_REPORT_RANGE_INVALID");
        }
    }

    /** Validates and orders dimensions in canonical field order. */
    public static Map<String, String> dimensions(Fact fact) {
        if (fact.dimensions.isEmpty()) throw new AdsQueryError("ADS_REPORT_GRAIN_UNVERIFIED");
        for (Map.Entry<String, String> entry : fact.dimensions.entrySet()) {
            if (!DIMENSION_FIELDS.contains(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().isEmpty() || entry.getValue().length() > 2000) {
                throw new AdsQueryError("ADS_REPORT_DIMENSIONS_INVALID");
            }
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : DIMENSION_FIELDS) {
            if (fact.dimensions.containsKey(key)) ordered.put(key, fact.dimensions.get(key));
        }
        return ordered;
    }

    /** SHA-256 of the frozen manifest binding plus a fact's date and dimensions. */
    public static String factKey(Manifest manifest, Fact fact) {
        Map<String, String> dims = dimensions(fact);
        List<String> keys = new ArrayList<>(dims.keySet());
        if (keys.size() != manifest.dimensions.size()
                || manifest.dimensions.stream().anyMatch(k -> !keys.contains(k))) {
            throw new AdsQueryError("ADS_REPORT_GRAIN_MISMATCH");
        }
        for (String key : Arrays.asList("impressions", "clicks", "orders", "units", "spend", "sales")) {
            Object value = metricValue(fact, key);
            if (value != null && !manifest.metrics.contains(key)) {
                throw new AdsQueryError("ADS_REPORT_METRIC_NOT_IN_CONTRACT");
            }
        }
        return AdsCanonical.digest(Arrays.asList(
                manifest.reportType, manifest.adProduct, manifest.apiVersion,
                manifest.currencyCode, manifest.attributionWindow, fact.reportDate, dims));
    }

    /** Integer metrics -> BigInteger, decimals -> BigDecimal, both null-safe. */
    public static Map<String, Object> numeric(Fact fact) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : INTEGER_METRICS) {
            String value = (String) metricValue(fact, key);
            if (value != null && (!value.matches("\\d+")
                    || new BigInteger(value).compareTo(MAX_INT64) > 0)) {
                throw new AdsQueryError("ADS_REPORT_METRIC_INVALID");
            }
            result.put(key, value == null ? null : new BigInteger(value));
        }
        for (String key : Arrays.asList("spend", "sales")) {
            String value = (String) metricValue(fact, key);
            if (value != null && !value.matches("\\d{1,14}(\\.\\d{1,6})?")) {
                throw new AdsQueryError("ADS_REPORT_METRIC_INVALID");
            }
            result.put(key, value == null ? null : new BigDecimal(value));
        }
        return result;
    }

    /** Validates the whole batch before any write; returns canonical fact keys. */
    public static List<String> stage(Manifest manifest, List<Fact> facts) {
        validateManifest(manifest);
        if (facts == null || facts.isEmpty() || facts.size() > 1000) {
            throw new AdsQueryError("ADS_REPORT_BATCH_BOUNDS");
        }
        List<String> keys = new ArrayList<>();
        for (Fact fact : facts) {
            LocalDate day = date(fact.reportDate);
            if (day.isBefore(LocalDate.parse(manifest.from)) || day.isAfter(LocalDate.parse(manifest.to))) {
                throw new AdsQueryError("ADS_REPORT_ROW_OUT_OF_RANGE");
            }
            factKey(manifest, fact);
            numeric(fact);
            keys.add(factKey(manifest, fact));
        }
        return keys;
    }

    public static void publish(Manifest manifest, String profileCurrency, String profileTimezone,
                               String taskFrom, String taskTo, VersionStore store, String taskId,
                               String versionId, int expectedRows) {
        validateManifest(manifest);
        if (!profileCurrency.equals(manifest.currencyCode) || !profileTimezone.equals(manifest.timezone)) {
            throw new AdsQueryError("ADS_REPORT_PROFILE_CONTRACT_MISMATCH");
        }
        if (!taskFrom.equals(manifest.from) || !taskTo.equals(manifest.to)) {
            throw new AdsQueryError("ADS_REPORT_TASK_RANGE_MISMATCH");
        }
        String existing = store.versionId(taskId);
        if (existing != null && existing.equals(versionId)) {
            int count = store.count(versionId);
            if (count != expectedRows) throw new AdsQueryError("ADS_REPORT_ROW_COUNT_DRIFT");
            store.publish(versionId, count);
            return;
        }
        int count = store.count(versionId);
        if (count == 0 || count > 100_000) throw new AdsQueryError("ADS_REPORT_EMPTY_OR_EXCESSIVE_VERSION");
        if (count != expectedRows) throw new AdsQueryError("ADS_REPORT_ROW_COUNT_DRIFT");
        store.publish(versionId, count);
    }

    private static Object metricValue(Fact fact, String key) {
        switch (key) {
            case "impressions": return fact.impressions;
            case "clicks": return fact.clicks;
            case "orders": return fact.orders;
            case "units": return fact.units;
            case "spend": return fact.spend;
            case "sales": return fact.sales;
            default: return null;
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isEmpty() && value.length() <= 200;
    }

    private static LocalDate date(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new AdsQueryError("ADS_REPORT_DATE_INVALID");
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new AdsQueryError("ADS_REPORT_DATE_INVALID");
        }
    }
}

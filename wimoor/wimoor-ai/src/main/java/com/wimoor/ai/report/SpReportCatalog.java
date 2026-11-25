package com.wimoor.ai.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from the catalogue/request half of {@code amazon-ads/sp-report-types.ts}.
 *
 * <p>Five SP daily report types are defined from official documentation. SP has no
 * standalone placement reportTypeId, so placement reuses {@code spCampaigns} with a
 * distinct catalogue ID and domain state to avoid overwriting campaign reports.
 * Request field order is fixed so the frozen-scope digest is reproducible.
 */
public final class SpReportCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY = 86_400_000L;

    private SpReportCatalog() {}

    public enum Type {
        campaign, advertised_product, search_term, placement, targeting;

        public String id() { return name().replace('_', '-'); }

        public static Type from(String value) {
            if (value == null) return null;
            for (Type t : values()) if (t.id().equals(value)) return t;
            return null;
        }
    }

    /** Frozen report definition. */
    public static final class Definition {
        public final String id;
        public final String label;
        public final String capability;
        public final String reportTypeId;
        public final List<String> groupBy;
        public final List<String> columns;
        public final List<String> coreColumns;
        public final int maxDays;
        public final int retentionDays;
        public final String timeUnit = "DAILY";
        public final String format = "GZIP_JSON";
        public final List<String> grain;
        public final String docs;
        public final String currencyColumn;

        Definition(String id, String label, String capability, String reportTypeId,
                   String[] groupBy, String[] columns, String[] coreColumns,
                   int maxDays, int retentionDays, String[] grain, String docs, String currencyColumn) {
            this.id = id;
            this.label = label;
            this.capability = capability;
            this.reportTypeId = reportTypeId;
            this.groupBy = Collections.unmodifiableList(Arrays.asList(groupBy));
            this.columns = Collections.unmodifiableList(Arrays.asList(columns));
            this.coreColumns = Collections.unmodifiableList(Arrays.asList(coreColumns));
            this.maxDays = maxDays;
            this.retentionDays = retentionDays;
            this.grain = Collections.unmodifiableList(Arrays.asList(grain));
            this.docs = docs;
            this.currencyColumn = currencyColumn;
        }
    }

    private static final String[] METRIC_COLUMNS =
            {"impressions", "clicks", "cost", "purchasesClicks", "salesClicks", "unitsSoldClicks"};

    private static String[] metrics() { return METRIC_COLUMNS.clone(); }

    private static String[] columns(String... head) {
        List<String> all = new ArrayList<>(Arrays.asList(head));
        all.addAll(Arrays.asList(METRIC_COLUMNS));
        return all.toArray(new String[0]);
    }

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    static {
        DEFINITIONS.put("campaign", new Definition("campaign", "活动绩效", "campaign-performance",
                "spCampaigns", new String[]{"campaign"},
                columns("date", "campaignId", "campaignName", "campaignBudgetCurrencyCode", "campaignStatus"),
                columns("date", "campaignId"),
                31, 95, new String[]{"campaignId"},
                "guides/reporting/v3/report-types/campaign", "campaignBudgetCurrencyCode"));
        DEFINITIONS.put("advertised-product", new Definition("advertised-product", "投放商品绩效",
                "advertised-product-performance", "spAdvertisedProduct", new String[]{"advertiser"},
                columns("date", "campaignId", "adGroupId", "advertisedAsin", "advertisedSku"),
                columns("date", "campaignId", "adGroupId", "advertisedAsin"),
                31, 95, new String[]{"campaignId", "adGroupId", "advertisedAsin"},
                "guides/reporting/v3/report-types/advertised-product", null));
        DEFINITIONS.put("search-term", new Definition("search-term", "搜索词绩效",
                "search-term-performance", "spSearchTerm", new String[]{"searchTerm"},
                columns("date", "campaignId", "adGroupId", "keywordId", "keyword", "keywordType", "matchType", "searchTerm"),
                columns("date", "campaignId", "adGroupId", "searchTerm"),
                31, 65, new String[]{"campaignId", "adGroupId", "targetingId", "searchTerm"},
                "guides/reporting/v3/report-types/search-term", null));
        DEFINITIONS.put("placement", new Definition("placement", "位置绩效",
                "placement-performance", "spCampaigns", new String[]{"campaignPlacement"},
                columns("date", "campaignId", "campaignName", "campaignBudgetCurrencyCode", "placementClassification"),
                columns("date", "campaignId", "placementClassification"),
                31, 95, new String[]{"campaignId", "placement"},
                "guides/reporting/v3/report-types/placement", "campaignBudgetCurrencyCode"));
        DEFINITIONS.put("targeting", new Definition("targeting", "定向绩效",
                "targeting-performance", "spTargeting", new String[]{"targeting"},
                columns("date", "campaignId", "adGroupId", "targetingId", "targeting", "keyword", "keywordType", "matchType"),
                columns("date", "campaignId", "adGroupId", "targetingId"),
                31, 95, new String[]{"campaignId", "adGroupId", "targetingId"},
                "guides/reporting/v3/report-types/targeting", null));
    }

    public static boolean isSpReportType(String value) {
        return value != null && DEFINITIONS.containsKey(value);
    }

    public static Definition definition(String type) {
        Definition d = type == null ? null : DEFINITIONS.get(type);
        if (d == null) throw new AdsQueryError("ADS_REPORT_TYPE_INVALID");
        return d;
    }

    public static boolean isDateOnly(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static int days(String from, String to) {
        if (!isDateOnly(from) || !isDateOnly(to) || from.compareTo(to) > 0) {
            throw new AdsQueryError("ADS_REPORT_DATE_RANGE_INVALID");
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1;
    }

    /** Reporting v3 request body: POST /reporting/reports. */
    public static Map<String, Object> request(String type, String from, String to, String columnsMode) {
        Definition definition = definition(type);
        int days = days(from, to);
        if (days > definition.maxDays) throw new AdsQueryError("ADS_REPORT_RANGE_EXCEEDS_TYPE_LIMIT");
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("adProduct", "SPONSORED_PRODUCTS");
        configuration.put("groupBy", new ArrayList<>(definition.groupBy));
        configuration.put("columns", new ArrayList<>("core".equals(columnsMode)
                ? definition.coreColumns : definition.columns));
        configuration.put("reportTypeId", definition.reportTypeId);
        configuration.put("timeUnit", definition.timeUnit);
        configuration.put("format", definition.format);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "woker-" + definition.reportTypeId + "-" + from + "-" + to);
        request.put("startDate", from);
        request.put("endDate", to);
        request.put("configuration", configuration);
        return request;
    }

    public static Map<String, Object> request(String type, String from, String to) {
        return request(type, from, to, "full");
    }

    /** Frozen digest of the exact Amazon request, used across restarts. */
    public static String requestDigest(Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration = (Map<String, Object>) request.get("configuration");
        List<Object> payload = Arrays.asList(
                request.get("startDate"), request.get("endDate"), configuration.get("adProduct"),
                configuration.get("groupBy"), configuration.get("columns"),
                configuration.get("reportTypeId"), configuration.get("timeUnit"), configuration.get("format"));
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Default window: yesterday as end, clamped to type limit and retention. */
    public static Map<String, String> window(String type, long nowMillis, int lookbackDays) {
        Definition definition = definition(type);
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC);
        LocalDate end = today.minusDays(1);
        int span = Math.max(1, Math.min(lookbackDays, definition.maxDays));
        LocalDate start = end.minusDays(span - 1L);
        LocalDate earliest = today.minusDays(definition.retentionDays - 1L);
        LocalDate from = start.isAfter(earliest) ? start : earliest;
        Map<String, String> window = new LinkedHashMap<>();
        window.put("from", from.toString());
        window.put("to", end.toString());
        return window;
    }

    /** Limits mirroring defaultSpReportLimits. */
    public static final class Limits {
        public long compressedBytes = 16L * 1024 * 1024;
        public long uncompressedBytes = 64L * 1024 * 1024;
        public int rows = 100_000;
    }

    public static Limits defaultLimits() { return new Limits(); }
}

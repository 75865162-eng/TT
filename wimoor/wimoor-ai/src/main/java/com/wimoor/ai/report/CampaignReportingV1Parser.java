package com.wimoor.ai.report;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/campaign-reporting-v1-parser.ts} together with
 * {@code campaign-reporting-v1-contract.ts}.
 *
 * <p>Builds the frozen minimal Reporting-v1 request and maps already-framed JSON
 * row objects to {@code CampaignReportingV1Fact}. File-level JSON/NDJSON framing
 * remains a separate contract ({@link ReportingV1Json}). Grain is date+campaignId;
 * duplicate grains, field-set drift, control characters, out-of-range dates,
 * non-3-letter currencies and int64 overflow are all rejected.
 */
public final class CampaignReportingV1Parser {

    private CampaignReportingV1Parser() {}

    public static final String CHECKED_AT = "2026-11-06";
    public static final List<String> EVIDENCE_URLS = Arrays.asList(
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/ads-v1/quickstart",
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/ads-v1/overview",
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/ads-v1/dimensions/level-of-detail/campaign",
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/ads-v1/metrics/amazon-retail-conversions/purchases",
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/ads-v1/metrics/costs-and-fees/costs");
    public static final String AD_PRODUCT = "SPONSORED_PRODUCTS";
    public static final String ATTRIBUTION = "SP_DEFAULT_LOOKBACK_SELLER_7D_VENDOR_14D";
    public static final List<String> UNRESOLVED = Arrays.asList(
            "DOWNLOADED_JSON_ROW_ENCODING", "LIVE_PROFILE_ACCEPTANCE", "DOWNLOAD_HOST_ALLOWLIST");
    public static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList(
            "date.value", "campaign.id", "campaign.name", "budgetCurrency.value",
            "metric.impressions", "metric.clicks", "metric.totalCost",
            "metric.purchases", "metric.sales", "metric.unitsSold"));

    private static final int MAX_ROWS = 100_000;
    private static final BigInteger MAX_INT64 = new BigInteger("9223372036854775807");

    /** Mapped fact. All metric strings are exact lexemes or null. */
    public static final class Fact {
        public String reportDate;
        public String campaignId;
        public String campaignName;
        public String currencyCode;
        public String impressions;
        public String clicks;
        public String spend;
        public String orders;
        public String sales;
        public String units;
    }

    /** Frozen minimal create-report request. */
    public static Map<String, Object> request(String from, String to) {
        if (!isDateOnly(from) || !isDateOnly(to) || from.compareTo(to) > 0) {
            throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_DATE_RANGE_INVALID");
        }
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", from);
        period.put("endDate", to);
        Map<String, Object> datePeriod = new LinkedHashMap<>();
        datePeriod.put("datePeriod", period);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("fields", new ArrayList<>(FIELDS));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("format", "GZIP_JSON");
        request.put("periods", new ArrayList<>(Collections.singletonList(datePeriod)));
        request.put("query", query);
        return request;
    }

    public static List<Fact> mapRows(List<Object> rows, String from, String to) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS
                || !isDateOnly(from) || !isDateOnly(to) || from.compareTo(to) > 0) {
            throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_ROW_SET_INVALID");
        }
        Set<String> grains = new HashSet<>();
        List<Fact> facts = new ArrayList<>();
        for (Object value : rows) {
            Map<String, Object> row = record(value);
            Set<String> keys = row.keySet();
            if (keys.size() != FIELDS.size()) throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_FIELDS_MISMATCH");
            for (String key : keys) if (!FIELDS.contains(key)) throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_FIELDS_MISMATCH");

            String reportDate = requiredString(row, "date.value", false);
            String campaignId = requiredString(row, "campaign.id", false);
            String campaignName = requiredString(row, "campaign.name", true);
            String currencyCode = requiredString(row, "budgetCurrency.value", false);
            if (!isDateOnly(reportDate) || reportDate.compareTo(from) < 0 || reportDate.compareTo(to) > 0) {
                throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_DATE_INVALID");
            }
            if (!currencyCode.matches("[A-Z]{3}")) throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_CURRENCY_INVALID");
            String grain = reportDate + "|" + campaignId;
            if (!grains.add(grain)) throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_DUPLICATE_GRAIN");

            Fact fact = new Fact();
            fact.reportDate = reportDate;
            fact.campaignId = campaignId;
            fact.campaignName = campaignName;
            fact.currencyCode = currencyCode;
            fact.impressions = metric(row, "metric.impressions", true);
            fact.clicks = metric(row, "metric.clicks", true);
            fact.spend = metric(row, "metric.totalCost", false);
            fact.orders = metric(row, "metric.purchases", true);
            fact.sales = metric(row, "metric.sales", false);
            fact.units = metric(row, "metric.unitsSold", true);
            facts.add(fact);
        }
        return facts;
    }

    /** Converts parser output to the staging grain without publishing or inferring metrics. */
    public static Map<String, Object> toStaging(List<Fact> facts) {
        if (facts == null || facts.isEmpty() || facts.size() > MAX_ROWS) {
            throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_ROW_SET_INVALID");
        }
        String currencyCode = facts.get(0).currencyCode;
        if (currencyCode == null) throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_CURRENCY_MIXED");
        for (Fact fact : facts) {
            if (!currencyCode.equals(fact.currencyCode)) {
                throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_CURRENCY_MIXED");
            }
        }
        List<Map<String, Object>> staged = new ArrayList<>();
        for (Fact fact : facts) {
            Map<String, Object> dimensions = new LinkedHashMap<>();
            dimensions.put("campaignId", fact.campaignId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reportDate", fact.reportDate);
            row.put("dimensions", dimensions);
            row.put("impressions", fact.impressions);
            row.put("clicks", fact.clicks);
            row.put("spend", fact.spend);
            row.put("orders", fact.orders);
            row.put("sales", fact.sales);
            row.put("units", fact.units);
            staged.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currencyCode", currencyCode);
        result.put("facts", staged);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> record(Object value) {
        if (!(value instanceof Map)) throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_ROW_INVALID");
        return (Map<String, Object>) value;
    }

    private static String requiredString(Map<String, Object> row, String field, boolean nullable) {
        Object value = row.get(field);
        if (nullable && value == null) return null;
        if (!(value instanceof String) || ((String) value).isEmpty()
                || ((String) value).length() > 2000 || hasControl((String) value)) {
            throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_ROW_INVALID");
        }
        return (String) value;
    }

    private static String metric(Map<String, Object> row, String field, boolean integer) {
        Object value = row.get(field);
        if (value == null) return null;
        if (!(value instanceof ReportingV1Json.JsonNumber)) {
            throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_METRIC_INVALID");
        }
        String source = ((ReportingV1Json.JsonNumber) value).source;
        if (integer) {
            if (!source.matches("(0|[1-9]\\d*)") || new BigInteger(source).compareTo(MAX_INT64) > 0) {
                throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_METRIC_INVALID");
            }
        } else if (!source.matches("(0|[1-9]\\d{0,13})(\\.\\d{1,6})?")) {
            throw new AdsQueryError("ADS_REPORT_V1_CAMPAIGN_METRIC_INVALID");
        }
        return source;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1f || c == 0x7f) return true;
        }
        return false;
    }

    private static boolean isDateOnly(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }
}

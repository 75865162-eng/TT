package com.wimoor.ai.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * Ported from {@code amazon-ads/campaign-report-contract.ts}.
 *
 * <p>Offline-only campaign reporting-v3 fixture: manifest construction, request
 * construction and bounded single-file GZIP_JSON parsing. V3 is single-file; it
 * never invents V1 multipart semantics. Public documentation evidence is data,
 * not authorization to issue a live request ({@code liveEnabled = false}).
 */
public final class CampaignReportContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CampaignReportContract() {}

    public static final String CHECKED_AT = "2026-11-06";
    public static final List<String> EVIDENCE_URLS = Arrays.asList(
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/v3/report-types/campaign",
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/v3/get-started",
            "https://advertising.amazon.com/API/docs/en-us/guides/reporting/ads-v1/quickstart");
    public static final String API_VERSION = "reporting-v3";
    public static final String REPORT_TYPE = "spCampaigns";
    public static final int MAX_DAYS = 31;
    public static final int RETENTION_DAYS = 95;
    public static final String DEPRECATION_DATE = "2027-06-30";
    public static final boolean LIVE_ENABLED = false;
    public static final List<String> UNRESOLVED = Arrays.asList(
            "V1_ADVERTISER_ACCOUNT_BINDING", "LIVE_PROFILE_ACCEPTANCE",
            "DOWNLOAD_HOST_ALLOWLIST", "ATTRIBUTION_FINALIZATION_SLA");

    /** Spec mirroring CampaignReportSpec; attributionDays must be 1/7/14/30. */
    public static final class Spec {
        public String from;
        public String to;
        public String currencyCode;
        public String timezone;
        public int attributionDays;

        public Spec() {}

        public Spec(String from, String to, String currencyCode, String timezone, int attributionDays) {
            this.from = from;
            this.to = to;
            this.currencyCode = currencyCode;
            this.timezone = timezone;
            this.attributionDays = attributionDays;
        }
    }

    public static final class Limits {
        public long compressedBytes;
        public long uncompressedBytes;
        public int rows;

        public Limits() {}

        public Limits(long compressedBytes, long uncompressedBytes, int rows) {
            this.compressedBytes = compressedBytes;
            this.uncompressedBytes = uncompressedBytes;
            this.rows = rows;
        }
    }

    public static final Limits ISOLATED_LIMITS = new Limits(1024L * 1024L, 8L * 1024L * 1024L, 1000);

    private static final BigInteger MAX_INT64 = new BigInteger("9223372036854775807");

    /** Manifest is evidence-only: ISOLATED_FIXTURE_ONLY, never a live contract. */
    public static Map<String, Object> manifest(Spec spec) {
        long from = date(spec.from);
        long to = date(spec.to);
        long days = (to - from) / 86_400_000L + 1;
        if (days < 1 || days > MAX_DAYS
                || !(spec.attributionDays == 1 || spec.attributionDays == 7
                || spec.attributionDays == 14 || spec.attributionDays == 30)) {
            throw new AdsQueryError("ADS_CAMPAIGN_REPORT_RANGE_OR_ATTRIBUTION_INVALID");
        }
        if (spec.currencyCode == null || !spec.currencyCode.matches("[A-Z]{3}")) {
            throw new AdsQueryError("ADS_CAMPAIGN_REPORT_CURRENCY_INVALID");
        }
        try {
            TimeZone.getTimeZone(spec.timezone);
            java.time.ZoneId.of(spec.timezone);
        } catch (DateTimeException e) {
            throw new AdsQueryError("ADS_CAMPAIGN_REPORT_TIMEZONE_INVALID");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("evidence", "ISOLATED_FIXTURE_ONLY");
        m.put("apiVersion", API_VERSION);
        m.put("reportType", REPORT_TYPE);
        m.put("adProduct", "SPONSORED_PRODUCTS");
        m.put("from", spec.from);
        m.put("to", spec.to);
        m.put("currencyCode", spec.currencyCode);
        m.put("timezone", spec.timezone);
        m.put("attributionWindow", spec.attributionDays + "d");
        m.put("dimensions", Arrays.asList("campaignId"));
        m.put("metrics", Arrays.asList("impressions", "clicks", "spend", "orders", "sales", "units"));
        return m;
    }

    public static Map<String, Object> request(Spec spec) {
        manifest(spec);
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("adProduct", "SPONSORED_PRODUCTS");
        configuration.put("groupBy", Arrays.asList("campaign"));
        configuration.put("columns", Arrays.asList(
                "date", "campaignId", "campaignBudgetCurrencyCode", "impressions", "clicks", "cost",
                "purchases" + spec.attributionDays + "d",
                "sales" + spec.attributionDays + "d",
                "unitsSoldClicks" + spec.attributionDays + "d"));
        configuration.put("reportTypeId", REPORT_TYPE);
        configuration.put("timeUnit", "DAILY");
        configuration.put("format", "GZIP_JSON");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("startDate", spec.from);
        request.put("endDate", spec.to);
        request.put("configuration", configuration);
        return request;
    }

    /** Bounded single GZIP_JSON document; maps rows to the staging grain. */
    public static List<Map<String, Object>> parse(byte[] buffer, Spec spec, Limits limits) {
        manifest(spec);
        if (limits == null || limits.compressedBytes < 1 || limits.uncompressedBytes < 1 || limits.rows < 1
                || limits.rows > 1000 || limits.compressedBytes > ISOLATED_LIMITS.compressedBytes
                || limits.uncompressedBytes > ISOLATED_LIMITS.uncompressedBytes) {
            throw new AdsQueryError("ADS_CAMPAIGN_REPORT_LIMITS_INVALID");
        }
        if (buffer == null || buffer.length == 0 || buffer.length > limits.compressedBytes) {
            throw new AdsQueryError("ADS_REPORT_COMPRESSED_LIMIT");
        }
        byte[] decoded;
        try {
            decoded = gunzip(buffer, limits.uncompressedBytes);
        } catch (IOException e) {
            throw new AdsQueryError("ADS_REPORT_GZIP_OR_JSON_INVALID");
        }
        List<Map<String, Object>> rows;
        try {
            rows = MAPPER.readValue(new String(decoded, StandardCharsets.UTF_8),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw new AdsQueryError("ADS_REPORT_GZIP_OR_JSON_INVALID");
        }
        if (rows == null || rows.isEmpty() || rows.size() > limits.rows) {
            throw new AdsQueryError("ADS_REPORT_EMPTY_OR_ROW_LIMIT");
        }
        Map<?, ?> configuration = (Map<?, ?>) request(spec).get("configuration");
        Set<String> columns = new HashSet<>((List<String>) configuration.get("columns"));
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map)) throw new AdsQueryError("ADS_REPORT_ROW_INVALID");
            Map<?, ?> row = (Map<?, ?>) value;
            for (Object key : row.keySet()) if (!columns.contains(key)) throw new AdsQueryError("ADS_REPORT_UNEXPECTED_COLUMN");
            String campaignId = integer(row.get("campaignId"));
            Object dateValue = row.get("date");
            if (campaignId == null || "0".equals(campaignId) || !(dateValue instanceof String)
                    || !isDateOnly((String) dateValue) || ((String) dateValue).compareTo(spec.from) < 0
                    || ((String) dateValue).compareTo(spec.to) > 0) {
                throw new AdsQueryError("ADS_REPORT_GRAIN_OR_DATE_INVALID");
            }
            String reportDate = (String) dateValue;
            if (!spec.currencyCode.equals(row.get("campaignBudgetCurrencyCode"))) {
                throw new AdsQueryError("ADS_REPORT_CURRENCY_MISMATCH");
            }
            String key = reportDate + ":" + campaignId;
            if (!seen.add(key)) throw new AdsQueryError("ADS_REPORT_DUPLICATE_GRAIN");
            Map<String, Object> dims = new LinkedHashMap<>();
            dims.put("campaignId", campaignId);
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("reportDate", reportDate);
            fact.put("dimensions", dims);
            fact.put("impressions", integer(row.get("impressions")));
            fact.put("clicks", integer(row.get("clicks")));
            fact.put("spend", decimal(row.get("cost")));
            fact.put("orders", integer(row.get("purchases" + spec.attributionDays + "d")));
            fact.put("sales", decimal(row.get("sales" + spec.attributionDays + "d")));
            fact.put("units", integer(row.get("unitsSoldClicks" + spec.attributionDays + "d")));
            facts.add(fact);
        }
        return facts;
    }

    public static List<Map<String, Object>> parse(byte[] buffer, Spec spec) {
        return parse(buffer, spec, ISOLATED_LIMITS);
    }

    private static String integer(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d < 0 || d != Math.floor(d) || (long) d != d || d > 9_007_199_254_740_991L) {
                throw new AdsQueryError("ADS_REPORT_INTEGER_PRECISION_INVALID");
            }
        }
        String text = String.valueOf(value);
        if (!text.matches("(0|[1-9]\\d*)") || new BigInteger(text).compareTo(MAX_INT64) > 0) {
            throw new AdsQueryError("ADS_REPORT_INTEGER_INVALID");
        }
        return text;
    }

    private static String decimal(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d) || d < 0 || d > 1_000_000) {
                throw new AdsQueryError("ADS_REPORT_DECIMAL_PRECISION_INVALID");
            }
        }
        String text = String.valueOf(value);
        if (!text.matches("\\d{1,14}(\\.\\d{1,6})?")) throw new AdsQueryError("ADS_REPORT_DECIMAL_INVALID");
        return text;
    }

    static boolean isDateOnly(String value) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static long date(String value) {
        if (!isDateOnly(value)) throw new AdsQueryError("ADS_CAMPAIGN_REPORT_DATE_INVALID");
        try {
            return LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeException e) {
            throw new AdsQueryError("ADS_CAMPAIGN_REPORT_DATE_INVALID");
        }
    }

    static byte[] gunzip(byte[] bytes, long maxOutput) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxOutput) throw new AdsQueryError("ADS_REPORT_GZIP_OR_JSON_INVALID");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

}

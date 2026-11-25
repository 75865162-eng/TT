package com.wimoor.ai.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Ported from the parsing half of {@code amazon-ads/sp-report-types.ts}
 * ({@code mapSpReportRow}, {@code parseSpReportFile}, {@code integer}, {@code decimal},
 * {@code text}, {@code sumInteger}, {@code sumDecimal}).
 *
 * <p>Only officially declared columns are accepted: unknown columns are ignored and
 * reported. Rows sharing a grain (for example several SKUs of one ASIN) are merged
 * by summing metrics — never silently dropped. Integer metrics are BigInteger
 * bounded to signed 64-bit; decimal metrics keep at most 14 integer + 6 fraction
 * digits as exact strings to avoid floating-point drift.
 */
public final class SpReportParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigInteger MAX_INT64 = new BigInteger("9223372036854775807");

    private SpReportParser() {}

    /** Parse result mirroring SpReportParseResult. */
    public static final class Result {
        public final List<Map<String, Object>> facts;
        public final int rowCount;
        public final int mergedRows;
        public final List<String> ignoredColumns;

        Result(List<Map<String, Object>> facts, int rowCount, int mergedRows, List<String> ignored) {
            this.facts = facts;
            this.rowCount = rowCount;
            this.mergedRows = mergedRows;
            this.ignoredColumns = ignored;
        }
    }

    /** Maps one v3 wide row to the local staging grain. */
    public static Map<String, Object> mapRow(String type, Map<String, Object> row,
                                             String from, String to, String currencyCode) {
        SpReportCatalog.Definition definition = SpReportCatalog.definition(type);
        String reportDate = text(row.get("date"));
        if (!SpReportCatalog.isDateOnly(reportDate)
                || reportDate.compareTo(from) < 0 || reportDate.compareTo(to) > 0) {
            throw new AdsQueryError("ADS_REPORT_DATE_INVALID");
        }
        Map<String, Object> dimensions = new LinkedHashMap<>();
        String campaignId = text(get(row, "campaignId"));
        String adGroupId = text(get(row, "adGroupId"));
        if (campaignId != null) dimensions.put("campaignId", campaignId);
        if (adGroupId != null) dimensions.put("adGroupId", adGroupId);
        if (definition.grain.contains("advertisedAsin")) {
            String asin = text(get(row, "advertisedAsin", "asin", "promotedAsin"));
            if (asin == null) throw new AdsQueryError("ADS_REPORT_GRAIN_MISSING");
            dimensions.put("advertisedAsin", asin);
        }
        if (definition.grain.contains("targetingId")) {
            String targetingId = text(get(row, "targetingId", "keywordId", "targetId"));
            if (targetingId == null) throw new AdsQueryError("ADS_REPORT_GRAIN_MISSING");
            dimensions.put("targetingId", targetingId);
        }
        if (definition.grain.contains("searchTerm")) {
            String searchTerm = text(get(row, "searchTerm", "query"));
            if (searchTerm == null) throw new AdsQueryError("ADS_REPORT_GRAIN_MISSING");
            dimensions.put("searchTerm", searchTerm);
        }
        if (definition.grain.contains("placement")) {
            String placement = text(get(row, "placementClassification", "placement"));
            if (placement == null) throw new AdsQueryError("ADS_REPORT_GRAIN_MISSING");
            dimensions.put("placement", placement);
        }
        for (String key : definition.grain) {
            if (!dimensions.containsKey(key)) throw new AdsQueryError("ADS_REPORT_GRAIN_MISSING");
        }
        if (definition.currencyColumn != null) {
            String rowCurrency = text(row.get(definition.currencyColumn));
            if (rowCurrency != null && currencyCode != null && !rowCurrency.equals(currencyCode)) {
                throw new AdsQueryError("ADS_REPORT_CURRENCY_MISMATCH");
            }
        }
        String effectiveCurrency = definition.currencyColumn == null
                ? null : text(row.get(definition.currencyColumn));
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("reportDate", reportDate);
        fact.put("dimensions", dimensions);
        fact.put("impressions", integer(get(row, "impressions")));
        fact.put("clicks", integer(get(row, "clicks")));
        fact.put("spend", decimal(get(row, "cost", "spend")));
        fact.put("orders", integer(get(row, "purchasesClicks", "purchases", "purchases7d")));
        fact.put("sales", decimal(get(row, "salesClicks", "sales", "sales7d")));
        fact.put("units", integer(get(row, "unitsSoldClicks", "unitsSold", "unitsSoldClicks7d")));
        fact.put("currencyCode", effectiveCurrency);
        return fact;
    }

    /** Parses a GZIP_JSON report and merges rows sharing a grain. */
    public static Result parseFile(String type, byte[] buffer, String from, String to,
                                   String currencyCode, SpReportCatalog.Limits limits) {
        SpReportCatalog.Definition definition = SpReportCatalog.definition(type);
        SpReportCatalog.Limits effective = limits == null ? SpReportCatalog.defaultLimits() : limits;
        int days = SpReportCatalog.days(from, to);
        if (days > definition.maxDays) throw new AdsQueryError("ADS_REPORT_RANGE_EXCEEDS_TYPE_LIMIT");
        if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
            throw new AdsQueryError("ADS_REPORT_CURRENCY_INVALID");
        }
        if (buffer == null || buffer.length < 1 || buffer.length > effective.compressedBytes) {
            throw new AdsQueryError("ADS_REPORT_COMPRESSED_LIMIT");
        }
        byte[] decoded;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(buffer));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > effective.uncompressedBytes) throw new AdsQueryError("ADS_REPORT_GZIP_OR_JSON_INVALID");
                out.write(chunk, 0, read);
            }
            decoded = out.toByteArray();
        } catch (IOException e) {
            throw new AdsQueryError("ADS_REPORT_GZIP_OR_JSON_INVALID");
        }
        List<Map<String, Object>> parsed;
        try {
            parsed = MAPPER.readValue(new String(decoded, StandardCharsets.UTF_8),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw new AdsQueryError("ADS_REPORT_GZIP_OR_JSON_INVALID");
        }
        if (parsed == null) throw new AdsQueryError("ADS_REPORT_ROW_SET_INVALID");
        if (parsed.size() > effective.rows) throw new AdsQueryError("ADS_REPORT_ROW_LIMIT");

        Set<String> ignored = new TreeSet<>();
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        int mergedRows = 0;
        for (Object value : parsed) {
            if (!(value instanceof Map)) throw new AdsQueryError("ADS_REPORT_ROW_INVALID");
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) value;
            for (String key : row.keySet()) {
                if (!definition.columns.contains(key)) ignored.add(key);
            }
            Map<String, Object> fact = mapRow(type, row, from, to, currencyCode);
            String key = grainKey(definition, fact);
            Map<String, Object> prior = merged.get(key);
            if (prior == null) {
                merged.put(key, fact);
                continue;
            }
            mergedRows++;
            Map<String, Object> sum = new LinkedHashMap<>(prior);
            sum.put("impressions", sumInteger(prior.get("impressions"), fact.get("impressions")));
            sum.put("clicks", sumInteger(prior.get("clicks"), fact.get("clicks")));
            sum.put("orders", sumInteger(prior.get("orders"), fact.get("orders")));
            sum.put("units", sumInteger(prior.get("units"), fact.get("units")));
            sum.put("spend", sumDecimal(prior.get("spend"), fact.get("spend")));
            sum.put("sales", sumDecimal(prior.get("sales"), fact.get("sales")));
            merged.put(key, sum);
        }
        return new Result(new ArrayList<>(merged.values()), parsed.size(), mergedRows,
                new ArrayList<>(ignored));
    }

    @SuppressWarnings("unchecked")
    private static String grainKey(SpReportCatalog.Definition definition, Map<String, Object> fact) {
        List<Object> key = new ArrayList<>();
        key.add(fact.get("reportDate"));
        Map<String, Object> dimensions = (Map<String, Object>) fact.get("dimensions");
        for (String field : definition.grain) key.add(dimensions.get(field));
        try {
            return MAPPER.writeValueAsString(key);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !"".equals(value)) return value;
        }
        return null;
    }

    private static String integer(Object value) {
        if (value == null || "".equals(value)) return null;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d != Math.floor(d) || d < 0 || d > 9_007_199_254_740_991L) {
                throw new AdsQueryError("ADS_REPORT_INTEGER_INVALID");
            }
            return String.valueOf(value);
        }
        String text = String.valueOf(value).trim();
        if (!text.matches("(0|[1-9]\\d*)") || new BigInteger(text).compareTo(MAX_INT64) > 0) {
            throw new AdsQueryError("ADS_REPORT_INTEGER_INVALID");
        }
        return text;
    }

    private static String decimal(Object value) {
        if (value == null || "".equals(value)) return null;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d) || d < 0 || d > 1_000_000_000) {
                throw new AdsQueryError("ADS_REPORT_DECIMAL_INVALID");
            }
        }
        String text = String.valueOf(value).trim();
        if (!text.matches("\\d{1,14}(\\.\\d{1,6})?")) throw new AdsQueryError("ADS_REPORT_DECIMAL_INVALID");
        return text;
    }

    private static String text(Object value) {
        if (value == null || "".equals(value)) return null;
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new AdsQueryError("ADS_REPORT_ROW_INVALID");
        }
        String result = String.valueOf(value).trim();
        if (result.isEmpty()) return null;
        if (result.length() > 2000) throw new AdsQueryError("ADS_REPORT_ROW_INVALID");
        // Emulate the JS /[\u0000-\u001f\u007f]/.test() control-character check.
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (c <= 0x1f || c == 0x7f) throw new AdsQueryError("ADS_REPORT_ROW_INVALID");
        }
        return result;
    }

    static String sumInteger(Object left, Object right) {
        if (left == null && right == null) return null;
        BigInteger total = (left == null ? BigInteger.ZERO : new BigInteger(left.toString()))
                .add(right == null ? BigInteger.ZERO : new BigInteger(right.toString()));
        if (total.compareTo(MAX_INT64) > 0) throw new AdsQueryError("ADS_REPORT_INTEGER_INVALID");
        return total.toString();
    }

    static String sumDecimal(Object left, Object right) {
        if (left == null && right == null) return null;
        BigInteger a = scaled(left), b = scaled(right);
        BigInteger total = a.add(b);
        boolean negative = total.signum() < 0;
        String digits = total.abs().toString();
        while (digits.length() < 7) digits = "0" + digits;
        String result = digits.substring(0, digits.length() - 6) + "." + digits.substring(digits.length() - 6);
        result = result.replaceAll("0+$", "").replaceAll("\\.$", "");
        return negative ? "-" + result : result;
    }

    private static BigInteger scaled(Object value) {
        if (value == null) return BigInteger.ZERO;
        String text = value.toString();
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot + 1);
        while (fraction.length() < 6) fraction += "0";
        if (fraction.length() > 6) fraction = fraction.substring(0, 6);
        return new BigInteger(whole + fraction);
    }
}

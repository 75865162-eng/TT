package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/reporting-v1-transport.ts}.
 *
 * <p>Injectable protocol adapter only — no production factory or dispatch is enabled.
 * The defensive snapshot canonicalises a create request to a frozen contract, and
 * both create and subsequent polls must match it byte-for-byte. URLs stay
 * server-side; signed download URLs are never exposed to clients.
 */
public final class ReportingV1Transport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FORMATS = new HashSet<>(Arrays.asList(
            "CSV", "GZIP_JSON", "PARTITIONED_CSV", "PARTITIONED_GZIP_JSON"));

    private ReportingV1Transport() {}

    public static final class CreateReport {
        public String format;
        public List<Map<String, Object>> periods;
        public Map<String, Object> query;

        public CreateReport() {}

        public CreateReport(String format, String from, String to, List<String> fields) {
            this.format = format;
            Map<String, Object> datePeriod = new LinkedHashMap<>();
            datePeriod.put("startDate", from);
            datePeriod.put("endDate", to);
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("datePeriod", datePeriod);
            this.periods = new ArrayList<>(Collections.singletonList(period));
            this.query = new LinkedHashMap<>();
            this.query.put("fields", new ArrayList<>(fields));
        }

        public Map<String, Object> firstPeriod() {
            if (periods == null || periods.isEmpty()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> holder = (Map<String, Object>) periods.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> period = (Map<String, Object>) holder.get("datePeriod");
            return period;
        }
    }

    public static final class Part {
        public String url;
        public long sizeInBytes;
        public String urlExpirationDateTime;

        public Part() {}

        public Part(String url, long sizeInBytes, String urlExpirationDateTime) {
            this.url = url;
            this.sizeInBytes = sizeInBytes;
            this.urlExpirationDateTime = urlExpirationDateTime;
        }
    }

    public static final class Result {
        public String outcome;
        public String reportId;
        public String status;
        public String format;
        public String createdAt;
        public String updatedAt;
        public String completedAt;
        public String failureCode;
        public List<Part> parts = new ArrayList<>();
        public List<Map<String, Object>> periods = new ArrayList<>();
        public Map<String, Object> query;
        public final List<Map<String, Object>> errors = new ArrayList<>();
    }

    /** Injectable protocol adapter. */
    public interface Transport {
        Result create(AuthInput input, CreateReport report);

        Result retrieve(AuthInput input, String reportId);

        byte[] download(String url, String expiresAt, long maxBytes, long expectedBytes);
    }

    public static final class AuthInput {
        public String region;
        public String clientId;
        public String accessToken;
        public String advertiserAccountId;
    }

    /** Deterministic fixture transport: returns pre-queued results in FIFO order. */
    public static final class FixtureTransport implements Transport {
        public final String evidence = "ISOLATED_FIXTURE_ONLY";
        private final Queue<Result> results = new ArrayDeque<>();
        private final Map<String, byte[]> downloads = new LinkedHashMap<>();

        public FixtureTransport enqueue(Result result) { results.add(result); return this; }

        public FixtureTransport addDownload(String url, byte[] bytes) { downloads.put(url, bytes); return this; }

        @Override
        public Result create(AuthInput input, CreateReport report) {
            if (results.isEmpty()) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
            return results.poll();
        }

        @Override
        public Result retrieve(AuthInput input, String reportId) {
            if (results.isEmpty()) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
            return results.poll();
        }

        @Override
        public byte[] download(String url, String expiresAt, long maxBytes, long expectedBytes) {
            byte[] bytes = downloads.get(url);
            if (bytes == null || bytes.length != expectedBytes || bytes.length > maxBytes) {
                throw new AdsQueryError("ADS_REPORT_BODY_SIZE_MISMATCH");
            }
            return bytes.clone();
        }
    }

    /** Canonical defensive snapshot, also used by the persisted V1 protocol fixture. */
    public static CreateReport snapshot(CreateReport value) {
        validateCreate(value);
        List<String> fields = new ArrayList<>((List<String>) value.query.get("fields"));
        int chars = 0;
        for (String field : fields) chars += field.length();
        if (chars > 64 * 1024) throw new AdsQueryError("ADS_REPORT_V1_REQUEST_INVALID");
        CreateReport report = new CreateReport();
        report.format = value.format;
        Map<String, Object> period = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> sourcePeriod = (Map<String, Object>) value.periods.get(0).get("datePeriod");
        period.put("startDate", sourcePeriod.get("startDate"));
        period.put("endDate", sourcePeriod.get("endDate"));
        Map<String, Object> holder = new LinkedHashMap<>();
        holder.put("datePeriod", period);
        report.periods = new ArrayList<>(Collections.singletonList(holder));
        report.query = new LinkedHashMap<>();
        report.query.put("fields", fields);
        if (value.query.get("filter") != null) {
            report.query.put("filter", snapshotFilter(value.query.get("filter"), 0, new int[]{0, 0, 0}));
        }
        if (AdsCanonical.json(report).getBytes(StandardCharsets.UTF_8).length > 512 * 1024) {
            throw new AdsQueryError("ADS_REPORT_V1_REQUEST_INVALID");
        }
        return report;
    }

    /** Both create and subsequent polls must match the frozen report contract. */
    public static void assertReceiptMatches(Result result, CreateReport expected) {
        if (!"success".equals(result.outcome)) return;
        CreateReport actual;
        try {
            actual = snapshot(new CreateReport(result.format,
                    (String) result.periods.get(0).get("startDate"),
                    (String) result.periods.get(0).get("endDate"),
                    (List<String>) result.query.get("fields")));
        } catch (RuntimeException e) {
            throw new AdsQueryError("ADS_REPORT_V1_RECEIPT_MISMATCH");
        }
        if (!AdsCanonical.json(actual).equals(AdsCanonical.json(expected))) {
            throw new AdsQueryError("ADS_REPORT_V1_RECEIPT_MISMATCH");
        }
    }

    /** Local allowlist validation; a host is only accepted as an exact domain suffix. */
    public static Set<String> validateDownloadHosts(List<String> hosts) {
        Set<String> result = new HashSet<>();
        for (String host : hosts) {
            if (host == null || !host.matches("(?i)^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$")) {
                throw new AdsQueryError("ADS_REPORT_DOWNLOAD_ALLOWLIST_INVALID");
            }
            result.add(host.toLowerCase());
        }
        return result;
    }

    public static void validateCredentials(AuthInput input, Set<String> regions) {
        if (!regions.contains(input.region) || input.clientId == null || input.clientId.trim().isEmpty()
                || input.clientId.length() > 512 || input.accessToken == null || input.accessToken.trim().isEmpty()
                || input.accessToken.length() > 16384
                || (input.clientId + input.accessToken).matches("[\\s\\S]*[\\r\\n][\\s\\S]*")) {
            throw new AdsQueryError("ADS_REPORT_V1_AUTH_INVALID");
        }
    }

    private static void validateCreate(CreateReport value) {
        if (value == null || !FORMATS.contains(value.format) || value.periods == null || value.periods.size() != 1
                || value.query == null || value.query.get("fields") == null
                || !(value.query.get("fields") instanceof List)) {
            throw new AdsQueryError("ADS_REPORT_V1_REQUEST_INVALID");
        }
        Map<String, Object> period = value.firstPeriod();
        if (period == null || period.size() != 1
                || !isDateOnly((String) period.get("startDate")) || !isDateOnly((String) period.get("endDate"))
                || ((String) period.get("startDate")).compareTo((String) period.get("endDate")) > 0
                || value.query.size() > 2
                || ((List<?>) value.query.get("fields")).size() < 1
                || ((List<?>) value.query.get("fields")).size() > 1000) {
            throw new AdsQueryError("ADS_REPORT_V1_REQUEST_INVALID");
        }
        for (Object field : (List<?>) value.query.get("fields")) if (!safeId(field)) throw new AdsQueryError("ADS_REPORT_V1_REQUEST_INVALID");
    }

    private static Object snapshotFilter(Object value, int depth, int[] budget) {
        if (++budget[0] > 100 || depth > 8 || !(value instanceof Map)) {
            throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
        }
        Map<?, ?> node = (Map<?, ?>) value;
        if (node.size() != 1) throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
        if (node.containsKey("and")) {
            Object and = node.get("and");
            if (!(and instanceof Map) || ((Map<?, ?>) and).size() != 1) throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
            Object filters = ((Map<?, ?>) and).get("filters");
            if (!(filters instanceof List) || ((List<?>) filters).size() < 1 || ((List<?>) filters).size() > 10) {
                throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> wrapper = new LinkedHashMap<>();
            List<Object> mapped = new ArrayList<>();
            for (Object filter : (List<?>) filters) mapped.add(snapshotFilter(filter, depth + 1, budget));
            wrapper.put("filters", mapped);
            result.put("and", wrapper);
            return result;
        }
        if (node.containsKey("on")) {
            Object on = node.get("on");
            if (!(on instanceof Map)) throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
            Map<?, ?> predicate = (Map<?, ?>) on;
            Object op = predicate.get("comparisonOperator");
            Object field = predicate.get("field");
            Object not = predicate.get("not");
            Object values = predicate.get("values");
            if (predicate.size() != 4 || !"EQUALS".equals(op) && !"IN".equals(op)
                    || !(field instanceof String) || ((String) field).length() > 512
                    || !((String) field).matches("[a-zA-Z0-9]+\\.[a-zA-Z0-9]+")
                    || !(not instanceof Boolean) || !(values instanceof List)
                    || ((List<?>) values).size() < 1 || ((List<?>) values).size() > 2000
                    || ("EQUALS".equals(op) && ((List<?>) values).size() != 1)) {
                throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
            }
            List<String> mapped = new ArrayList<>();
            for (Object item : (List<?>) values) {
                if (!(item instanceof String) || ((String) item).length() > 512 || hasControl((String) item)
                        || ++budget[1] > 2000 || (budget[2] += ((String) item).length()) > 256 * 1024) {
                    throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
                }
                mapped.add((String) item);
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("comparisonOperator", op);
            p.put("field", field);
            p.put("not", not);
            p.put("values", mapped);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("on", p);
            return result;
        }
        throw new AdsQueryError("ADS_REPORT_V1_FILTER_INVALID");
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1f || c == 0x7f) return true;
        }
        return false;
    }

    private static boolean safeId(Object value) {
        return value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= 512
                && ((String) value).trim().equals(value) && !hasControl((String) value);
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

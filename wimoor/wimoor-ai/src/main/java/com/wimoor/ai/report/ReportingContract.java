package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ported from {@code amazon-ads/reporting-contract.ts}.
 *
 * <p>Parses ONLY the Reporting-v1 task envelope; it never downloads or exposes
 * temporary part URLs (the returned part view intentionally drops {@code url}).
 * The body must contain exactly one of success/error; strict shape, bounded arrays,
 * HTTPS URL hygiene, field/warning-code whitelists and ISO datetime validation are
 * all preserved.
 */
public final class ReportingContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DATETIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern FIELD = Pattern.compile("[a-zA-Z0-9]+\\.[a-zA-Z0-9]+");
    private static final Set<String> STATUSES = new HashSet<>(Arrays.asList("PENDING", "PROCESSING", "COMPLETED", "FAILED"));
    private static final Set<String> FORMATS = new HashSet<>(Arrays.asList(
            "CSV", "GZIP_JSON", "PARTITIONED_CSV", "PARTITIONED_GZIP_JSON"));
    private static final Set<String> WARNING_CODES = new HashSet<>(Arrays.asList(
            "DATE_LIMITED", "NO_PERMISSION", "PENDING_DEPRECATION"));

    private ReportingContract() {}

    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED }

    public static final class Warning {
        public final String field;
        public final List<String> codes;

        Warning(String field, List<String> codes) { this.field = field; this.codes = codes; }
    }

    /** Part metadata: URLs are deliberately not carried into the domain type. */
    public static final class Part {
        public final long sizeInBytes;
        public final String urlExpirationDateTime;

        Part(long sizeInBytes, String urlExpirationDateTime) {
            this.sizeInBytes = sizeInBytes;
            this.urlExpirationDateTime = urlExpirationDateTime;
        }
    }

    public static final class Envelope {
        public String reportId;
        public Status status;
        public String format;
        public String createdAt;
        public String updatedAt;
        public String completedAt;
        public String failureCode;
        public final List<Warning> warnings = new ArrayList<>();
        public final List<Part> parts = new ArrayList<>();
    }

    public static final class RequestError {
        public final List<String> codes = new ArrayList<>();
        public final List<String> fieldLocations = new ArrayList<>();
    }

    public static final class Result {
        public final Envelope envelope;
        public final RequestError error;

        Result(Envelope envelope, RequestError error) {
            this.envelope = envelope;
            this.error = error;
        }

        public boolean isError() { return error != null; }
    }

    /** Parses a JSON object (as text or JsonNode) into a validated envelope/error. */
    public static Result parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
        }
        return parse(root);
    }

    public static Result parse(JsonNode body) {
        if (body == null || !body.isObject()) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
        JsonNode success = body.get("success");
        JsonNode error = body.get("error");
        if ((success != null && !success.isArray()) || (error != null && !error.isArray())) {
            throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
        }
        int successSize = success == null ? 0 : success.size();
        int errorSize = error == null ? 0 : error.size();
        if (successSize + errorSize != 1) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");

        if (errorSize > 0) {
            JsonNode indexed = error.get(0);
            if (!isRecord(indexed) || indexed.path("index").asInt(-1) != 0) invalid();
            JsonNode issues = indexed.get("errors");
            if (issues == null || !issues.isArray() || issues.size() < 1 || issues.size() > 20) invalid();
            RequestError result = new RequestError();
            for (JsonNode issue : issues) {
                if (!isRecord(issue)) invalid();
                JsonNode code = issue.get("code");
                if (code == null || !code.isTextual() || code.asText().isEmpty()) invalid();
                result.codes.add(code.asText());
                JsonNode location = issue.get("fieldLocation");
                if (location != null && !location.isTextual()) invalid();
                result.fieldLocations.add(location == null ? null : location.asText());
            }
            return new Result(null, result);
        }

        JsonNode indexed = success.get(0);
        JsonNode report = isRecord(indexed) && indexed.path("index").asInt(-1) == 0
                ? indexed.get("report") : null;
        if (!isRecord(report)) invalid();
        JsonNode reportId = report.get("reportId");
        JsonNode status = report.get("status");
        JsonNode format = report.get("format");
        if (reportId == null || !reportId.isTextual() || reportId.asText().isEmpty()
                || status == null || !status.isTextual() || !STATUSES.contains(status.asText())
                || format == null || !format.isTextual() || !FORMATS.contains(format.asText())
                || !validDateTime(report.get("creationDateTime"))
                || !validDateTime(report.get("lastUpdatedDateTime"))
                || (report.has("completedDateTime") && !validDateTime(report.get("completedDateTime")))
                || (report.has("failureCode") && !report.get("failureCode").isTextual())) {
            invalid();
        }
        JsonNode periods = report.get("periods");
        if (periods == null || !periods.isArray() || periods.size() != 1 || !validPeriod(periods.get(0))) invalid();
        JsonNode query = report.get("query");
        if (!isRecord(query)) invalid();
        JsonNode fields = query.get("fields");
        if (fields == null || !fields.isArray() || fields.size() < 1 || fields.size() > 1000) invalid();
        for (JsonNode field : fields) if (!field.isTextual()) invalid();

        JsonNode rawParts = report.get("completedReportParts");
        if (rawParts != null && (!rawParts.isArray() || rawParts.size() > 100)) invalid();
        if (rawParts != null) {
            for (JsonNode part : rawParts) {
                if (!isRecord(part)) invalid();
                JsonNode size = part.get("sizeInBytes");
                JsonNode url = part.get("url");
                if (size == null || !size.isIntegralNumber() || !size.canConvertToLong() || size.asLong() < 0
                        || url == null || !url.isTextual() || !isHttpsUrl(url.asText())
                        || !validDateTime(part.get("urlExpirationDateTime"))) invalid();
            }
        }
        JsonNode rawWarnings = report.get("warnings");
        if (rawWarnings != null && (!rawWarnings.isArray() || rawWarnings.size() > 1000)) invalid();
        if (rawWarnings != null) {
            for (JsonNode warningValue : rawWarnings) {
                if (!isRecord(warningValue)) invalid();
                JsonNode field = warningValue.get("field");
                JsonNode codes = warningValue.get("warningCodes");
                if (field == null || !field.isTextual() || !FIELD.matcher(field.asText()).matches()
                        || codes == null || !codes.isArray() || codes.size() < 1 || codes.size() > 100) invalid();
                for (JsonNode code : codes) {
                    if (!code.isTextual() || !WARNING_CODES.contains(code.asText())) invalid();
                }
            }
        }

        Envelope envelope = new Envelope();
        envelope.reportId = reportId.asText();
        envelope.status = Status.valueOf(status.asText());
        envelope.format = format.asText();
        envelope.createdAt = report.get("creationDateTime").asText();
        envelope.updatedAt = report.get("lastUpdatedDateTime").asText();
        if (report.has("completedDateTime")) envelope.completedAt = report.get("completedDateTime").asText();
        if (report.has("failureCode")) envelope.failureCode = report.get("failureCode").asText();
        if (rawWarnings != null) {
            for (JsonNode warningValue : rawWarnings) {
                List<String> codes = new ArrayList<>();
                for (JsonNode code : warningValue.get("warningCodes")) codes.add(code.asText());
                envelope.warnings.add(new Warning(warningValue.get("field").asText(), codes));
            }
        }
        if (rawParts != null) {
            for (JsonNode part : rawParts) {
                envelope.parts.add(new Part(part.get("sizeInBytes").asLong(),
                        part.get("urlExpirationDateTime").asText()));
            }
        }
        return new Result(envelope, null);
    }

    private static boolean isRecord(JsonNode node) {
        return node != null && node.isObject();
    }

    private static boolean validDateTime(JsonNode node) {
        if (node == null || !node.isTextual() || !DATETIME.matcher(node.asText()).matches()) return false;
        try {
            java.time.OffsetDateTime.parse(node.asText());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean validPeriod(JsonNode node) {
        if (!isRecord(node)) return false;
        JsonNode datePeriod = node.get("datePeriod");
        if (!isRecord(datePeriod)) return false;
        JsonNode start = datePeriod.get("startDate");
        JsonNode end = datePeriod.get("endDate");
        return start != null && start.isTextual() && end != null && end.isTextual()
                && isDateOnly(start.asText()) && isDateOnly(end.asText())
                && start.asText().compareTo(end.asText()) <= 0;
    }

    private static boolean isDateOnly(String value) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return java.time.LocalDate.parse(value).toString().equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isHttpsUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            return value.startsWith("https://") && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void invalid() { throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID"); }
}

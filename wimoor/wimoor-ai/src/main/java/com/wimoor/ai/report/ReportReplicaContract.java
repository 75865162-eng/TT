package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/report-replica-contract.ts}.
 *
 * <p>Strict parser for a cloud->local ads-report replica. It rejects any drift in
 * shape, range, identity uniqueness, metric precision, raw dimensions or the
 * whole-body checksum. A malformed replica must never publish a partial version.
 */
public final class ReportReplicaContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int FACT_LIMIT = 500;
    public static final int VERSION_LIMIT = 50;

    /** Field set mirrored from reportReplicaFactSelect; order is irrelevant for shape checks. */
    public static final List<String> FACT_FIELDS = Arrays.asList(
            "reportType", "adProduct", "reportDate", "factKey", "campaignId", "adGroupId",
            "targetingId", "advertisedAsin", "searchTerm", "placement", "impressions",
            "clicks", "spend", "orders", "sales", "units", "currencyCode",
            "attributionWindow", "apiVersion", "rawDimensions", "syncedAt");
    private static final List<String> TOP_FIELDS = Arrays.asList(
            "version", "domain", "profileRecordId", "amazonProfileId", "sellerAccountId",
            "marketplaceId", "region", "currencyCode", "timezone", "versions", "digest");
    private static final List<String> VERSION_FIELDS = Arrays.asList(
            "id", "requestedFrom", "requestedTo", "rowCount", "publishedAt", "sourceTask", "facts");
    private static final List<String> TASK_FIELDS = Arrays.asList("id", "stage", "finishedAt");

    private ReportReplicaContract() {}

    private static AdsQueryError invalid() {
        return new AdsQueryError("ADS_REPORT_REPLICA_INVALID", 409);
    }

    public static final class Version {
        public String id;
        public String requestedFrom;
        public String requestedTo;
        public int rowCount;
        public String publishedAt;
        public String taskId;
        public String taskStage;
        public String taskFinishedAt;
        public final List<JsonNode> facts = new ArrayList<>();
    }

    public static final class Replica {
        public String profileRecordId;
        public String amazonProfileId;
        public String sellerAccountId;
        public String marketplaceId;
        public String region;
        public String currencyCode;
        public String timezone;
        public final List<Version> versions = new ArrayList<>();
        public String digest;
        public JsonNode body;
    }

    /** Parses and fully validates a replica JSON document. */
    public static Replica parse(String json) {
        JsonNode input;
        try {
            input = MAPPER.readTree(json);
        } catch (Exception e) {
            throw invalid();
        }
        Replica replica = new Replica();
        if (!shape(input, TOP_FIELDS)) throw invalid();
        if (input.path("version").asInt() != 1 || !"ads-reports".equals(input.path("domain").asText())) throw invalid();
        JsonNode versions = input.get("versions");
        if (versions == null || !versions.isArray() || versions.size() < 1 || versions.size() > VERSION_LIMIT) throw invalid();
        if (input.toString().getBytes(StandardCharsets.UTF_8).length > 450 * 1024) throw invalid();
        for (String key : Arrays.asList("profileRecordId", "amazonProfileId", "sellerAccountId", "region")) {
            if (!text(input.get(key))) throw invalid();
        }
        for (String key : Arrays.asList("marketplaceId", "currencyCode", "timezone")) {
            JsonNode value = input.get(key);
            if (!value.isNull() && !text(value)) throw invalid();
        }
        replica.profileRecordId = input.get("profileRecordId").asText();
        replica.amazonProfileId = input.get("amazonProfileId").asText();
        replica.sellerAccountId = input.get("sellerAccountId").asText();
        replica.marketplaceId = input.get("marketplaceId").isNull() ? null : input.get("marketplaceId").asText();
        replica.region = input.get("region").asText();
        replica.currencyCode = input.get("currencyCode").isNull() ? null : input.get("currencyCode").asText();
        replica.timezone = input.get("timezone").isNull() ? null : input.get("timezone").asText();

        Set<String> versionIds = new HashSet<>();
        Set<String> taskIds = new HashSet<>();
        int total = 0;
        for (JsonNode value : versions) {
            if (!shape(value, VERSION_FIELDS)) throw invalid();
            Version version = new Version();
            version.id = value.get("id").asText();
            if (!text(value.get("id")) || !versionIds.add(version.id)
                    || !isoDate(value.get("publishedAt"))) throw invalid();
            version.publishedAt = value.get("publishedAt").asText();
            JsonNode from = value.get("requestedFrom");
            JsonNode to = value.get("requestedTo");
            version.requestedFrom = from.isNull() ? null : from.asText();
            version.requestedTo = to.isNull() ? null : to.asText();
            if (!from.isNull() && !day(from)) throw invalid();
            if (!to.isNull() && !day(to)) throw invalid();
            if (!from.isNull() && !to.isNull() && version.requestedFrom.compareTo(version.requestedTo) > 0) throw invalid();
            JsonNode facts = value.get("facts");
            JsonNode rowCount = value.get("rowCount");
            if (!facts.isArray() || !rowCount.isIntegralNumber() || rowCount.asInt() != facts.size()) throw invalid();
            total += facts.size();
            if (total > FACT_LIMIT) throw invalid();
            version.rowCount = facts.size();

            JsonNode task = value.get("sourceTask");
            if (!shape(task, TASK_FIELDS) || !text(task.get("id")) || !text(task.get("stage"))
                    || !isoDate(task.get("finishedAt")) || !taskIds.add(task.get("id").asText())) throw invalid();
            version.taskId = task.get("id").asText();
            version.taskStage = task.get("stage").asText();
            version.taskFinishedAt = task.get("finishedAt").asText();

            Set<String> identities = new HashSet<>();
            for (JsonNode fact : facts) {
                validateFact(fact);
                String reportDate = fact.get("reportDate").asText();
                if (version.requestedFrom != null && reportDate.compareTo(version.requestedFrom) < 0) throw invalid();
                if (version.requestedTo != null && reportDate.compareTo(version.requestedTo) > 0) throw invalid();
                String identity = json(Arrays.asList(
                        fact.get("reportType").asText(), fact.get("adProduct").asText(),
                        reportDate, fact.get("factKey").asText()));
                if (!identities.add(identity)) throw invalid();
                version.facts.add(fact);
            }
            replica.versions.add(version);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!"digest".equals(entry.getKey())) body.put(entry.getKey(), toJava(entry.getValue()));
        }
        String checksum = input.get("digest").asText();
        if (!text(input.get("digest")) || !checksum.equals(AdsCanonical.digest(body))) throw invalid();
        replica.digest = checksum;
        replica.body = input;
        return replica;
    }

    private static void validateFact(JsonNode fact) {
        if (!shape(fact, FACT_FIELDS)) throw invalid();
        if (!day(fact.get("reportDate")) || !isoDate(fact.get("syncedAt"))) throw invalid();
        for (String key : Arrays.asList("reportType", "adProduct", "apiVersion", "factKey")) {
            if (!text(fact.get(key), 10000)) throw invalid();
        }
        for (String key : Arrays.asList("campaignId", "adGroupId", "targetingId",
                "advertisedAsin", "searchTerm", "placement", "currencyCode", "attributionWindow")) {
            JsonNode value = fact.get(key);
            if (!value.isNull() && (!value.isTextual() || value.asText().length() > 10000)) throw invalid();
        }
        for (String key : Arrays.asList("impressions", "clicks", "orders", "units")) {
            JsonNode value = fact.get(key);
            if (!value.isNull()) {
                String s = value.asText();
                if (!value.isTextual() || !s.matches("-?\\d{1,19}")) throw invalid();
                try {
                    if (new java.math.BigInteger(s).compareTo(new java.math.BigInteger("9223372036854775807")) > 0
                            || new java.math.BigInteger(s).compareTo(new java.math.BigInteger("-9223372036854775808")) < 0) throw invalid();
                } catch (NumberFormatException e) {
                    throw invalid();
                }
            }
        }
        for (String key : Arrays.asList("spend", "sales")) {
            JsonNode value = fact.get(key);
            if (!value.isNull() && (!value.isTextual() || !value.asText().matches("-?\\d{1,14}(\\.\\d{1,6})?"))) throw invalid();
        }
        JsonNode raw = fact.get("rawDimensions");
        if (raw == null || raw.isNull()) throw invalid();
        if (!AdsCanonical.digest(AdsCanonical.businessData(toJava(raw))).equals(AdsCanonical.digest(toJava(raw)))) throw invalid();
    }

    private static boolean shape(JsonNode value, List<String> keys) {
        if (value == null || !value.isObject() || value.size() != keys.size()) return false;
        for (String key : keys) if (!value.has(key)) return false;
        return true;
    }

    private static boolean text(JsonNode value) { return text(value, 200); }

    private static boolean text(JsonNode value, int max) {
        return value != null && value.isTextual() && !value.asText().isEmpty() && value.asText().length() <= max;
    }

    private static boolean isoDate(JsonNode value) {
        if (value == null || !value.isTextual()) return false;
        try {
            Instant.parse(value.asText());
            return value.asText().equals(Instant.parse(value.asText()).toString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean day(JsonNode value) {
        return isoDate(value) && value.asText().endsWith("T00:00:00.000Z");
    }

    private static String json(Object value) { return AdsCanonical.json(value); }

    private static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asInt();
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) list.add(toJava(item));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                map.put(entry.getKey(), toJava(entry.getValue()));
            }
            return map;
        }
        return node.asText();
    }
}

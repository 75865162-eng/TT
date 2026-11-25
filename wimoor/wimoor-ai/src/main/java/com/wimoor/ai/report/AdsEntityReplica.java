package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from {@code entity-replica-contract.ts} and {@code entity-replica.ts}.
 *
 * <p>Strict entity-snapshot replica parser and cloud/local publish boundary. The
 * replica carries at most 500 rows, each with a stable fingerprint; publish is
 * local-only, refuses to overwrite a version whose fingerprint changed, and treats a
 * repeated identical publish as a replay rather than a new import.
 */
public final class AdsEntityReplica {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int REPLICA_LIMIT = 500;

    private AdsEntityReplica() {}

    public static final List<String> ROW_FIELDS = Arrays.asList(
            "entityType", "externalId", "parentExternalId", "state", "payload", "sourceUpdatedAt");

    public static final class Row {
        public String entityType;
        public String externalId;
        public String parentExternalId;
        public String state;
        public Map<String, Object> payload;
        public String sourceUpdatedAt;
    }

    public static final class Replica {
        public String domain = "ads-entities";
        public String profileRecordId;
        public String fingerprint;
        public final List<Row> rows = new ArrayList<>();
    }

    private static SyncInputError invalid() {
        return new SyncInputError("广告实体副本格式、范围或完整性无效，未发布本地版本。", 409);
    }

    /** Parses a replica JSON document with strict field/identity checks. */
    public static Replica parse(String json) {
        JsonNode input;
        try {
            input = MAPPER.readTree(json);
        } catch (Exception e) {
            throw invalid();
        }
        if (input == null || !input.isObject()) throw invalid();
        JsonNode rows = input.get("rows");
        if (rows == null || !rows.isArray() || rows.size() < 1 || rows.size() > REPLICA_LIMIT) throw invalid();
        JsonNode profileId = input.get("profileRecordId");
        if (profileId == null || !profileId.isTextual() || profileId.asText().isEmpty()) throw invalid();
        Replica replica = new Replica();
        replica.profileRecordId = profileId.asText();
        Set<String> seen = new HashSet<>();
        for (JsonNode value : rows) {
            if (!value.isObject() || value.size() != ROW_FIELDS.size()) throw invalid();
            for (String field : ROW_FIELDS) if (!value.has(field)) throw invalid();
            Row row = new Row();
            row.entityType = value.get("entityType").asText();
            row.externalId = value.get("externalId").asText();
            if (!row.entityType.matches("(SP|SB|SD):[a-z-]+") || !row.externalId.matches("\\d{1,40}")) throw invalid();
            JsonNode parent = value.get("parentExternalId");
            row.parentExternalId = parent.isNull() ? null : parent.asText();
            JsonNode state = value.get("state");
            row.state = state.isNull() ? null : state.asText();
            JsonNode payload = value.get("payload");
            if (payload == null || !payload.isObject()) throw invalid();
            row.payload = toMap(payload);
            JsonNode updated = value.get("sourceUpdatedAt");
            row.sourceUpdatedAt = updated.isNull() ? null : updated.asText();
            String identity = row.entityType + ":" + row.externalId;
            if (!seen.add(identity)) throw invalid();
            replica.rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("domain", replica.domain);
        body.put("profileRecordId", replica.profileRecordId);
        body.put("rows", replica.rows);
        String checksum = AdsCanonical.digest(body);
        JsonNode fingerprint = input.get("fingerprint");
        if (fingerprint == null || !fingerprint.isTextual() || !checksum.equals(fingerprint.asText())) throw invalid();
        replica.fingerprint = checksum;
        return replica;
    }

    /** Local fingerprint of a replica's rows, used for replay detection. */
    public static String versionFingerprint(Replica replica) {
        return AdsCanonical.digest(Arrays.asList(replica.profileRecordId, replica.rows));
    }

    public static Map<String, Object> publish(Replica replica, String expectedProfileId,
                                              String expectedFingerprint, Store store) {
        if (!expectedProfileId.equals(replica.profileRecordId)) {
            throw new SyncInputError("实体副本 Profile 不匹配。", 409);
        }
        if (expectedFingerprint != null && !expectedFingerprint.equals(replica.fingerprint)) {
            throw new SyncInputError("实体副本指纹不匹配。", 409);
        }
        String fingerprint = versionFingerprint(replica);
        String prior = store.load(replica.profileRecordId);
        boolean replay = prior != null && prior.equals(fingerprint);
        if (prior != null && !replay) {
            throw new SyncInputError("已发布实体副本内容发生变化，禁止覆盖历史版本。", 409);
        }
        if (!replay) store.save(replica.profileRecordId, fingerprint);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "ads-entities");
        result.put("profileRecordId", replica.profileRecordId);
        result.put("replayed", replay);
        result.put("rowCount", replica.rows.size());
        result.put("amazonApiCalled", false);
        return result;
    }

    public static final class Store {
        private final Map<String, String> fingerprints = new java.util.concurrent.ConcurrentHashMap<>();

        public String load(String profileId) { return fingerprints.get(profileId); }

        public void save(String profileId, String fingerprint) { fingerprints.put(profileId, fingerprint); }
    }

    private static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        java.util.Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            map.put(entry.getKey(), value.isTextual() ? value.asText()
                    : value.isNumber() ? value.numberValue()
                    : value.isBoolean() ? value.asBoolean() : AdsCanonical.json(value));
        }
        return map;
    }
}

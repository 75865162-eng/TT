package com.wimoor.ai.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ported from {@code amazon-ads/report-scope.ts}.
 *
 * <p>Produces a stable SHA-256 identity from the frozen task/profile scope and
 * the manifest contract. Key order in the manifest is canonicalised (sorted keys)
 * and the whole payload is serialized compactly, so the digest is independent of
 * lease renewal, JSONB key order or replay after a restart. A stored digest that
 * differs from the current one is a 409 conflict — the contract may never be
 * silently swapped for the same task.
 */
public final class ReportScope {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportScope() {}

    /** Task identity subset used by the report digest. ISO date strings are UTC day labels. */
    public static final class Task {
        public String id;
        public String organizationId;
        public String workspaceId;
        public String requestedByUserId;
        public String planId;
        public long planRevision;
        public String profileId;
        public String requestedFrom;
        public String requestedTo;
    }

    /** Profile identity subset used by the report digest. */
    public static final class Profile {
        public String id;
        public String authorizationId;
        public String profileId;
        public String region;
        public String sellerAccountId;
        public String marketplaceId;
        public String currencyCode;
        public String timezone;
    }

    public static String digest(Task task, Profile profile, long authorizationRevision,
                                Map<String, Object> manifest) {
        List<Object> payload = new ArrayList<>();
        payload.add(task.id);
        payload.add(task.organizationId);
        payload.add(task.workspaceId);
        payload.add(task.requestedByUserId);
        payload.add(task.planId);
        payload.add(task.planRevision);
        payload.add(task.profileId);
        payload.add(task.requestedFrom);
        payload.add(task.requestedTo);
        payload.add(profile.id);
        payload.add(profile.authorizationId);
        payload.add(authorizationRevision);
        payload.add(profile.profileId);
        payload.add(profile.region);
        payload.add(profile.sellerAccountId);
        payload.add(profile.marketplaceId);
        payload.add(profile.currencyCode);
        payload.add(profile.timezone);
        payload.add(contract(manifest));
        return sha256(toJson(payload));
    }

    /** Sorted [key, value] pairs, mirroring Object.keys(manifest).sort().map(...). */
    private static List<List<Object>> contract(Map<String, Object> manifest) {
        TreeMap<String, Object> sorted = new TreeMap<>(manifest == null ? Map.of() : manifest);
        List<List<Object>> pairs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            List<Object> pair = new ArrayList<>();
            pair.add(entry.getKey());
            pair.add(entry.getValue());
            pairs.add(pair);
        }
        return pairs;
    }

    public static void assertDigest(String stored, String current) {
        if (stored == null || !stored.equals(current)) {
            throw new AdsQueryError("ADS_REPORT_FROZEN_SCOPE_CHANGED", 409);
        }
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("scope JSON serialization failed", e);
        }
    }

    static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

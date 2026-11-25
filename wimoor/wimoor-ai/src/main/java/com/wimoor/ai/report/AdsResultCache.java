package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code amazon-ads/result-cache.ts}.
 *
 * <p>Read-through result cache with two non-negotiable rules: every key carries an
 * access fingerprint derived from the SERVER-resolved data access, and an empty
 * fingerprint bypasses the cache entirely. A cached body is served only while its
 * data version still matches. Invalidation is fail-open (a cache error never 500s a
 * page). Pure helpers are deterministic and IO-free.
 */
public final class AdsResultCache {

    private AdsResultCache() {}

    public static final String PREFIX = "amazon:";
    public static final int DEFAULT_TTL = 120;
    public static final int EMPTY_TTL = 30;
    public static final double TTL_JITTER_RATIO = 0.1;

    private static final Map<String, Integer> DOMAIN_TTL = new java.util.HashMap<>();
    static {
        DOMAIN_TTL.put("report_acquisition", 120);
        DOMAIN_TTL.put("ads_metadata", 300);
    }

    /** Canonical query normalisation: sorted keys, order-independent arrays, empties dropped. */
    public static String normalize(Map<String, Object> input) {
        List<String> parts = new ArrayList<>();
        for (String key : new TreeSet<>(input.keySet())) {
            Object value = input.get(key);
            if (value == null || "".equals(value)) continue;
            if (value instanceof List) {
                TreeSet<String> items = new TreeSet<>();
                for (Object item : (List<?>) value) {
                    if (item != null && !"".equals(String.valueOf(item))) items.add(String.valueOf(item));
                }
                if (!items.isEmpty()) parts.add(key + "=[" + String.join(",", items) + "]");
            } else if (value instanceof Map) {
                parts.add(key + "=" + AdsCanonical.json(value));
            } else {
                parts.add(key + "=" + value);
            }
        }
        return String.join("&", parts);
    }

    /** Stable fingerprint of the caller's current access; "" means bypass. */
    public static String accessFingerprint(boolean allSellers, List<String> sellerIds) {
        if (allSellers) return sha256("all").substring(0, 32);
        TreeSet<String> sorted = new TreeSet<>(sellerIds);
        if (sorted.isEmpty()) return "";
        return sha256(AdsCanonical.json(new ArrayList<>(sorted))).substring(0, 32);
    }

    public static String key(String organizationId, String workspaceId, String domain,
                             String accessFingerprint, String normalizedQuery) {
        return PREFIX + "wb:" + organizationId + ":" + workspaceId + ":" + domain + ":"
                + (accessFingerprint == null || accessFingerprint.isEmpty() ? "INVALID" : accessFingerprint)
                + ":" + sha256(normalizedQuery).substring(0, 32);
    }

    public static String domainInvalidationPrefix(String organizationId, String workspaceId, String domain) {
        return PREFIX + "wb:" + organizationId + ":" + workspaceId + ":" + domain + ":";
    }

    public static String tenantInvalidationPrefix(String organizationId, String workspaceId) {
        return PREFIX + "wb:" + organizationId + ":" + workspaceId + ":";
    }

    public static int ttlWithJitter(int baseTtl, double random) {
        if (baseTtl <= 0) return baseTtl;
        double r = Math.min(1, Math.max(0, random));
        return (int) Math.max(1L, Math.round(baseTtl * (1 - TTL_JITTER_RATIO + 2 * TTL_JITTER_RATIO * r)));
    }

    public static int ttlFor(String domain, double random) {
        int base = DOMAIN_TTL.getOrDefault(domain, DEFAULT_TTL);
        return ttlWithJitter(base, random);
    }

    /** Minimal in-memory cache preserving version checks and prefix invalidation. */
    public static final class Cache {
        private final Map<String, Cached> entries = new ConcurrentHashMap<>();

        private static final class Cached {
            final String version;
            final Object body;

            Cached(String version, Object body) {
                this.version = version;
                this.body = body;
            }
        }

        public Object read(String key, String currentVersion) {
            if (currentVersion == null || currentVersion.isEmpty()) return null;
            Cached cached = entries.get(key);
            return cached != null && currentVersion.equals(cached.version) ? cached.body : null;
        }

        public void write(String key, String currentVersion, Object body) {
            if (currentVersion != null && !currentVersion.isEmpty()) entries.put(key, new Cached(currentVersion, body));
        }

        public int invalidatePrefix(String prefix) {
            int removed = 0;
            for (String key : new ArrayList<>(entries.keySet())) {
                if (key.startsWith(prefix)) {
                    entries.remove(key);
                    removed++;
                }
            }
            return removed;
        }
    }

    private static String sha256(String value) {
        return ReportScope.sha256(value);
    }
}

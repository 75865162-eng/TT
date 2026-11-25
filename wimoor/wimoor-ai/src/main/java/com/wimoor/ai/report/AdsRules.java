package com.wimoor.ai.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from a group of small deterministic rule modules:
 * {@code token-expiry.ts}, {@code task-routing.ts}, {@code query-budget.ts},
 * {@code profile-reconciliation.ts}, {@code entity-history.ts} and
 * {@code invalidate-after-sync.ts}.
 *
 * <p>All rules are pure or bounded in-memory logic: token expiry tiers, structure-task
 * routing (unknown tasks are never treated as structure merely because they share a
 * plan), a per-process query budget with a fixed window, missing-profile detection
 * limited to successfully checked regions, observation deduplication by fingerprint,
 * and fail-open cache invalidation after a successful sync.
 */
public final class AdsRules {

    private AdsRules() {}

    /** Token expiry notice mirroring AdsTokenExpiryNotice. */
    public static final class Notice {
        public final String level;
        public final Integer daysRemaining;

        Notice(String level, Integer daysRemaining) {
            this.level = level;
            this.daysRemaining = daysRemaining;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("level", level);
            map.put("daysRemaining", daysRemaining);
            return map;
        }
    }

    public static Notice tokenExpiry(String expiresAt, long nowMillis) {
        if (expiresAt == null) return new Notice("UNKNOWN", null);
        long expiry;
        try {
            expiry = Instant.parse(expiresAt).toEpochMilli();
        } catch (RuntimeException e) {
            return new Notice("UNKNOWN", null);
        }
        if (expiry <= nowMillis) return new Notice("EXPIRED", 0);
        long days = (long) Math.ceil((expiry - nowMillis) / 86_400_000.0);
        if (days <= 7) return new Notice("WITHIN_7_DAYS", (int) days);
        if (days <= 30) return new Notice("WITHIN_30_DAYS", (int) days);
        if (days <= 60) return new Notice("WITHIN_60_DAYS", (int) days);
        return null;
    }

    private static final Set<String> STRUCTURE_TRIGGERS = new HashSet<>(Arrays.asList(
            "schedule", "scheduled-structure", "manual-structure", "manual-retry"));
    private static final Set<String> STRUCTURE_STAGES = new HashSet<>(Arrays.asList(
            "DISCOVER", "campaigns", "ad-groups", "keywords", "targets", "negatives",
            "product-ads", "reports", "COMPLETED_SP_STRUCTURE"));

    /** A routing task's identity/counts, mirroring the Prisma select shape. */
    public static final class RoutingTask {
        public String trigger;
        public String stage;
        public boolean hasRequestedDates;
        public boolean hasReportRequests;
        public boolean hasReportVersions;
    }

    public static boolean isStructureTask(RoutingTask task) {
        return task != null && STRUCTURE_TRIGGERS.contains(task.trigger)
                && STRUCTURE_STAGES.contains(task.stage)
                && !task.hasRequestedDates && !task.hasReportRequests && !task.hasReportVersions;
    }

    /** Sliding-window query budget. Exceeding the limit throws 429. */
    public static final class QueryBudget {
        private final int limit;
        private final long windowMs;
        private final int maxKeys;
        private final Map<String, long[]> windows = new ConcurrentHashMap<>();

        public QueryBudget() { this(60, 60_000L, 10_000); }

        public QueryBudget(int limit, long windowMs, int maxKeys) {
            this.limit = limit;
            this.windowMs = windowMs;
            this.maxKeys = maxKeys;
        }

        public void consume(String credentialId, long nowMillis) {
            for (Map.Entry<String, long[]> entry : new HashSet<>(windows.entrySet())) {
                if (entry.getValue()[0] <= nowMillis) windows.remove(entry.getKey());
            }
            long[] prior = windows.get(credentialId);
            if ((prior == null && windows.size() >= maxKeys) || (prior != null && prior[1] >= limit)) {
                throw new SyncInputError("Ads 查询频率超限，请稍后重试。", 429);
            }
            long until = prior == null ? nowMillis + windowMs : prior[0];
            long count = (prior == null ? 0 : prior[1]) + 1;
            windows.put(credentialId, new long[]{until, count});
        }
    }

    /** Identity tuple shared by profile reconciliation. */
    public static final class ProfileIdentity {
        public String id;
        public String region;
        public String profileId;

        public ProfileIdentity(String id, String region, String profileId) {
            this.id = id;
            this.region = region;
            this.profileId = profileId;
        }
    }

    /** Existing profiles missing from a successful discovery are stale. */
    public static List<String> missingProfileIds(List<ProfileIdentity> existing,
                                                 List<ProfileIdentity> discovered,
                                                 List<String> successfulRegions) {
        Set<String> discoveredKeys = new HashSet<>();
        for (ProfileIdentity profile : discovered) discoveredKeys.add(profile.region + ":" + profile.profileId);
        Set<String> checked = new HashSet<>(successfulRegions);
        List<String> missing = new ArrayList<>();
        for (ProfileIdentity profile : existing) {
            if (checked.contains(profile.region) && !discoveredKeys.contains(profile.region + ":" + profile.profileId)) {
                missing.add(profile.id);
            }
        }
        return missing;
    }

    /** Observation deduplication: no write when before/after fingerprints are equal. */
    public static String observationHash(Object before, Object after) {
        return AdsCanonical.digest(Arrays.asList(before, after));
    }

    public static boolean observationChanged(Object before, Object after) {
        if (before == null) return true;
        return !AdsCanonical.digest(before).equals(AdsCanonical.digest(after));
    }

    /** Fail-open invalidation: a cache failure never re-classifies a completed sync. */
    public interface CacheInvalidator {
        void invalidate(String organizationId, String workspaceId);
    }

    public static boolean invalidateAfterSync(String organizationId, String workspaceId,
                                              CacheInvalidator invalidator) {
        try {
            invalidator.invalidate(organizationId, workspaceId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

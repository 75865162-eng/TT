package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code amazon-ads/report-replica.ts}.
 *
 * <p>Cloud/local replica boundary. Export refuses to return an empty or partial
 * batch: an absent published report is never represented as an empty report. Publish
 * is local-only, preserves original IDs and publication times (so the existing
 * revision rank tie-breaker stays stable), and refuses to overwrite a version whose
 * checksum or local fingerprint changed. A repeated publish is a replay, not a new import.
 */
public final class ReportReplica {

    private ReportReplica() {}

    public static final class ProfileBinding {
        public String profileRecordId;
        public String amazonProfileId;
        public String sellerAccountId;
        public String marketplaceId;
        public String region;
        public String currencyCode;
        public String timezone;
    }

    /** In-memory replica store replacing Prisma version/task/fact/audit tables. */
    public static final class Store {
        private final Map<String, ReportReplicaContract.Replica> byProfile = new ConcurrentHashMap<>();
        private final Map<String, String> checksums = new ConcurrentHashMap<>();
        private final Map<String, String> fingerprints = new ConcurrentHashMap<>();

        public ReportReplicaContract.Replica find(String profileRecordId) {
            return byProfile.get(profileRecordId);
        }

        public void save(String profileRecordId, ReportReplicaContract.Replica replica) {
            byProfile.put(profileRecordId, replica);
        }

        public String checksum(String entityId) { return checksums.get(entityId); }

        public String fingerprint(String entityId) { return fingerprints.get(entityId); }

        public void record(String entityId, String checksum, String fingerprint) {
            checksums.put(entityId, checksum);
            fingerprints.put(entityId, fingerprint);
        }
    }

    /** Entity ID mirrors {@code ads-report-replica-${digest([scope, profile.id, version.id])}}. */
    public static String entityId(Object scope, String profileId, String versionId) {
        return "ads-report-replica-" + AdsCanonical.digest(java.util.Arrays.asList(scope, profileId, versionId));
    }

    /** Checksum binds all replica fields except versions/digest to one version. */
    public static String versionDigest(ReportReplicaContract.Replica replica,
                                       ReportReplicaContract.Version version) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("domain", "ads-reports");
        binding.put("profileRecordId", replica.profileRecordId);
        binding.put("amazonProfileId", replica.amazonProfileId);
        binding.put("sellerAccountId", replica.sellerAccountId);
        binding.put("marketplaceId", replica.marketplaceId);
        binding.put("region", replica.region);
        binding.put("currencyCode", replica.currencyCode);
        binding.put("timezone", replica.timezone);
        binding.put("version", version);
        return AdsCanonical.digest(binding);
    }

    /** Local fingerprint of a version's stored facts (canonical JSON). */
    public static String localFingerprint(ReportReplicaContract.Version version) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", version.id);
        snapshot.put("facts", version.facts);
        return AdsCanonical.digest(snapshot);
    }

    /** Export rejects absent/oversized data instead of returning a partial batch. */
    public static ReportReplicaContract.Replica export(ProfileBinding binding,
                                                       List<ReportReplicaContract.Version> published) {
        if (published == null || published.isEmpty()) {
            throw new SyncInputError("云端尚无已发布广告报表，不能将未接入数据作为空报告。", 409);
        }
        if (published.size() > ReportReplicaContract.VERSION_LIMIT) {
            throw new SyncInputError("广告报表版本超过整批容量，未返回部分版本。", 409);
        }
        int total = 0;
        for (ReportReplicaContract.Version version : published) {
            total += version.facts.size();
            if (total > ReportReplicaContract.FACT_LIMIT) {
                throw new SyncInputError("广告报表事实超过整批容量，未返回不完整数据。", 409);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("domain", "ads-reports");
        body.put("profileRecordId", binding.profileRecordId);
        body.put("amazonProfileId", binding.amazonProfileId);
        body.put("sellerAccountId", binding.sellerAccountId);
        body.put("marketplaceId", binding.marketplaceId);
        body.put("region", binding.region);
        body.put("currencyCode", binding.currencyCode);
        body.put("timezone", binding.timezone);
        body.put("versions", published);
        String checksum = AdsCanonical.digest(body);
        StringBuilder json = new StringBuilder(AdsCanonical.json(body));
        json.insert(json.length() - 1, ",\"digest\":" + AdsCanonical.json(checksum));
        return ReportReplicaContract.parse(json.toString());
    }

    /** Publish locally; repeated identical versions are replays, not imports. */
    public static Map<String, Object> publish(Store store, ReportReplicaContract.Replica replica,
                                              String expectedProfileRecordId, ProfileBinding local,
                                              boolean cloudEnabled, Object scope, String actor) {
        if (cloudEnabled) throw new SyncInputError("广告报表副本只能在本地发布。", 409);
        ReportReplicaContract.Replica parsed = replica;
        if (!expectedProfileRecordId.equals(parsed.profileRecordId)) {
            throw new SyncInputError("报表副本 Profile 不匹配。", 409);
        }
        if (!sameBinding(parsed, local)) {
            throw new SyncInputError("云端与本地广告账号、站点、币种或时区绑定不一致。", 409);
        }
        int imported = 0;
        for (ReportReplicaContract.Version version : parsed.versions) {
            String entityId = entityId(scope, local.profileRecordId, version.id);
            String checksum = versionDigest(parsed, version);
            String fingerprint = localFingerprint(version);
            String prior = store.checksum(entityId);
            if (prior != null) {
                if (!prior.equals(checksum)
                        || !fingerprint.equals(store.fingerprint(entityId))) {
                    throw new SyncInputError("已发布报表副本内容发生变化，禁止覆盖历史版本。", 409);
                }
                continue;
            }
            store.record(entityId, checksum, fingerprint);
            imported++;
        }
        store.save(local.profileRecordId, parsed);
        int factCount = 0;
        for (ReportReplicaContract.Version version : parsed.versions) factCount += version.rowCount;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "ads-reports");
        result.put("profileRecordId", local.profileRecordId);
        result.put("versionIds", versionIds(parsed));
        result.put("importedVersions", imported);
        result.put("replayed", imported == 0);
        result.put("factCount", factCount);
        result.put("amazonApiCalled", false);
        return result;
    }

    private static List<String> versionIds(ReportReplicaContract.Replica replica) {
        List<String> ids = new ArrayList<>();
        for (ReportReplicaContract.Version version : replica.versions) ids.add(version.id);
        return ids;
    }

    private static boolean sameBinding(ReportReplicaContract.Replica a, ProfileBinding b) {
        return eq(a.sellerAccountId, b.sellerAccountId) && eq(a.amazonProfileId, b.amazonProfileId)
                && eq(a.marketplaceId, b.marketplaceId) && eq(a.region, b.region)
                && eq(a.currencyCode, b.currencyCode) && eq(a.timezone, b.timezone);
    }

    private static boolean eq(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}

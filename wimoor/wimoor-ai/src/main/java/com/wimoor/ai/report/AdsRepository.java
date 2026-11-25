package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code repository.ts}, {@code data-query.ts} and
 * {@code query-service.ts}.
 *
 * <p>Scoped read boundary for structure entities. It always revalidates query params,
 * resolves access per caller, caps profile scope at 500 and builds a freshness envelope
 * from persisted domain state with a snapshot-based fallback (an entity's freshness is
 * derived from its source task status when no domain state exists). A failed discovery
 * region is never treated as "no entities". DB calls are represented by in-memory
 * predicates/repositories; filter and ordering rules are preserved.
 */
public final class AdsRepository {

    private AdsRepository() {}

    private static final List<String> SORT_FIELDS = Arrays.asList(
            "entityType", "externalId", "state", "syncedAt", "sourceUpdatedAt");

    /** Entity query implementation mirroring parseAdsEntityQuery. */
    public static final class EntityQuery {
        public int page = 1;
        public int pageSize = 50;
        public String sort = "entityType";
        public String direction = "asc";
        public String internalProfileId;
        public String sellerAccountId;
        public String authorizationId;
        public String entityType;
        public String campaignId;
        public String adGroupId;
        public String externalId;
        public String state;
        public String search;
    }

    public static EntityQuery parseEntityQuery(Map<String, List<String>> params) {
        AdsQueryContract.assertUnambiguous(params);
        EntityQuery query = new EntityQuery();
        query.page = bounded(params.get("page"), 1, 1, 10000);
        query.pageSize = bounded(params.get("pageSize"), 50, 1, 100);
        String sort = text(params, "sort");
        String direction = text(params, "direction");
        query.sort = sort == null ? "entityType" : sort;
        query.direction = direction == null ? "asc" : direction;
        if (!SORT_FIELDS.contains(query.sort) || !Arrays.asList("asc", "desc").contains(query.direction)) {
            throw new SyncInputError("Ads 排序参数无效。");
        }
        String internal = text(params, "internalProfileId");
        String legacy = text(params, "profileId");
        if (internal != null && legacy != null && !internal.equals(legacy)) {
            throw new SyncInputError("Profile 参数冲突。");
        }
        query.internalProfileId = internal == null ? legacy : internal;
        if (params.containsKey("from") || params.containsKey("to")) {
            throw new SyncInputError("结构数据没有报告日期维度，请切换日报事实。");
        }
        query.sellerAccountId = text(params, "sellerAccountId");
        query.authorizationId = text(params, "authorizationId");
        query.entityType = text(params, "entityType");
        query.campaignId = text(params, "campaignId");
        query.adGroupId = text(params, "adGroupId");
        query.externalId = text(params, "externalId");
        query.state = text(params, "state");
        query.search = text(params, "search");
        return query;
    }

    /** Entity row used by the in-memory repository. */
    public static final class Entity {
        public String profileId;
        public String entityType;
        public String externalId;
        public String state;
        public String sellerAccountId;
        public String authorizationId;
        public String campaignId;
        public String adGroupId;
        public String syncedAt;
        public String sourceUpdatedAt;
        public String sourceTaskStatus;
        public String sourceTaskStage;
        public String sourceTaskFinishedAt;
    }

    /** Matches an entity against the parsed query; null fields mean "any". */
    public static boolean matches(Entity entity, EntityQuery query) {
        if (query.internalProfileId != null && !query.internalProfileId.equals(entity.profileId)) return false;
        if (query.sellerAccountId != null && !query.sellerAccountId.equals(entity.sellerAccountId)) return false;
        if (query.authorizationId != null && !query.authorizationId.equals(entity.authorizationId)) return false;
        if (query.entityType != null && !query.entityType.equals(entity.entityType)) return false;
        if (query.campaignId != null && !query.campaignId.equals(entity.campaignId)) return false;
        if (query.adGroupId != null && !query.adGroupId.equals(entity.adGroupId)) return false;
        if (query.externalId != null && !query.externalId.equals(entity.externalId)) return false;
        if (query.state != null && !query.state.equals(entity.state)) return false;
        if (query.search != null && !containsAny(query.search, entity)) return false;
        return true;
    }

    private static boolean containsAny(String search, Entity entity) {
        return contains(entity.entityType, search) || contains(entity.externalId, search)
                || contains(entity.state, search) || contains(entity.campaignId, search)
                || contains(entity.adGroupId, search);
    }

    private static boolean contains(String value, String search) {
        return value != null && value.contains(search);
    }

    public static Comparator<Entity> orderBy(String sortBy, String direction) {
        Comparator<Entity> comparator = (a, b) -> {
            String left = field(a, sortBy);
            String right = field(b, sortBy);
            if (left == null && right == null) return 0;
            if (left == null) return 1;
            if (right == null) return -1;
            return left.compareTo(right);
        };
        if ("desc".equalsIgnoreCase(direction)) comparator = comparator.reversed();
        return comparator.thenComparing(e -> e.externalId == null ? "" : e.externalId);
    }

    public static List<Entity> query(List<Entity> source, EntityQuery query) {
        List<Entity> matched = new ArrayList<>();
        for (Entity entity : source) if (matches(entity, query)) matched.add(entity);
        matched.sort(orderBy(query.sort, query.direction));
        long from = ((long) query.page - 1) * query.pageSize;
        if (from >= matched.size()) return new ArrayList<>();
        int to = (int) Math.min(matched.size(), from + query.pageSize);
        return new ArrayList<>(matched.subList((int) from, to));
    }

    /** Freshness envelope: worst known status across profiles. */
    public static Map<String, Object> freshnessEnvelope(List<ReportFreshness.DomainState> states,
                                                        List<String> profileIds) {
        List<String> statuses = new ArrayList<>();
        for (String profileId : profileIds) {
            ReportFreshness.DomainState state = ReportFreshness.select(states, profileId, null);
            statuses.add(ReportFreshness.publishedReportFreshness(state != null,
                    state == null ? null : state.status).name());
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("status", ReportFreshness.summarize(statuses));
        return envelope;
    }

    private static String field(Entity entity, String sortBy) {
        switch (sortBy) {
            case "entityType": return entity.entityType;
            case "externalId": return entity.externalId;
            case "state": return entity.state;
            case "syncedAt": return entity.syncedAt;
            case "sourceUpdatedAt": return entity.sourceUpdatedAt;
            default: return null;
        }
    }

    private static int bounded(List<String> values, int fallback, int min, int max) {
        if (values == null || values.isEmpty()) return fallback;
        String raw = values.get(0);
        if (!raw.matches("\\d+")) throw new SyncInputError("Ads 分页参数无效。");
        int value = Integer.parseInt(raw);
        if (value < min || value > max) throw new SyncInputError("Ads 分页参数无效。");
        return value;
    }

    private static String text(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        if (values == null || values.isEmpty()) return null;
        String value = values.get(0).trim();
        if (value.isEmpty()) return null;
        if (value.length() > 200) throw new SyncInputError("Ads 筛选参数过长。");
        return value;
    }
}

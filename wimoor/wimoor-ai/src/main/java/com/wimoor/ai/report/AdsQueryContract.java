package com.wimoor.ai.report;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ported from {@code amazon-ads/query-contract.ts}.
 *
 * <p>Parses and validates the single read boundary used by both the API and MCP.
 * Invariants preserved exactly:
 * <ul>
 *   <li>conflicting repeated values and conflicting aliases are rejected before
 *       any scope or SQL is built;</li>
 *   <li>structure ("entities") reads have no date dimension — supplying from/to is an error;</li>
 *   <li>page (1..10000, default 1) and pageSize (1..100, default 50) are bounded integers;</li>
 *   <li>sort fields are whitelisted per kind; direction is asc/desc (default desc);</li>
 *   <li>dates are strict YYYY-MM-DD, default window is the last 30 days ending today,
 *       and the range may not exceed 367 days;</li>
 *   <li>text filters are trimmed; empty becomes null; over-long values are rejected
 *       (search 120 chars, every other filter 200 chars).</li>
 * </ul>
 */
public final class AdsQueryContract {

    private AdsQueryContract() {}

    private static final Set<String> ENTITY_SORTS = new LinkedHashSet<>(Arrays.asList(
            "name", "entityType", "externalId", "state", "syncedAt", "sourceUpdatedAt", "budgetAmount", "bid"));
    private static final Set<String> REPORT_SORTS = new LinkedHashSet<>(Arrays.asList(
            "reportDate", "campaignId", "adGroupId", "impressions", "clicks", "spend", "orders", "sales", "searchTerm"));
    private static final List<List<String>> ALIASES = Arrays.asList(
            Arrays.asList("profileRecordId", "internalProfileId", "profileId"),
            Arrays.asList("sortBy", "sort"),
            Arrays.asList("sortDirection", "direction"));

    /** Value object mirroring the TypeScript {@code AdsQueryInput} interface. */
    public static final class Input {
        public String kind;
        public String profileRecordId;
        public String sellerAccountId;
        public String authorizationId;
        public String marketplaceId;
        public String entityType;
        public String reportType;
        public String adProduct;
        public String campaignId;
        public String adGroupId;
        public String targetingId;
        public String advertisedAsin;
        public String searchTerm;
        public String placement;
        public String externalId;
        public String state;
        public String search;
        public String from;
        public String to;
        public int page;
        public int pageSize;
        public String sortBy;
        public String sortDirection;
    }

    /**
     * @param params query parameters; a key may carry several values (repeated query string keys)
     * @param nowUtcEpochMillis current time, used only to derive the default window
     */
    public static Input parse(Map<String, List<String>> params, long nowUtcEpochMillis) {
        assertUnambiguous(params);
        Input in = new Input();
        String kind = first(params, "kind");
        in.kind = kind == null ? "entities" : kind;
        if (!"entities".equals(in.kind) && !"reports".equals(in.kind)) {
            throw new AdsQueryError("Ads 数据类型无效。");
        }
        if ("entities".equals(in.kind) && (params.containsKey("from") || params.containsKey("to"))) {
            throw new AdsQueryError("结构数据没有报告日期维度，请切换日报事实。");
        }

        in.page = boundedInt(first(params, "page"), 1, 1, 10000, "页码");
        in.pageSize = boundedInt(first(params, "pageSize"), 50, 1, 100, "每页数量");
        String direction = first(params, "sortDirection");
        if (direction == null) direction = first(params, "direction");
        if (direction == null) direction = "desc";
        if (!"asc".equals(direction) && !"desc".equals(direction)) throw new AdsQueryError("排序方向无效。");
        in.sortDirection = direction;

        String defaultSort = "entities".equals(in.kind) ? "name" : "reportDate";
        String sortBy = first(params, "sortBy");
        if (sortBy == null) sortBy = first(params, "sort");
        if (sortBy == null) sortBy = defaultSort;
        Set<String> allowed = "entities".equals(in.kind) ? ENTITY_SORTS : REPORT_SORTS;
        if (!allowed.contains(sortBy)) throw new AdsQueryError("排序字段不受支持。");
        in.sortBy = sortBy;

        String today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowUtcEpochMillis), ZoneOffset.UTC).toString();
        String to = text(params, "to");
        if (to == null) to = today;
        in.to = parseDate(to, "结束日期");
        String from = text(params, "from");
        if (from == null) from = shiftDate(in.to, -29);
        in.from = parseDate(from, "开始日期");
        if (in.from.compareTo(in.to) > 0) throw new AdsQueryError("报表日期范围倒置。");
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.parse(in.from), LocalDate.parse(in.to));
        if (days > 366) throw new AdsQueryError("报表日期范围最多 367 天。");

        in.profileRecordId = coalesce(text(params, "profileRecordId"),
                text(params, "internalProfileId"), text(params, "profileId"));
        in.sellerAccountId = text(params, "sellerAccountId");
        in.authorizationId = text(params, "authorizationId");
        in.marketplaceId = text(params, "marketplaceId");
        in.entityType = text(params, "entityType");
        in.reportType = text(params, "reportType");
        in.adProduct = text(params, "adProduct");
        in.campaignId = text(params, "campaignId");
        in.adGroupId = text(params, "adGroupId");
        in.targetingId = text(params, "targetingId");
        in.advertisedAsin = text(params, "advertisedAsin");
        in.searchTerm = text(params, "searchTerm");
        in.placement = text(params, "placement");
        in.externalId = text(params, "externalId");
        in.state = text(params, "state");
        in.search = text(params, "search");
        return in;
    }

    /** Reject conflicting aliases or repeated values before any scope/SQL is constructed. */
    public static void assertUnambiguous(Map<String, List<String>> params) {
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            Set<String> distinct = new TreeSet<>();
            for (String value : entry.getValue()) distinct.add(trim(value));
            if (distinct.size() > 1) throw new AdsQueryError("查询参数重复且冲突。");
        }
        for (List<String> aliases : ALIASES) {
            Set<String> values = new LinkedHashSet<>();
            for (String key : aliases) {
                String value = first(params, key);
                if (value != null && !value.isEmpty()) values.add(value);
            }
            if (values.size() > 1) throw new AdsQueryError("查询参数别名冲突。");
        }
    }

    private static String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null || values.isEmpty() ? null : trim(values.get(0));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String coalesce(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    private static int boundedInt(String value, int fallback, int min, int max, String label) {
        if (value == null || value.isEmpty()) return fallback;
        if (!value.matches("\\d+")) throw new AdsQueryError(label + "无效。");
        long number;
        try {
            number = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new AdsQueryError(label + "超出允许范围。");
        }
        if (number < min || number > max) throw new AdsQueryError(label + "超出允许范围。");
        return (int) number;
    }

    private static String text(Map<String, List<String>> params, String key) {
        String value = first(params, key);
        if (value == null || value.isEmpty()) return null;
        int limit = "search".equals(key) ? 120 : 200;
        if (value.length() > limit) throw new AdsQueryError("筛选条件过长。");
        return value;
    }

    private static String parseDate(String value, String label) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new AdsQueryError(label + "格式无效。");
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) throw new AdsQueryError(label + "格式无效。");
        } catch (RuntimeException e) {
            throw new AdsQueryError(label + "格式无效。");
        }
        return value;
    }

    private static String shiftDate(String day, int delta) {
        return LocalDate.parse(day).plusDays(delta).toString();
    }

    /** Convenience adapter building a single-valued parameter map. */
    public static Map<String, List<String>> params(String... kv) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            result.computeIfAbsent(kv[i], k -> new ArrayList<>()).add(kv[i + 1]);
        }
        return Collections.unmodifiableMap(result);
    }
}

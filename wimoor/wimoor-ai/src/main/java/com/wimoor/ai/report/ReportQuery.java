package com.wimoor.ai.report;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code amazon-ads/report-query.ts}.
 *
 * <p>Single read boundary for API and MCP over CURRENT published facts (not full-report
 * replacement semantics). Revalidates every input, applies scoped filters and a
 * whitelisted sort, then builds pagination, per-currency totals with completeness,
 * per-profile freshness and derived metrics. A missing metric makes its total and
 * dependent derived metrics null — a null is never presented as a real zero.
 */
public final class ReportQuery {

    private ReportQuery() {}

    private static final List<String> SORTS = java.util.Arrays.asList(
            "reportDate", "campaignId", "adGroupId", "impressions", "clicks",
            "spend", "orders", "sales", "searchTerm");

    /** In-memory published fact row. */
    public static final class Fact {
        public String profileId;
        public String reportType;
        public String adProduct;
        public String reportDate;
        public String factKey;
        public String versionId;
        public String campaignId;
        public String adGroupId;
        public String targetingId;
        public String advertisedAsin;
        public String searchTerm;
        public String placement;
        public String currencyCode;
        public String attributionWindow;
        public String apiVersion;
        public String impressions;
        public String clicks;
        public String spend;
        public String orders;
        public String sales;
        public String units;
    }

    public static final class PageInfo {
        public int page;
        public int pageSize;
        public int total;
        public boolean hasMore;
        public boolean hasNext;
        public int pageCount;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("page", page);
            map.put("pageSize", pageSize);
            map.put("total", total);
            map.put("hasMore", hasMore);
            map.put("hasNext", hasNext);
            map.put("pageCount", pageCount);
            return map;
        }
    }

    public static PageInfo pageInfo(int page, int pageSize, int total) {
        PageInfo info = new PageInfo();
        info.page = page;
        info.pageSize = pageSize;
        info.total = total;
        info.hasMore = (long) page * pageSize < total;
        info.hasNext = info.hasMore;
        info.pageCount = pageSize == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return info;
    }

    /** Applies the whitelisted filters to an in-memory fact set. */
    public static List<Fact> filter(List<Fact> facts, AdsQueryContract.Input input) {
        List<Fact> result = new ArrayList<>();
        for (Fact fact : facts) {
            if (fact.reportDate.compareTo(input.from) < 0 || fact.reportDate.compareTo(input.to) > 0) continue;
            if (input.reportType != null && !input.reportType.equals(fact.reportType)) continue;
            if (input.adProduct != null && !input.adProduct.equals(fact.adProduct)) continue;
            if (input.campaignId != null && !input.campaignId.equals(fact.campaignId)) continue;
            if (input.adGroupId != null && !input.adGroupId.equals(fact.adGroupId)) continue;
            if (input.targetingId != null && !input.targetingId.equals(fact.targetingId)) continue;
            if (input.advertisedAsin != null && !input.advertisedAsin.equals(fact.advertisedAsin)) continue;
            if (input.searchTerm != null && !input.searchTerm.equals(fact.searchTerm)) continue;
            if (input.placement != null && !input.placement.equals(fact.placement)) continue;
            if (input.profileRecordId != null && !input.profileRecordId.equals(fact.profileId)) continue;
            if (input.search != null && !containsAny(input.search, fact)) continue;
            result.add(fact);
        }
        return result;
    }

    public static List<Fact> sort(List<Fact> facts, String sortBy, String direction) {
        if (!SORTS.contains(sortBy)) throw new AdsQueryError("报表排序无效。");
        Comparator<Fact> comparator = comparator(sortBy);
        if ("desc".equalsIgnoreCase(direction)) comparator = comparator.reversed();
        List<Fact> result = new ArrayList<>(facts);
        result.sort(comparator.thenComparing(f -> f.profileId == null ? "" : f.profileId)
                .thenComparing(f -> f.factKey == null ? "" : f.factKey));
        return result;
    }

    public static List<Fact> page(List<Fact> facts, int pageNo, int pageSize) {
        long from = ((long) pageNo - 1) * pageSize;
        if (from >= facts.size()) return new ArrayList<>();
        long to = Math.min(facts.size(), from + pageSize);
        return new ArrayList<>(facts.subList((int) from, (int) to));
    }

    /** Per-group totals; a metric is summed only when every row provides it. */
    public static final class Totals {
        public String currencyCode;
        public String attributionWindow;
        public String apiVersion;
        public String reportType;
        public String adProduct;
        public final Map<String, String> sums = new LinkedHashMap<>();
        public final Map<String, BigInteger> provided = new LinkedHashMap<>();
        public BigInteger observedFactCount = BigInteger.ZERO;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("currencyCode", currencyCode);
            map.put("attributionWindow", attributionWindow);
            map.put("apiVersion", apiVersion);
            map.put("reportType", reportType);
            map.put("adProduct", adProduct);
            for (Map.Entry<String, String> entry : sums.entrySet()) map.put(entry.getKey(), entry.getValue());
            AdsMetrics.Input metrics = new AdsMetrics.Input();
            metrics.impressions = sums.get("impressions");
            metrics.clicks = sums.get("clicks");
            metrics.spend = sums.get("spend");
            metrics.orders = sums.get("orders");
            metrics.sales = sums.get("sales");
            for (Map.Entry<String, String> entry : AdsMetrics.derive(metrics).toMap().entrySet()) {
                map.put(entry.getKey(), entry.getValue());
            }
            map.put("completeness", ReportCompleteness.evaluate(observedFactCount, provided));
            return map;
        }
    }

    public static List<Totals> totals(List<Fact> facts) {
        Map<String, List<Fact>> groups = new LinkedHashMap<>();
        for (Fact fact : facts) {
            String key = String.join("|",
                    value(fact.currencyCode), value(fact.attributionWindow),
                    value(fact.apiVersion), value(fact.reportType), value(fact.adProduct));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(fact);
        }
        List<Totals> result = new ArrayList<>();
        for (Map.Entry<String, List<Fact>> entry : groups.entrySet()) {
            List<Fact> rows = entry.getValue();
            Totals totals = new Totals();
            Fact first = rows.get(0);
            totals.currencyCode = first.currencyCode;
            totals.attributionWindow = first.attributionWindow;
            totals.apiVersion = first.apiVersion;
            totals.reportType = first.reportType;
            totals.adProduct = first.adProduct;
            totals.observedFactCount = BigInteger.valueOf(rows.size());
            for (String metric : new String[]{"impressions", "clicks", "orders", "units"}) {
                int count = 0;
                BigInteger sum = BigInteger.ZERO;
                for (Fact row : rows) {
                    String value = metric(row, metric);
                    if (value != null) { count++; sum = sum.add(new BigInteger(value)); }
                }
                totals.provided.put(metric, BigInteger.valueOf(count));
                totals.sums.put(metric, count == rows.size() ? sum.toString() : null);
            }
            for (String metric : new String[]{"spend", "sales"}) {
                int count = 0;
                BigDecimal sum = BigDecimal.ZERO;
                for (Fact row : rows) {
                    String value = metric(row, metric);
                    if (value != null) { count++; sum = sum.add(new BigDecimal(value)); }
                }
                totals.provided.put(metric, BigInteger.valueOf(count));
                totals.sums.put(metric, count == rows.size() ? sum.toPlainString() : null);
            }
            result.add(totals);
        }
        return result;
    }

    /** Per-profile freshness envelope over the selected facts. */
    public static List<Map<String, Object>> freshProfiles(List<String> profileIds,
                                                          List<ReportFreshness.DomainState> states,
                                                          List<Fact> facts, String reportType) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String profileId : profileIds) {
            boolean hasFacts = facts.stream().anyMatch(f -> profileId.equals(f.profileId));
            ReportFreshness.DomainState state = ReportFreshness.select(states, profileId, reportType);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("profileRecordId", profileId);
            row.put("status", ReportFreshness.publishedReportFreshness(hasFacts,
                    state == null ? null : state.status).name());
            row.put("reportType", reportType);
            row.put("dataDomain", "reports");
            result.add(row);
        }
        return result;
    }

    private static Comparator<Fact> comparator(String sortBy) {
        return (a, b) -> {
            String left = metric(a, sortBy);
            String right = metric(b, sortBy);
            if (left == null && right == null) return 0;
            if (left == null) return 1; // NULLS LAST
            if (right == null) return -1;
            if (("impressions".equals(sortBy) || "clicks".equals(sortBy) || "orders".equals(sortBy))) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if ("spend".equals(sortBy) || "sales".equals(sortBy)) {
                return new BigDecimal(left).compareTo(new BigDecimal(right));
            }
            return left.compareTo(right);
        };
    }

    private static String metric(Fact fact, String key) {
        switch (key) {
            case "campaignId": return fact.campaignId;
            case "adGroupId": return fact.adGroupId;
            case "searchTerm": return fact.searchTerm;
            case "reportDate": return fact.reportDate;
            case "impressions": return fact.impressions;
            case "clicks": return fact.clicks;
            case "orders": return fact.orders;
            case "units": return fact.units;
            case "spend": return fact.spend;
            case "sales": return fact.sales;
            default: return null;
        }
    }

    private static boolean containsAny(String search, Fact fact) {
        return contains(fact.searchTerm, search) || contains(fact.advertisedAsin, search)
                || contains(fact.campaignId, search) || contains(fact.adGroupId, search);
    }

    private static boolean contains(String value, String search) {
        return value != null && value.contains(search);
    }

    private static String value(String value) { return value == null ? "" : value; }
}

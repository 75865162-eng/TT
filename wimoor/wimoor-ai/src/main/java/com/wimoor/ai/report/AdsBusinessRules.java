package com.wimoor.ai.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from {@code business-fields.ts}, {@code automatic-binding.ts},
 * {@code change-contract.ts} and {@code capabilities.ts}.
 *
 * <p>Business-field normalisation keeps exact decimal strings from the ERP record over
 * raw payload values; automatic binding only joins a verified SP seller/site identity
 * (display names, countries and user-entered advertiser IDs are never identity); change
 * patches are validated against a per-entity whitelist and transitions require two
 * different people for apply vs approve. The capability catalogue is code metadata only
 * and never grants access or enables a sync task.
 */
public final class AdsBusinessRules {

    private AdsBusinessRules() {}

    /** Normalised entity record subset used by businessFields. */
    public static final class Entity {
        public Map<String, Object> payload;
        public String name;
        public String campaignId;
        public String adGroupId;
        public String keywordText;
        public String matchType;
        public String targetingType;
        public String expressionType;
        public BigDecimal bid;
        public String entityType;
        public LocalDate startDate;
        public LocalDate endDate;
        public BigDecimal budgetAmount;
        public String budgetType;
        public Object targetingExpression;
    }

    public static Map<String, Object> businessFields(Entity entity) {
        Map<String, Object> fields = new LinkedHashMap<>(AdsCanonical.businessData(entity.payload));
        putIfPresent(fields, "name", entity.name);
        putIfPresent(fields, "campaignId", entity.campaignId);
        putIfPresent(fields, "adGroupId", entity.adGroupId);
        putIfPresent(fields, "keywordText", entity.keywordText);
        putIfPresent(fields, "matchType", entity.matchType);
        putIfPresent(fields, "targetingType", entity.targetingType);
        putIfPresent(fields, "expressionType", entity.expressionType);
        if (entity.bid != null) {
            fields.put("bid", entity.bid.toPlainString());
            if ("SP:ad-group".equals(entity.entityType)) fields.put("defaultBid", entity.bid.toPlainString());
        }
        if (entity.startDate != null) fields.put("startDate", entity.startDate.toString());
        if (entity.endDate != null) fields.put("endDate", entity.endDate.toString());
        if (entity.budgetAmount != null || entity.budgetType != null) {
            Object original = fields.get("budget");
            Map<String, Object> budget = original instanceof Map
                    ? new LinkedHashMap<>((Map<String, Object>) original) : new LinkedHashMap<>();
            if (entity.budgetAmount != null) budget.put("budget", entity.budgetAmount.toPlainString());
            if (entity.budgetType != null) budget.put("budgetType", entity.budgetType);
            fields.put("budget", budget);
        }
        if (entity.targetingExpression != null) {
            fields.put("targetingExpression", AdsCanonical.businessData(entity.targetingExpression));
        }
        return fields;
    }

    /** Region -> AWS region mapping used by verified seller identity lookup. */
    public static String awsRegion(String adsRegion) {
        return "NA".equals(adsRegion) ? "us-east-1" : "EU".equals(adsRegion) ? "eu-west-1" : "us-west-2";
    }

    /** Stable synthetic seller account key for an unbound Ads profile. */
    public static String syntheticAccountKey(String authorizationId, String region, String profileId) {
        return "amazon-ads:" + AdsCanonical.digest(Arrays.asList(authorizationId, region, profileId));
    }

    private static final Map<String, List<String>> SUPPORTED_PATCH_FIELDS = new LinkedHashMap<>();
    static {
        SUPPORTED_PATCH_FIELDS.put("SP:campaign", Arrays.asList("state", "name", "dailyBudget", "placements"));
        SUPPORTED_PATCH_FIELDS.put("SP:ad-group", Arrays.asList("state", "name", "bid"));
        SUPPORTED_PATCH_FIELDS.put("SP:keyword", Arrays.asList("state", "bid"));
        SUPPORTED_PATCH_FIELDS.put("SP:target", Arrays.asList("state", "bid"));
        for (String type : Arrays.asList("SP:product-ad", "SP:negative-keyword", "SP:negative-target",
                "SP:campaign-negative-keyword", "SP:campaign-negative-target")) {
            SUPPORTED_PATCH_FIELDS.put(type, Collections.singletonList("state"));
        }
    }

    public static List<String> patchFields(String entityType) {
        return SUPPORTED_PATCH_FIELDS.getOrDefault(entityType, Collections.emptyList());
    }

    /** Validates a business patch draft; never issues an Amazon HTTP request. */
    public static Map<String, Object> parsePatch(String entityType, Object value) {
        List<String> keys = SUPPORTED_PATCH_FIELDS.get(entityType);
        if (keys == null || !(value instanceof Map) || ((Map<?, ?>) value).isEmpty()) {
            throw new AdsQueryError("此实体或变更字段尚未核实。", 422);
        }
        Map<?, ?> raw = (Map<?, ?>) value;
        for (Object key : raw.keySet()) if (!keys.contains(key)) throw new AdsQueryError("此实体或变更字段尚未核实。", 422);
        Map<String, Object> patch = new LinkedHashMap<>((Map<String, Object>) raw);
        Object state = patch.get("state");
        if (state != null && !Arrays.asList("ENABLED", "PAUSED", "ARCHIVED").contains(state)) {
            throw new AdsQueryError("广告状态无效。", 422);
        }
        Object name = patch.get("name");
        if (name != null && (!(name instanceof String) || ((String) name).trim().isEmpty()
                || ((String) name).length() > 200)) throw new AdsQueryError("广告名称无效。", 422);
        if (name != null) patch.put("name", ((String) name).trim());
        for (String key : Arrays.asList("bid", "dailyBudget")) {
            Object amount = patch.get(key);
            if (amount != null && (!(amount instanceof String)
                    || !((String) amount).matches("\\d{1,8}(\\.\\d{1,6})?")
                    || !((String) amount).matches("[\\s\\S]*[1-9][\\s\\S]*"))) {
                throw new AdsQueryError("竞价/预算须为正数十进制文本。", 422);
            }
        }
        Object placements = patch.get("placements");
        if (placements != null) {
            if (!(placements instanceof List) || ((List<?>) placements).isEmpty()
                    || ((List<?>) placements).size() > 3) throw new AdsQueryError("展示位置调整无效。", 422);
            Set<Object> unique = new HashSet<>();
            for (Object item : (List<?>) placements) {
                if (!(item instanceof Map)) throw new AdsQueryError("展示位置调整无效。", 422);
                Map<?, ?> row = (Map<?, ?>) item;
                for (Object key : row.keySet()) {
                    if (!"placement".equals(key) && !"percentage".equals(key)) throw new AdsQueryError("展示位置调整无效。", 422);
                }
                Object placement = row.get("placement");
                Object percentage = row.get("percentage");
                if (!Arrays.asList("PLACEMENT_TOP", "PLACEMENT_PRODUCT_PAGE", "PLACEMENT_REST_OF_SEARCH").contains(placement)
                        || !(percentage instanceof Integer) || (Integer) percentage < 0 || (Integer) percentage > 900
                        || !unique.add(placement)) throw new AdsQueryError("展示位置调整无效。", 422);
            }
        }
        return patch;
    }

    public static String fingerprint(Object value) { return AdsCanonical.digest(value); }

    /** Draft state machine: apply/approve/reject/cancel with two-person separation. */
    public static String assertDraftTransition(String status, String action, String actor, String creator) {
        String next = null;
        if ("submit".equals(action) && "DRAFT".equals(status)) next = "SUBMITTED";
        else if ("approve".equals(action) && "SUBMITTED".equals(status)) next = "APPROVED";
        else if ("reject".equals(action) && "SUBMITTED".equals(status)) next = "REJECTED";
        else if ("cancel".equals(action) && Arrays.asList("DRAFT", "SUBMITTED", "APPROVED").contains(status)) next = "CANCELLED";
        if (next == null) throw new AdsQueryError("广告草稿状态不允许此操作。", 409);
        boolean approverAction = "approve".equals(action) || "reject".equals(action);
        if (approverAction ? actor.equals(creator) : !actor.equals(creator)) {
            throw new AdsQueryError("广告草稿申请与审核必须由不同人员完成，提交/取消限原申请人。", 403);
        }
        return next;
    }

    /** Capability catalogue entry. Code only; never grants access. */
    public static final class Capability {
        public String product;
        public String domain;
        public String priority;
        public String read;
        public String acquisition;
        public String contract;
        public List<String> blockers;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("product", product);
            map.put("domain", domain);
            map.put("priority", priority);
            map.put("read", read);
            map.put("acquisition", acquisition);
            map.put("contract", contract);
            map.put("blockers", blockers);
            return map;
        }
    }

    private static final List<String> DOMAINS = Arrays.asList(
            "profiles", "campaigns", "ad-groups", "product-ads", "keywords", "targets",
            "negative-keywords", "negative-targets", "campaign-negative-keywords",
            "campaign-negative-targets", "portfolios", "budget-bidding",
            "campaign-performance", "advertised-product-performance", "search-term-performance",
            "placement-performance", "targeting-performance", "creative-brand");
    private static final Set<String> LATER = new HashSet<>(Arrays.asList("portfolios", "budget-bidding", "creative-brand"));
    private static final Set<String> REPORTS = new HashSet<>(Arrays.asList(
            "campaign-performance", "advertised-product-performance", "search-term-performance",
            "placement-performance", "targeting-performance"));

    public static List<Capability> listCapabilities(String product) {
        List<String> products = product == null ? Arrays.asList("SP", "SB", "SD")
                : Collections.singletonList(product);
        List<Capability> result = new ArrayList<>();
        for (String p : products) {
            for (String domain : DOMAINS) {
                Capability c = new Capability();
                c.product = p;
                c.domain = domain;
                c.priority = LATER.contains(domain) ? "P1" : "P0";
                c.read = "NOT_IMPLEMENTED";
                c.acquisition = "NONE";
                c.contract = "UNVERIFIED";
                if ("profiles".equals(domain)) {
                    c.read = "AUTHORIZATION_FLOW";
                    c.contract = "PROFILE_CONTRACT";
                } else if ("SP".equals(p) && REPORTS.contains(domain)) {
                    c.read = "REPORT_WORKER";
                    c.contract = "DOCUMENTED_REPORT_TYPE";
                    c.acquisition = "FORMAL_LIVE_ADAPTER";
                } else if ("SP".equals(p) && !LATER.contains(domain) && !"profiles".equals(domain)) {
                    c.read = "STRUCTURE_WORKER";
                    c.contract = "DOCUMENTED_STRUCTURE";
                }
                c.blockers = blockers(c);
                result.add(c);
            }
        }
        return result;
    }

    public static List<String> blockers(Capability row) {
        List<String> blockers = new ArrayList<>();
        if ("UNVERIFIED".equals(row.contract)) blockers.add("DOMAIN_CONTRACT_UNVERIFIED");
        if ("NOT_IMPLEMENTED".equals(row.read)) blockers.add("READ_ADAPTER_NOT_IMPLEMENTED");
        if ("ISOLATED_FIXTURE_ONLY".equals(row.acquisition)) blockers.add("ISOLATED_FIXTURE_ONLY");
        blockers.add("LIVE_ACCEPTANCE_NOT_VERIFIED");
        blockers.add("PROFILE_PERMISSION_REQUIRES_CHECK");
        blockers.add("WRITE_NOT_IMPLEMENTED");
        return blockers;
    }

    @SuppressWarnings("unchecked")
    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }
}

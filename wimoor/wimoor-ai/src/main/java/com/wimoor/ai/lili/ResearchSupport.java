package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 支撑类：移植 selection-research 中 public-research-tools.ts / research-tools.ts 引用的纯计算函数
 * （brief 解析、站点解析、五阶段 Skill 模板、预算与积分预览、费用审批判定）。无外部服务依赖。
 */
public final class ResearchSupport {

    private ResearchSupport() {}

    private static final Map<String, String> MARKETPLACES = Map.of(
        "US", "美国", "UK", "英国", "DE", "德国", "FR", "法国", "JP", "日本", "CA", "加拿大");
    private static final Pattern ASIN = Pattern.compile("\\b[A-Z0-9]{10}\\b");
    private static final List<String> STAGE_SKILLS = List.of(
        "research-brief", "research-market", "research-competition", "research-economics", "research-report");
    private static final List<String> STAGE_TOOLS = List.of(
        "research_prepare", "meta_load_skill", "meta_load_skill", "meta_load_skill", "research_export");
    private static final int[] STAGE_MIN_CALLS = {0, 2, 2, 1, 0};
    private static final int[] STAGE_MAX_CALLS = {0, 6, 8, 4, 0};

    /** resolveResearchMarketplace：显式站点与原文冲突时标记 conflict，不静默覆盖。 */
    public static Map<String, Object> resolveResearchMarketplace(String requirement, String explicit) {
        List<String> found = new ArrayList<>();
        String upper = requirement == null ? "" : requirement.toUpperCase(Locale.ROOT);
        for (String code : MARKETPLACES.keySet()) if (upper.contains(code) || requirement != null && requirement.contains(MARKETPLACES.get(code))) found.add(code);
        String value = explicit;
        boolean conflict = false;
        if (explicit != null && !explicit.isEmpty()) {
            if (!MARKETPLACES.containsKey(explicit.toUpperCase(Locale.ROOT))) { value = null; conflict = true; }
            else {
                value = explicit.toUpperCase(Locale.ROOT);
                conflict = !found.isEmpty() && !found.contains(value);
            }
        } else if (found.size() == 1) value = found.get(0);
        else if (found.size() > 1) conflict = true;
        return Ports.jsonObject("value", value, "candidates", found, "conflict", conflict);
    }

    /** parseResearchBrief：阶段 0 只解析显式约束，不访问市场/模型/持久化。 */
    public static Map<String, Object> parseResearchBrief(String requirement, String marketplace) {
        List<String> unresolved = new ArrayList<>();
        String asin = null;
        Matcher m = ASIN.matcher(requirement == null ? "" : requirement);
        if (m.find()) asin = m.group();
        if (marketplace == null || marketplace.isEmpty()) unresolved.add("marketplace");
        List<String> keywords = new ArrayList<>();
        if (requirement != null) {
            for (String token : requirement.split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 2 && token.length() <= 24) keywords.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return Ports.jsonObject("requirement", requirement, "asin", asin, "keywords", keywords,
            "constraints", Ports.jsonObject("maxMarketCalls", null, "budget", null), "unresolved", unresolved);
    }

    /** loadBundledResearchSkills：五阶段模板，sha256 由模板正文计算。 */
    public static List<Map<String, Object>> loadBundledResearchSkills() {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (int i = 0; i < STAGE_SKILLS.size(); i++) {
            String name = STAGE_SKILLS.get(i);
            String body = "# " + name + "\n阶段 " + (i + 1) + "：仅使用已注册的真实工具；未知费用保持未知。\n";
            skills.add(Ports.jsonObject("name", name, "path", "skills/" + name + "/SKILL.md",
                "sha256", CryptoSupport.sha256Hex(body), "tools", List.of(STAGE_TOOLS.get(i))));
        }
        return skills;
    }

    /** researchBudgetPreview：规划调用区间与积分（1 credit = CNY 0.01）。 */
    public static Map<String, Object> researchBudgetPreview(Map<String, Object> context) {
        Object skillsValue = context == null ? null : context.get("skills");
        List<?> skills = skillsValue instanceof List ? (List<?>) skillsValue : loadBundledResearchSkills();
        String mode = context == null ? null : JsonSupport.str(context.get("budgetMode"));
        if (mode == null) mode = "uncapped";
        List<Map<String, Object>> stages = new ArrayList<>();
        int min = 0, max = 0;
        for (int i = 0; i < STAGE_MIN_CALLS.length; i++) {
            int lo = STAGE_MIN_CALLS[i], hi = STAGE_MAX_CALLS[i];
            min += lo; max += hi;
            stages.add(Ports.jsonObject("stage", i + 1, "minCalls", lo, "maxCalls", hi,
                "skill", i < skills.size() ? JsonSupport.str(((Map<?, ?>) skills.get(i)).get("name")) : null));
        }
        return Ports.jsonObject("budgetMode", mode, "stages", stages,
            "minCredits", min, "maxCredits", max, "creditsPerCall", 1,
            "cnyPerCredit", "0.01", "unverifiedPricesPending", true, "skillCount", skills.size());
    }

    /** researchUsage：已执行调用数。 */
    public static Map<String, Object> researchUsage(Map<String, Object> run) {
        int calls = 0;
        if (run != null) {
            List<?> results = JsonSupport.arr(run.get("results"));
            if (results != null) calls = results.size();
            Number n = JsonSupport.num(run.get("marketCallsUsed"));
            if (n != null) calls = n.intValue();
        }
        return Ports.jsonObject("calls", calls, "capped", run != null && !"uncapped".equals(run.get("budgetMode")));
    }

    /** researchCreditUsage：1 credit = CNY 0.01。 */
    public static Map<String, Object> researchCreditUsage(Map<String, Object> run) {
        Map<String, Object> usage = researchUsage(run);
        int credits = (Integer) usage.get("calls");
        return Ports.jsonObject("credits", credits, "cny", CallQuotaServer.creditsToCny(credits).toPlainString());
    }

    /** researchFeeReviewRequired：显式费用审批策略或未知费用均不阻断研究。 */
    public static boolean researchFeeReviewRequired(Map<String, Object> run) {
        if (run == null) return false;
        return Boolean.TRUE.equals(run.get("feeApprovalExplicitlyRequested")) && Boolean.TRUE.equals(run.get("paidCallAuthorization"));
    }

    /** normalizeResearchAction：仅规范化 type/tool/arguments/result，不执行任何工具。 */
    public static Map<String, Object> normalizeResearchAction(Object raw) {
        Map<String, Object> src = JsonSupport.obj(raw);
        Map<String, Object> out = new LinkedHashMap<>();
        if (src == null) return out;
        out.put("type", JsonSupport.str(src.get("type")));
        if (src.containsKey("tool")) out.put("tool", src.get("tool"));
        if (src.containsKey("arguments")) out.put("arguments", src.get("arguments"));
        if (src.containsKey("result")) out.put("result", src.get("result"));
        return out;
    }
}

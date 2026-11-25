package com.wimoor.ai.research;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保守的研究需求解析（移植自 src/lib/selection-research/brief.ts）。
 * 只有唯一且显式标注的约束才被提取；歧义或缺失保留 unknown，原文 originalRequirement 始终权威。
 */
public final class BriefParser {

    public static final class BriefField {
        public final String value;
        public final String sourceText;

        @JsonCreator
        public BriefField(@JsonProperty("value") String value, @JsonProperty("sourceText") String sourceText) {
            this.value = value;
            this.sourceText = sourceText;
        }
    }

    public static final class QuantityField {
        public final long value;
        public final String sourceText;

        @JsonCreator
        public QuantityField(@JsonProperty("value") long value, @JsonProperty("sourceText") String sourceText) {
            this.value = value;
            this.sourceText = sourceText;
        }
    }

    public static final class ResearchBrief {
        public final String originalRequirement;
        public final String marketplace;
        public final BriefField productDirection;
        public final BriefField targetAudience;
        public final QuantityField procurementQuantity;
        public final BriefField targetPrice;
        public final BriefField costConstraint;
        public final BriefField depth;
        public final List<String> unresolved;

        @JsonCreator
        public ResearchBrief(@JsonProperty("originalRequirement") String originalRequirement,
                             @JsonProperty("marketplace") String marketplace,
                             @JsonProperty("productDirection") BriefField productDirection,
                             @JsonProperty("targetAudience") BriefField targetAudience,
                             @JsonProperty("procurementQuantity") QuantityField procurementQuantity,
                             @JsonProperty("targetPrice") BriefField targetPrice,
                             @JsonProperty("costConstraint") BriefField costConstraint,
                             @JsonProperty("depth") BriefField depth,
                             @JsonProperty("unresolved") List<String> unresolved) {
            this.originalRequirement = originalRequirement;
            this.marketplace = marketplace;
            this.productDirection = productDirection;
            this.targetAudience = targetAudience;
            this.procurementQuantity = procurementQuantity;
            this.targetPrice = targetPrice;
            this.costConstraint = costConstraint;
            this.depth = depth;
            this.unresolved = unresolved;
        }
    }

    private static final Pattern LABELED_DIRECTION = Pattern.compile(
            "(?:产品方向|产品大方向|product direction|starting direction|我想做的方向是)\\s*[:：]\\s*(?:【([^】]+)】|([^，,；;。\\n.]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL_NAME = Pattern.compile("amazon-[a-z-]+|tariff-calculator-amazon", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESEARCH_PREFIX = Pattern.compile("^(?:research)\\s+([^，,；;。\\n.]+)[.]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESEARCH_ZH = Pattern.compile("(?:研究)\\s+([^，,；;。\\n]+)");
    private static final Pattern AUDIENCE = Pattern.compile("(?:目标用户|目标人群|target audience)\\s*[:：]\\s*([^，,；;。\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY = Pattern.compile("(?:采购数量|采购|首批|purchase quantity)\\s*[:：]?\\s*([0-9]+)\\s*(?:件|个|套|units)(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE = Pattern.compile("(?:目标售价|目标价格|target price)\\s*[:：]?\\s*([^，,；;。\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST = Pattern.compile("(?:成本约束|成本上限|target cost|cost limit)\\s*[:：]?\\s*([^，,；;。\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPTH = Pattern.compile("(?:研究深度|research depth)\\s*[:：]\\s*([^，,；;。\\n]+)", Pattern.CASE_INSENSITIVE);

    private BriefParser() {}

    public static ResearchBrief parseResearchBrief(String requirement, String marketplace) {
        BriefField productDirection = parseProductDirection(requirement);
        BriefField targetAudience = field(requirement, AUDIENCE);
        BriefField quantity = field(requirement, QUANTITY);
        QuantityField procurementQuantity = null;
        if (quantity != null) {
            try {
                long n = Long.parseLong(quantity.value);
                if (n > 0 && Math.abs(n) <= 9007199254740991L) procurementQuantity = new QuantityField(n, quantity.sourceText);
            } catch (NumberFormatException ignored) {
                // 超大整数不是安全整数，保留 unknown
            }
        }
        BriefField targetPrice = field(requirement, PRICE);
        BriefField costConstraint = field(requirement, COST);
        BriefField depth = field(requirement, DEPTH);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("productDirection", productDirection);
        values.put("targetAudience", targetAudience);
        values.put("procurementQuantity", procurementQuantity);
        values.put("targetPrice", targetPrice);
        values.put("costConstraint", costConstraint);
        values.put("depth", depth);
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) if (e.getValue() == null) unresolved.add(e.getKey());
        return new ResearchBrief(requirement, marketplace, productDirection, targetAudience,
                procurementQuantity, targetPrice, costConstraint, depth, unresolved);
    }

    private static BriefField parseProductDirection(String requirement) {
        List<String[]> labeled = matches(requirement, LABELED_DIRECTION);
        if (!labeled.isEmpty()) {
            Set<String> directions = new LinkedHashSet<>();
            for (String[] m : labeled) directions.add((m[1] != null ? m[1] : m[2]).trim());
            return directions.size() == 1 ? new BriefField(directions.iterator().next(), labeled.get(0)[0]) : null;
        }
        if (SKILL_NAME.matcher(requirement).find()) return null;
        BriefField f = field(requirement, RESEARCH_PREFIX);
        return f != null ? f : field(requirement, RESEARCH_ZH);
    }

    /** 同一字段出现多个不同取值时返回 null，不擅自做选择。 */
    private static BriefField field(String text, Pattern pattern) {
        List<String[]> matches = matches(text, pattern);
        Set<String> values = new LinkedHashSet<>();
        for (String[] m : matches) values.add(m[1].trim());
        return values.size() == 1 ? new BriefField(values.iterator().next(), matches.get(0)[0]) : null;
    }

    /** 返回 [整个匹配, 捕获组1] 的列表（对应 JS matchAll）。 */
    private static List<String[]> matches(String text, Pattern pattern) {
        List<String[]> out = new ArrayList<>();
        Matcher m = pattern.matcher(text == null ? "" : text);
        while (m.find()) {
            String[] groups = new String[m.groupCount() + 1];
            groups[0] = m.group();
            for (int i = 1; i <= m.groupCount(); i++) groups[i] = m.group(i);
            out.add(groups);
        }
        return out;
    }
}

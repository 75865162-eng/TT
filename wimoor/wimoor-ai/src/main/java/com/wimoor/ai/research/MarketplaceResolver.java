package com.wimoor.ai.research;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站点解析（移植自 src/lib/selection-research/marketplace.ts）。
 * 只有需求中的显式站点语言或显式参数算目标站点；币种、卖家所在地、竞品链接都不推断站点。
 */
public final class MarketplaceResolver {

    public static final class Resolution {
        public final String value;
        public final boolean conflict;
        public final String source; // argument | requirement | null
        public final List<String> sourceTexts;

        public Resolution(String value, boolean conflict, String source, List<String> sourceTexts) {
            this.value = value;
            this.conflict = conflict;
            this.source = source;
            this.sourceTexts = sourceTexts;
        }
    }

    private static final List<String[]> SITES = List.of(
            new String[]{"US", "美国"}, new String[]{"CA", "加拿大"}, new String[]{"UK", "英国"},
            new String[]{"DE", "德国"}, new String[]{"FR", "法国"}, new String[]{"IT", "意大利"},
            new String[]{"ES", "西班牙"}, new String[]{"JP", "日本"}, new String[]{"AU", "澳大利亚"},
            new String[]{"MX", "墨西哥"});
    private static final Pattern LABELED = Pattern.compile(
            "(?:目标站点|目标市场|站点|marketplace|target market)\\s*[:：]\\s*([^，,；;。\\n]+)",
            Pattern.CASE_INSENSITIVE);

    private MarketplaceResolver() {}

    private static String normalize(String value) {
        String trimmed = value == null ? "" : value.strip();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (String[] site : SITES) {
            if (site[0].equals(upper) || site[1].equals(upper) || (site[1] + "站").equals(upper)) return site[0];
        }
        return upper;
    }

    /** 多处不同站点视为冲突，value 返回 null；单一来源时标记 argument 或 requirement。 */
    public static Resolution resolveResearchMarketplace(String requirement, String argument) {
        List<String[]> mentions = new ArrayList<>();
        for (String[] site : SITES) {
            Matcher m = Pattern.compile(site[1] + "站(?:点)?").matcher(requirement == null ? "" : requirement);
            while (m.find()) mentions.add(new String[]{site[0], m.group()});
        }
        Matcher labeled = LABELED.matcher(requirement == null ? "" : requirement);
        while (labeled.find()) {
            String value = normalize(labeled.group(1));
            for (String[] site : SITES) if (site[0].equals(value)) {
                mentions.add(new String[]{value, labeled.group()});
                break;
            }
        }
        Set<String> values = new LinkedHashSet<>();
        for (String[] mention : mentions) values.add(mention[0]);
        boolean explicit = argument != null && !argument.strip().isEmpty();
        String explicitValue = explicit ? normalize(argument) : null;
        if (explicitValue != null) values.add(explicitValue);
        boolean conflict = values.size() > 1;
        String source = conflict || values.isEmpty() ? null : explicit ? "argument" : "requirement";
        LinkedHashSet<String> sourceTexts = new LinkedHashSet<>();
        for (String[] mention : mentions) sourceTexts.add(mention[1]);
        String value = values.size() == 1 ? values.iterator().next() : null;
        return new Resolution(value, conflict, source, new ArrayList<>(sourceTexts));
    }
}

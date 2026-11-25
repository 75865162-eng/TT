package com.wimoor.ai.skill;

import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import com.wimoor.ai.skill.SkillTypes.Option;
import com.wimoor.ai.skill.SkillTypes.SkillInput;
import com.wimoor.ai.skill.SkillTypes.SkillInputKind;
import com.wimoor.ai.skill.SkillTypes.SkillView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 工作台只读配置层。移植自 {@code src/lib/skill-engine/config.ts}。
 * 关键规则：不新建第二套 Skill 注册表——数据源唯一事实来源是 catalog（此处抽象为
 * {@link SkillCatalog} 接口 + 内存实现），Lili Skill 定义没有 inputsSchema/exportFormat 字段，
 * 因此参数表单与导出格式一律「保守派生」，绝不伪造 Skill 声明。
 */
public final class Config {

    private Config() {}

    private static final List<Option> MARKETPLACES = Arrays.asList(
            new Option("US", "美国 US"), new Option("DE", "德国 DE"), new Option("UK", "英国 UK"),
            new Option("JP", "日本 JP"), new Option("FR", "法国 FR"), new Option("CA", "加拿大 CA"));

    private static final List<Option> DATE_RANGES = Arrays.asList(
            new Option("last7days", "最近 7 天"), new Option("last14days", "最近 14 天"),
            new Option("last30days", "最近 30 天"), new Option("last90days", "最近 90 天"));

    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();
    static {
        CATEGORY_LABELS.put("ads", "广告");
        CATEGORY_LABELS.put("research", "市场与选品");
        CATEGORY_LABELS.put("listing", "Listing");
        CATEGORY_LABELS.put("ops", "运营");
        CATEGORY_LABELS.put("report", "报告");
        CATEGORY_LABELS.put("profit", "利润与财务");
        CATEGORY_LABELS.put("inventory", "补货与库存");
        CATEGORY_LABELS.put("promo", "促销");
        CATEGORY_LABELS.put("market", "市场与选品");
        CATEGORY_LABELS.put("keywords", "关键词");
        CATEGORY_LABELS.put("restock", "补货与库存");
        CATEGORY_LABELS.put("data", "数据");
        CATEGORY_LABELS.put("compliance", "合规");
        CATEGORY_LABELS.put("returns", "退货分析");
        CATEGORY_LABELS.put("orders", "订单");
    }

    private static final Set<String> XLSX_CATEGORIES = Set.of(
            "inventory", "profit", "restock", "returns", "orders");
    private static final List<String> XLSX_SLUG_HINTS = Arrays.asList(
            "keyword", "keywords", "traffic", "reverse");

    /** 模拟 skillDataContracts：skill → 需求表集合（真实来源在 ERP 侧，此处内存建模）。 */
    private static final Map<String, List<String>> DATA_CONTRACTS = new LinkedHashMap<>();
    static {
        DATA_CONTRACTS.put("keyword-traffic-reverse", Arrays.asList("keyword_rank", "traffic_source", "keyword_rank"));
        DATA_CONTRACTS.put("inventory-restock-plan", Arrays.asList("inventory_snapshot"));
        DATA_CONTRACTS.put("amazon-niche-finder", Arrays.asList("amazon_catalog", "review_feed"));
    }

    /** 等价 skill-data-contracts 派生：按 skill 去重表清单。 */
    public static Map<String, List<String>> dataTablesBySkill() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : DATA_CONTRACTS.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(new LinkedHashSet<>(e.getValue())));
        }
        return out;
    }

    /** 按分类与 slug 派生导出格式；分类优先，其次 slug 关键字，缺省 docx。 */
    public static ExportFormat exportFormatFor(String category, String slug) {
        if (category != null && XLSX_CATEGORIES.contains(category)) return ExportFormat.XLSX;
        String normalized = slug == null ? "" : slug.toLowerCase();
        for (String hint : XLSX_SLUG_HINTS) {
            if (normalized.contains(hint)) return ExportFormat.XLSX;
        }
        return ExportFormat.DOCX;
    }

    /** 所有 Skill 共用的四个通用参数（站点/时间范围/ASIN 选填/补充说明选填）。 */
    public static List<SkillInput> inputsFor(ExportFormat exportFormat) {
        List<SkillInput> inputs = new ArrayList<>();
        inputs.add(new SkillInput("marketplace", "站点", SkillInputKind.MARKETPLACE, true,
                null, MARKETPLACES, "US"));
        inputs.add(new SkillInput("dateRange", "时间范围", SkillInputKind.DATE_RANGE, true,
                null, DATE_RANGES, "last30days"));
        inputs.add(new SkillInput("asin", "ASIN / SKU", SkillInputKind.ASIN, false,
                "选填，聚焦单个商品", null, null));
        inputs.add(new SkillInput("brief", "补充说明", SkillInputKind.TEXT, false,
                "选填，例如目标 ACOS、重点关注的活动等", null, null));
        // 通用参数目前与导出格式无关，保留入参以便后续按格式扩展（与 config.ts 一致）。
        voidFormat(exportFormat);
        return inputs;
    }

    private static void voidFormat(ExportFormat format) { if (format == null) throw new IllegalArgumentException("FORMAT_NULL"); }

    /** 把 catalog 定义映射为工作台视图；categoryLabel/exportFormat 均为系统派生。 */
    public static List<SkillView> listSkillViews(SkillCatalog catalog) {
        Map<String, List<String>> tables = dataTablesBySkill();
        List<SkillView> views = new ArrayList<>();
        for (SkillDefinition skill : catalog.listSkills()) {
            ExportFormat format = exportFormatFor(skill.getCategory(), skill.getSlug());
            SkillView view = new SkillView();
            view.setSlug(skill.getSlug());
            view.setName(skill.getNameZh());
            view.setSummary(skill.getSummaryZh());
            view.setCategory(skill.getCategory());
            view.setCategoryLabel(CATEGORY_LABELS.getOrDefault(skill.getCategory(), skill.getCategory()));
            view.setSteps(new ArrayList<>(skill.getStepsZh()));
            view.setTools(new ArrayList<>(skill.getTools()));
            view.setPrompt(skill.getPromptZh());
            view.setFeatured(skill.isFeatured());
            view.setNew(skill.isNew());
            view.setInputs(inputsFor(format));
            view.setExportFormat(format);
            view.setExportFormatLabel(format.label());
            view.setDataTables(tables.getOrDefault(skill.getSlug(), new ArrayList<>()));
            views.add(view);
        }
        return views;
    }

    /** 按 slug（含 catalog 别名解析）查找视图。 */
    public static SkillView findSkillView(SkillCatalog catalog, String slug) {
        SkillDefinition resolved = catalog.findSkill(slug);
        if (resolved == null) return null;
        for (SkillView view : listSkillViews(catalog)) {
            if (view.getSlug().equals(resolved.getSlug())) return view;
        }
        return null;
    }

    /** 分类导航：all + 按数量倒序；标签未知时回退原始 id。 */
    public static List<Map<String, Object>> listSkillCategories(SkillCatalog catalog) {
        List<SkillView> views = listSkillViews(catalog);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SkillView v : views) counts.put(v.getCategory(), counts.getOrDefault(v.getCategory(), 0) + 1);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", "all"); all.put("label", "全部"); all.put("count", views.size());
        out.add(all);
        counts.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed())
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", e.getKey());
                    row.put("label", CATEGORY_LABELS.getOrDefault(e.getKey(), e.getKey()));
                    row.put("count", e.getValue());
                    out.add(row);
                });
        return out;
    }

    /** 加载 Skill 完整正文（不可信流程文档），用于运行时 system 上下文。 */
    public static LoadedSkill loadSkillBody(SkillCatalog catalog, String slug) {
        SkillDefinition resolved = catalog.findSkill(slug);
        if (resolved == null) return null;
        SkillView view = findSkillView(catalog, resolved.getSlug());
        if (view == null) return null;
        String body = catalog.skillMarkdown(resolved.getSlug());
        return new LoadedSkill(view, body == null ? "" : body);
    }

    /** 加载结果：view + SKILL.md 正文。 */
    public static final class LoadedSkill {
        private final SkillView view;
        private final String body;
        public LoadedSkill(SkillView view, String body) { this.view = view; this.body = body; }
        public SkillView getView() { return view; }
        public String getBody() { return body; }
    }

    /** Skill 目录唯一事实来源的接口抽象（ERP 侧由 lib/lili/catalog 实现）。 */
    public interface SkillCatalog {
        List<SkillDefinition> listSkills();
        SkillDefinition findSkill(String slug);
        String skillMarkdown(String slug);
        /** 版本即正文 SHA-256，复用 SkillManifest，不复制定义。 */
        SkillManifest.Manifest manifest(String slug);
    }

    /** catalog 中的原始 Skill 定义（含中英字段与正文）。 */
    public static final class SkillDefinition {
        private final String slug;
        private final String nameZh;
        private final String nameEn;
        private final String category;
        private final String summaryZh;
        private final String summaryEn;
        private final List<String> stepsZh;
        private final List<String> tools;
        private final String promptZh;
        private final boolean featured;
        private final boolean isNew;
        private final String markdown;
        private final List<String> requiredTools;

        public SkillDefinition(String slug, String nameZh, String nameEn, String category,
                               String summaryZh, String summaryEn, List<String> stepsZh, List<String> tools,
                               String promptZh, boolean featured, boolean isNew, String markdown,
                               List<String> requiredTools) {
            this.slug = slug; this.nameZh = nameZh; this.nameEn = nameEn; this.category = category;
            this.summaryZh = summaryZh; this.summaryEn = summaryEn; this.stepsZh = stepsZh;
            this.tools = tools; this.promptZh = promptZh; this.featured = featured; this.isNew = isNew;
            this.markdown = markdown; this.requiredTools = requiredTools;
        }
        public String getSlug() { return slug; }
        public String getNameZh() { return nameZh; }
        public String getNameEn() { return nameEn; }
        public String getCategory() { return category; }
        public String getSummaryZh() { return summaryZh; }
        public String getSummaryEn() { return summaryEn; }
        public List<String> getStepsZh() { return stepsZh; }
        public List<String> getTools() { return tools; }
        public String getPromptZh() { return promptZh; }
        public boolean isFeatured() { return featured; }
        public boolean isNew() { return isNew; }
        public String getMarkdown() { return markdown; }
        public List<String> getRequiredTools() { return requiredTools; }
    }

    /** 内存 catalog：内置四个确定性 Skill，覆盖 docx/xlsx 派生分支。 */
    public static final class InMemorySkillCatalog implements SkillCatalog {
        private final List<SkillDefinition> skills;

        public InMemorySkillCatalog() {
            this.skills = Arrays.asList(
                    new SkillDefinition("amazon-niche-finder", "细分市场发现", "Amazon Niche Finder", "research",
                            "发现高潜力细分市场", "Find niche markets",
                            Arrays.asList("收集类目数据", "识别细分", "评估竞争度"),
                            Arrays.asList("meta_search_skills", "data_query"),
                            "分析细分机会", true, false,
                            "## 流程\n1. 先确认站点与时间范围\n2. 数据缺失写 NEEDS_DATA\n3. 输出只读建议",
                            List.of("meta_search_skills", "data_query")),
                    new SkillDefinition("keyword-traffic-reverse", "关键词流量反查", "Keyword Traffic Reverse", "keywords",
                            "反查竞品流量关键词", "Reverse competitor traffic keywords",
                            Arrays.asList("选择竞品 ASIN", "反查关键词", "汇总流量结构"),
                            List.of("data_query"), "反查流量来源", false, true,
                            "## 流程\n1. 读取 ASIN\n2. 反查关键词排名\n3. 导出表格", List.of("data_query")),
                    new SkillDefinition("inventory-restock-plan", "补货计划", "Inventory Restock Plan", "inventory",
                            "基于库存快照生成补货建议", "Restock suggestions",
                            Arrays.asList("读取库存快照", "计算周转", "给出补货建议"),
                            List.of("data_query"), "生成补货建议", false, false,
                            "## 流程\n1. 校验库存快照\n2. 标记缺货风险", List.of("data_query")),
                    new SkillDefinition("listing-optimizer", "Listing 优化", "Listing Optimizer", "listing",
                            "优化标题与五点描述", "Optimize listing copy",
                            Arrays.asList("读取当前文案", "诊断问题", "给出改写建议"),
                            List.of("data_query"), "优化文案", false, false,
                            "## 流程\n1. 读取文案\n2. 给出只读改写建议", List.of("data_query")));
        }

        public InMemorySkillCatalog(List<SkillDefinition> skills) { this.skills = new ArrayList<>(skills); }

        @Override public List<SkillDefinition> listSkills() { return java.util.Collections.unmodifiableList(skills); }

        @Override public SkillDefinition findSkill(String slug) {
            if (slug == null) return null;
            for (SkillDefinition s : skills) {
                if (s.getSlug().equals(slug)) return s;
            }
            return null;
        }

        @Override public String skillMarkdown(String slug) {
            SkillDefinition s = findSkill(slug);
            return s == null ? null : s.getMarkdown();
        }

        @Override public SkillManifest.Manifest manifest(String slug) {
            SkillDefinition s = findSkill(slug);
            if (s == null) return null;
            return SkillManifest.build(s.getSlug(), s.getNameZh(), s.getSummaryZh(),
                    s.getMarkdown() == null ? "" : s.getMarkdown(), s.getRequiredTools());
        }
    }
}

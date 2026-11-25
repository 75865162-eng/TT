package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 移植自 src/lib/lili/catalog.ts。加载 classpath 上 shipped 的 skills/taxonomy/tools/
 * tool-docs/tool-labels/workflows 定义，精确复刻 listSkills/listTools/listWorkflows/
 * findSkill/skillMarkdown 的过滤、排序、提示词拼装逻辑。只读定义，绝不初始化第二个数据库。
 */
public final class Catalog {

    private Catalog() {}

    /** catalog.ts 的 InputSchema。 */
    public static final class InputSchema {
        public final Map<String, Object> raw;
        InputSchema(Map<String, Object> raw) { this.raw = raw == null ? new HashMap<>() : raw; }
        public String type() { return JsonSupport.str(raw.get("type")); }
        @SuppressWarnings("unchecked")
        public List<String> required() {
            Object v = raw.get("required");
            return v instanceof List ? (List<String>) v : Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        public Map<String, Object> properties() {
            Object v = raw.get("properties");
            return v instanceof Map ? (Map<String, Object>) v : Collections.emptyMap();
        }
        public Object item(String name) { return properties().get(name); }
        public String description() { return JsonSupport.str(raw.get("description")); }
        public Object items() { return raw.get("items"); }
        @SuppressWarnings("unchecked")
        public List<Object> enumValues() {
            Object v = raw.get("enum");
            return v instanceof List ? (List<Object>) v : Collections.emptyList();
        }
    }

    public static final class CatalogSkill {
        public final String slug, nameZh, nameEn, category, summaryZh, summaryEn, promptZh, promptEn;
        public final List<String> stepsZh, stepsEn, tools;
        public final boolean featured, isNew;
        public final Map<String, Object> approvalPolicy;
        CatalogSkill(String slug, String nameZh, String nameEn, String category, String summaryZh, String summaryEn,
                     String promptZh, String promptEn, List<String> stepsZh, List<String> stepsEn, List<String> tools,
                     boolean featured, boolean isNew) {
            this.slug = slug; this.nameZh = nameZh; this.nameEn = nameEn; this.category = category;
            this.summaryZh = summaryZh; this.summaryEn = summaryEn; this.promptZh = promptZh; this.promptEn = promptEn;
            this.stepsZh = stepsZh; this.stepsEn = stepsEn; this.tools = tools; this.featured = featured; this.isNew = isNew;
            this.approvalPolicy = ApprovalPolicy.toMap();
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("slug", slug); m.put("nameZh", nameZh); m.put("nameEn", nameEn); m.put("category", category);
            m.put("summaryZh", summaryZh); m.put("summaryEn", summaryEn); m.put("promptZh", promptZh); m.put("promptEn", promptEn);
            m.put("stepsZh", stepsZh); m.put("stepsEn", stepsEn); m.put("tools", tools);
            m.put("featured", featured); m.put("isNew", isNew); m.put("approvalPolicy", approvalPolicy);
            return m;
        }
    }

    public static final class CatalogTool {
        public final String name, description, category, docs, availability, nameZh, descriptionZh, kindLabel;
        public final boolean readOnly;
        public final InputSchema inputSchema;
        CatalogTool(String name, String description, String category, boolean readOnly, InputSchema inputSchema,
                    String docs, String availability, String nameZh, String descriptionZh, String kindLabel) {
            this.name = name; this.description = description; this.category = category; this.readOnly = readOnly;
            this.inputSchema = inputSchema; this.docs = docs; this.availability = availability;
            this.nameZh = nameZh; this.descriptionZh = descriptionZh; this.kindLabel = kindLabel;
        }
    }

    public static final class WorkflowStep {
        public final int n;
        public final String zh, en, skill;
        WorkflowStep(int n, String zh, String en, String skill) { this.n = n; this.zh = zh; this.en = en; this.skill = skill; }
    }

    public static final class CatalogWorkflow {
        public final String slug, category, titleZh, titleEn, summaryZh, summaryEn, promptZh, promptEn;
        public final int index;
        public final List<WorkflowStep> steps;
        public final Map<String, Object> approvalPolicy = ApprovalPolicy.toMap();
        CatalogWorkflow(String slug, int index, String category, String titleZh, String titleEn,
                        String summaryZh, String summaryEn, String promptZh, String promptEn, List<WorkflowStep> steps) {
            this.slug = slug; this.index = index; this.category = category; this.titleZh = titleZh; this.titleEn = titleEn;
            this.summaryZh = summaryZh; this.summaryEn = summaryEn; this.promptZh = promptZh; this.promptEn = promptEn;
            this.steps = steps;
        }
    }

    private static final Set<String> CATALOG_TOOLS = Set.of("meta_search_skills", "meta_load_skill", "meta_list_workflows");
    private static final Pattern SKILL_REF = Pattern.compile("(?:用|Use)\\s+([a-z0-9-]+)");

    private static final List<Object> RAW_SKILLS = JsonSupport.parseArray(JsonSupport.resource("skills.json"));
    private static final Map<String, Object> TAXONOMY = JsonSupport.parseObject(JsonSupport.resource("taxonomy.json"));
    private static final Map<String, Object> TOOL_DOCS = JsonSupport.parseObject(JsonSupport.resource("tool-docs.json"));
    private static final Map<String, Object> TOOL_LABELS = JsonSupport.parseObject(JsonSupport.resource("tool-labels.json"));
    private static final List<Object> RAW_EN = JsonSupport.parseArray(JsonSupport.resource("workflows-en.json"));
    private static final List<Object> RAW_ZH = JsonSupport.parseArray(JsonSupport.resource("workflows-zh.json"));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tax(String slug) {
        Object v = TAXONOMY.get(slug);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    public static List<CatalogSkill> listSkills() {
        List<CatalogSkill> skills = new ArrayList<>();
        for (Object raw : RAW_SKILLS) {
            Map<String, Object> skill = JsonSupport.obj(raw);
            String slug = JsonSupport.str(skill.get("slug"));
            Map<String, Object> t = tax(slug);
            String nameEn = t != null && t.get("nameEn") != null ? JsonSupport.str(t.get("nameEn")) : JsonSupport.str(skill.get("en"));
            String summaryEn = t != null && t.get("summaryEn") != null ? JsonSupport.str(t.get("summaryEn")) : JsonSupport.str(skill.get("enSum"));
            String category = t != null && t.get("cat") != null ? JsonSupport.str(t.get("cat")) : JsonSupport.str(skill.get("cat"));
            List<String> stepsZh = strings(skill.get("stepsZh"));
            List<String> stepsEn = strings(skill.get("stepsEn"));
            StringBuilder pZh = new StringBuilder();
            pZh.append("使用粒粒lili技能 ").append(slug).append("。\n").append(skill.get("zhSum")).append("\n\n按这个顺序做：\n");
            for (int i = 0; i < stepsZh.size(); i++) pZh.append(i + 1).append(". ").append(stepsZh.get(i)).append('\n');
            pZh.append('\n').append(ApprovalPolicy.INSTRUCTIONS_ZH);
            StringBuilder pEn = new StringBuilder();
            pEn.append("Use the 粒粒lili skill ").append(slug).append(".\n").append(summaryEn).append("\n\nDo it in this order:\n");
            for (int i = 0; i < stepsEn.size(); i++) pEn.append(i + 1).append(". ").append(stepsEn.get(i)).append('\n');
            pEn.append('\n').append(ApprovalPolicy.INSTRUCTIONS_EN);
            boolean featured = t != null && Boolean.TRUE.equals(t.get("featured"));
            boolean isNew = t != null && Boolean.TRUE.equals(t.get("isNew"));
            skills.add(new CatalogSkill(slug, JsonSupport.str(skill.get("zh")), nameEn, category,
                JsonSupport.str(skill.get("zhSum")), summaryEn, pZh.toString(), pEn.toString(),
                stepsZh, stepsEn, strings(skill.get("tools")), featured, isNew));
        }
        skills.sort((a, b) -> {
            int sa = tax(a.slug) != null && tax(a.slug).get("sort") instanceof Number ? ((Number) tax(a.slug).get("sort")).intValue() : 500;
            int sb = tax(b.slug) != null && tax(b.slug).get("sort") instanceof Number ? ((Number) tax(b.slug).get("sort")).intValue() : 500;
            int c = Integer.compare(sa, sb);
            return c != 0 ? c : a.nameEn.compareTo(b.nameEn);
        });
        return skills;
    }

    /** 兼容 duobaoyu- / lili- 前缀。 */
    public static CatalogSkill findSkill(String slug) {
        if (slug == null) return null;
        String shortSlug = slug.replaceFirst("^(?:duobaoyu-|lili-)", "");
        for (CatalogSkill skill : listSkills()) if (skill.slug.equals(shortSlug) || skill.slug.equals(slug)) return skill;
        return null;
    }

    /** 动态 import skill-bodies.json：仅详情、下载或显式工具调用时加载全文。 */
    public static String skillMarkdown(String slug) {
        CatalogSkill skill = findSkill(slug);
        if (skill == null) return null;
        Map<String, Object> bodies = JsonSupport.parseObject(JsonSupport.resource("skill-bodies.json"));
        Object body = bodies.get(skill.slug);
        return body instanceof String ? (String) body : null;
    }

    public static List<CatalogTool> listTools() {
        Map<String, Object> toolsRoot = JsonSupport.parseObject(JsonSupport.resource("tools.json"));
        List<CatalogTool> tools = new ArrayList<>();
        for (Object raw : JsonSupport.arr(toolsRoot.get("tools"))) {
            Map<String, Object> tool = JsonSupport.obj(raw);
            String name = JsonSupport.str(tool.get("name"));
            Map<String, Object> label = JsonSupport.obj(TOOL_LABELS.get(name));
            Map<String, Object> ann = JsonSupport.obj(tool.get("annotations"));
            String docs = JsonSupport.str(TOOL_DOCS.get(name));
            if (docs == null) docs = JsonSupport.str(tool.get("description"));
            String category = name.split("_", -1)[0];
            tools.add(new CatalogTool(name, JsonSupport.str(tool.get("description")), category,
                Boolean.TRUE.equals(ann.get("readOnlyHint")), new InputSchema(JsonSupport.obj(tool.get("inputSchema"))),
                docs, CATALOG_TOOLS.contains(name) ? "catalog" : "unmapped",
                label == null ? null : JsonSupport.str(label.get("zh")),
                label == null ? null : JsonSupport.str(label.get("desc")),
                label == null ? null : JsonSupport.str(label.get("type"))));
        }
        tools.sort((a, b) -> a.name.compareTo(b.name));
        return tools;
    }

    @SuppressWarnings("unchecked")
    public static List<CatalogWorkflow> listWorkflows() {
        List<CatalogWorkflow> workflows = new ArrayList<>();
        for (Object zhRaw : RAW_ZH) {
            Map<String, Object> zh = JsonSupport.obj(zhRaw);
            Object index = zh.get("index");
            Map<String, Object> en = null;
            for (Object enRaw : RAW_EN) {
                Map<String, Object> row = JsonSupport.obj(enRaw);
                if (row.get("index").equals(index)) { en = row; break; }
            }
            if (en == null) throw new IllegalStateException("LILI_CATALOG_WORKFLOW_TRANSLATION_MISSING");
            List<String> zhSteps = strings(zh.get("steps"));
            List<Object> enSteps = JsonSupport.arr(en.get("steps"));
            List<WorkflowStep> steps = new ArrayList<>();
            for (int i = 0; i < zhSteps.size(); i++) {
                String enStep = i < enSteps.size() && enSteps.get(i) instanceof String ? (String) enSteps.get(i) : zhSteps.get(i);
                Matcher m = SKILL_REF.matcher(zhSteps.get(i));
                String skill = null;
                if (m.find()) skill = m.group(1);
                else {
                    Matcher em = SKILL_REF.matcher(enStep);
                    if (em.find()) skill = em.group(1);
                }
                steps.add(new WorkflowStep(i + 1, zhSteps.get(i), enStep, skill));
            }
            StringBuilder pEn = new StringBuilder("Use 粒粒lili and follow this order:\n");
            for (int i = 0; i < enSteps.size(); i++) pEn.append(i + 1).append(". ").append(enSteps.get(i)).append('\n');
            pEn.append('\n').append(JsonSupport.str(en.get("close"))).append("\n\n").append(ApprovalPolicy.INSTRUCTIONS_EN);
            workflows.add(new CatalogWorkflow(JsonSupport.str(en.get("slug")), ((Number) index).intValue(),
                JsonSupport.str(en.get("category")), JsonSupport.str(zh.get("title")), JsonSupport.str(en.get("titleEn")),
                JsonSupport.str(zh.get("summary")), JsonSupport.str(en.get("summaryEn")),
                JsonSupport.str(zh.get("prompt")) + "\n\n" + ApprovalPolicy.INSTRUCTIONS_ZH,
                pEn.toString(), steps));
        }
        return workflows;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) for (Object v : (List<Object>) value) if (v instanceof String) out.add((String) v);
        return out;
    }
}

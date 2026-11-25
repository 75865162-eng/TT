package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wimoor.ai.skill.SkillManifest;

/** 移植自 src/lib/lili/tool-execution.ts：目录工具的 Schema 校验与 meta_* 工具执行。 */
public final class ToolExecution {

    private ToolExecution() {}

    public static final class ToolResult {
        public final int status;
        public final Map<String, Object> body;
        public ToolResult(int status, Map<String, Object> body) { this.status = status; this.body = body; }
    }

    /** validateToolInput：必填、未知参数、类型、enum、字符串长度（4000）。 */
    public static List<String> validateToolInput(Catalog.InputSchema schema, Object input) {
        return SchemaValidator.validateToolInput(schema, input);
    }

    public static ToolResult runCatalogTool(String name, Object input) {
        Catalog.CatalogTool tool = null;
        for (Catalog.CatalogTool t : Catalog.listTools()) if (t.name.equals(name)) { tool = t; break; }
        if (tool == null) return new ToolResult(404, Ports.jsonObject("ok", false, "error", "unknown_tool"));
        List<String> errors = validateToolInput(tool.inputSchema, input);
        if (!errors.isEmpty())
            return new ToolResult(400, Ports.jsonObject("ok", false, "error", "invalid_tool_input", "details", errors));
        Map<String, Object> args = JsonSupport.obj(input);

        if ("meta_search_skills".equals(name)) {
            String query = JsonSupport.str(args.get("query"));
            if (query == null) query = "";
            query = query.trim().toLowerCase(Locale.ROOT);
            if (query.isEmpty()) return new ToolResult(400, Ports.jsonObject("ok", false, "error", "empty_query"));
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Catalog.CatalogSkill skill : Catalog.listSkills()) {
                String hay = (skill.slug + " " + skill.nameZh + " " + skill.nameEn + " " + skill.summaryZh + " " + skill.summaryEn)
                    .toLowerCase(Locale.ROOT);
                if (hay.contains(query)) matches.add(skill.toMap());
            }
            List<Map<String, Object>> page = new ArrayList<>(matches.subList(0, Math.min(20, matches.size())));
            return new ToolResult(200, Ports.jsonObject("ok", true, "source", "shipped-definitions",
                "skills", page, "total", matches.size()));
        }

        if ("meta_load_skill".equals(name)) {
            Object skillName = args.get("skill_name");
            Catalog.CatalogSkill skill = Catalog.findSkill(skillName == null ? null : String.valueOf(skillName));
            if (skill == null) return new ToolResult(404, Ports.jsonObject("ok", false, "error", "unknown_skill"));
            String markdown = Catalog.skillMarkdown(skill.slug);
            SkillManifest.Manifest manifest = SkillManifest.build(skill.slug, skill.nameZh, skill.summaryZh,
                markdown == null ? "" : markdown, skill.tools);
            Map<String, Object> manifestMap = new LinkedHashMap<>();
            manifestMap.put("skill_id", manifest.getSkillId());
            manifestMap.put("name", manifest.getName());
            manifestMap.put("description", manifest.getDescription());
            manifestMap.put("sha256", manifest.getSha256());
            manifestMap.put("version", manifest.getVersion());
            manifestMap.put("required_tools", manifest.getRequiredTools());
            manifestMap.put("source", manifest.getSource());
            return new ToolResult(200, Ports.jsonObject("ok", true, "source", "shipped-definitions",
                "skill", skill.toMap(), "manifest", manifestMap, "markdown", markdown));
        }

        if ("meta_list_workflows".equals(name)) {
            List<Map<String, Object>> templates = new ArrayList<>();
            for (Catalog.CatalogWorkflow w : Catalog.listWorkflows()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("slug", w.slug); m.put("index", w.index); m.put("category", w.category);
                m.put("titleZh", w.titleZh); m.put("titleEn", w.titleEn);
                m.put("summaryZh", w.summaryZh); m.put("summaryEn", w.summaryEn);
                m.put("promptZh", w.promptZh); m.put("promptEn", w.promptEn);
                m.put("approvalPolicy", w.approvalPolicy);
                List<Map<String, Object>> steps = new ArrayList<>();
                for (Catalog.WorkflowStep s : w.steps) steps.add(Ports.jsonObject("n", s.n, "zh", s.zh, "en", s.en, "skill", s.skill));
                m.put("steps", steps);
                templates.add(m);
            }
            return new ToolResult(200, Ports.jsonObject("ok", true, "source", "shipped-definitions",
                "definitionOnly", true, "templates", templates,
                "message", "仅工作流定义；没有查询保存记录、调度或执行状态。"));
        }

        String nextPath = name.startsWith("ads_") ? "/amazon-ads" : name.startsWith("listing_") ? "/listing-ai" : "/agents";
        return new ToolResult(501, Ports.jsonObject("ok", false,
            "error", tool.readOnly ? "tool_adapter_not_mapped" : "live_write_unsupported", "tool", name,
            "nextAction", Ports.jsonObject("kind", "UNSUPPORTED_EXECUTOR", "channel", "codex_chat", "browserRequired", false),
            "message", "无需返回浏览器；请在对话中说明未执行部分并保留分析或草稿。该 Lili 工具尚未映射到主系统的授权执行器。未查询业务数据、创建任务或执行外部写入。",
            "nextPath", nextPath));
    }
}

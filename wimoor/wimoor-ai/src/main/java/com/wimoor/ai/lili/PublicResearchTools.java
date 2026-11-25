package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wimoor.ai.lili.Ports.ToolDefinition;

/**
 * 移植自 src/lib/lili/public-research-tools.ts：匿名阶段 0 准备工具。
 * 注册表声明只是需求，不代表供应商工具已连接；不访问账号数据、不持久化任务、不调用模型。
 */
public final class PublicResearchTools {

    private PublicResearchTools() {}

    public static final List<ToolDefinition> PUBLIC_RESEARCH_TOOLS = List.of(
        new ToolDefinition("research_prepare",
            "Prepare stage 0 from natural language without authentication: parse explicit constraints, load the existing five-stage Skill template and preview planning call ranges and research credits (1 credit = CNY 0.01); task call caps are optional. No account data, private settings, task persistence, AI model or market API is used. Missing information and supplier costs remain unknown. Then authenticate a data scope before research_start; the first paid request requires task-level human approval, and subsequent paid read calls run within the saved capped or uncapped task authorization.",
            Ports.jsonObject("type", "object",
                "properties", Ports.jsonObject("requirement", Ports.jsonObject("type", "string", "minLength", 1, "maxLength", 12000),
                    "marketplace", Ports.jsonObject("type", "string", "minLength", 1, "maxLength", 16)),
                "required", List.of("requirement"), "additionalProperties", false),
            Ports.annotations(true))
    );

    public static Map<String, Object> callPublicResearchTool(String name, Map<String, Object> args) {
        ToolDefinition tool = null;
        for (ToolDefinition t : PUBLIC_RESEARCH_TOOLS) if (t.name.equals(name)) { tool = t; break; }
        if (tool == null) throw new IllegalStateException("UNKNOWN_PUBLIC_RESEARCH_TOOL");
        SchemaValidator.rejectCredentials(args);
        SchemaValidator.validate(tool.inputSchema, args);
        String requirement = String.valueOf(args.get("requirement"));
        if (requirement.trim().isEmpty()) throw new IllegalStateException("EMPTY_RESEARCH_REQUIREMENT");
        Object explicit = args.get("marketplace");
        Map<String, Object> resolution = ResearchSupport.resolveResearchMarketplace(requirement,
            explicit instanceof String ? (String) explicit : null);
        String marketplace = JsonSupport.str(resolution.get("value"));
        if (marketplace == null) marketplace = "";
        Map<String, Object> brief = ResearchSupport.parseResearchBrief(requirement, marketplace);
        brief.put("marketplace", marketplace.isEmpty() ? null : marketplace);
        @SuppressWarnings("unchecked")
        List<String> unresolved = new ArrayList<>((List<String>) brief.get("unresolved"));
        if (marketplace.isEmpty()) unresolved.add("marketplace");
        brief.put("unresolved", unresolved);
        List<Map<String, Object>> skills = new ArrayList<>();
        List<Map<String, Object>> bundled = ResearchSupport.loadBundledResearchSkills();
        for (int i = 0; i < bundled.size(); i++) {
            Map<String, Object> skill = bundled.get(i);
            skills.add(Ports.jsonObject("stage", i + 1, "name", skill.get("name"), "source", skill.get("path"),
                "sha256", skill.get("sha256"), "declaredTools", skill.get("tools"),
                "loadTool", Ports.jsonObject("name", "meta_load_skill",
                    "arguments", Ports.jsonObject("skill_name", skill.get("name")))));
        }
        boolean conflict = Boolean.TRUE.equals(resolution.get("conflict"));
        List<String> missing = new ArrayList<>();
        missing.add("workspaceId");
        if (marketplace.isEmpty()) missing.add("marketplace");
        Map<String, Object> nextArgs = new LinkedHashMap<>();
        nextArgs.put("requirement", requirement);
        if (!marketplace.isEmpty()) nextArgs.put("marketplace", marketplace);
        String message = conflict
            ? "站点参数与原文或多个站点之间存在冲突，先请用户明确目标站点；不得默认覆盖。"
            : "阶段0未创建持久化任务。使用arguments保留原始需求；认证后从绑定的数据范围补充workspaceId。连接与Skill加载无需ERP网页登录；真实付费请求前仍须展示预算和完整请求、等待人工授权。";
        return Ports.jsonObject("ok", true, "stage", 0, "persisted", false, "scope", "anonymous-definitions",
            "marketDataCollected", false, "brief", brief, "marketplaceResolution", resolution, "skills", skills,
            "budget", ResearchSupport.researchBudgetPreview(Ports.jsonObject("skills", bundled, "budgetMode", "uncapped")),
            "discovery", Ports.jsonObject("status", "AUTHENTICATED_PROVIDER_DISCOVERY_REQUIRED",
                "verifiedSupplierTools", new ArrayList<>()),
            "nextAction", Ports.jsonObject("kind", marketplace.isEmpty() ? "COLLECT_INPUT" : "AUTHENTICATE_FOR_DATA",
                "tool", "research_start", "arguments", nextArgs, "missingArguments", missing, "message", message));
    }
}

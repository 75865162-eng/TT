package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wimoor.ai.lili.Ports.ApiRequest;
import com.wimoor.ai.lili.Ports.ApiResponse;
import com.wimoor.ai.lili.Ports.ToolDefinition;

/**
 * 移植自 src/lib/lili/public-mcp.ts：公开定义端点。只暴露 Skill 目录与公开研究工具，
 * 不暴露用户设置、凭证、已保存任务或供应商适配器。
 */
public final class PublicMcp {

    private PublicMcp() {}

    public static final class McpResult {
        public final ApiResponse response;
        public final boolean terminal;
        McpResult(ApiResponse response, boolean terminal) { this.response = response; this.terminal = terminal; }
    }

    private static final Set<String> SKILL_NAMES;
    static {
        Set<String> names = new java.util.HashSet<>();
        names.add("meta_search_skills"); names.add("meta_load_skill"); names.add("meta_list_workflows");
        SkillMeta.SKILL_META_TOOLS.forEach(t -> names.add(t.name));
        SKILL_NAMES = names;
    }

    public static Map<String, Object> catalog(RuntimeTools skills) {
        List<Map<String, Object>> tools = new ArrayList<>();
        skills.discover().tools.forEach(t -> tools.add(t.toMap()));
        PublicResearchTools.PUBLIC_RESEARCH_TOOLS.forEach(t -> tools.add(t.toMap()));
        for (ToolDefinition t : ResearchTools.RESEARCH_MCP_TOOLS)
            tools.add(Ports.jsonObject("name", t.name, "description", t.description
                + " Business data and persisted research require authenticated organization/workspace/owner scope; no ERP browser login is required to connect or load Skills.",
                "inputSchema", JsonSupport.copy(t.inputSchema), "annotations", t.annotations));
        return Ports.jsonObject("scope", "anonymous-definitions", "source", "public-skill-catalog",
            "providers", new ArrayList<>(), "tools", tools);
    }

    /** publicMcpResponse：terminal=true 表示请求已被终结，不再继续权限校验。 */
    public static McpResult publicMcpResponse(ApiRequest request, Map<String, Object> body, RuntimeTools skills) {
        if ("GET".equals(request.method)) return new McpResult(ApiResponse.json(catalog(skills)), false);
        if (!"POST".equals(request.method)) return null;
        Object rawId = body == null ? null : body.get("id");
        Object id = rawId instanceof String || rawId instanceof Number ? rawId : null;
        ApiResponse invalid = ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
            "error", Ports.jsonObject("code", -32600, "message", "Invalid request.")), 400);
        if (body == null || !"2.0".equals(body.get("jsonrpc")) || !(body.get("method") instanceof String)
            || (body.containsKey("id") && body.get("id") != null && !(body.get("id") instanceof String)
                && !(body.get("id") instanceof Number))) return new McpResult(invalid, false);
        String origin = request.header("origin");
        if (origin != null && !origin.equals(request.origin()))
            return new McpResult(ApiResponse.json(Ports.jsonObject("error", "origin_mismatch"), 403), true);
        Map<String, Object> params = JsonSupport.obj(body.get("params"));
        if (body.get("params") != null && params == null) return new McpResult(invalid, false);
        if (params == null) params = new LinkedHashMap<>();
        if ("initialize".equals(body.get("method"))) {
            Object requested = params.get("protocolVersion");
            Object version = Set.of("2024-11-05", "2025-03-26", "2025-06-18").contains(String.valueOf(requested))
                ? requested : "2025-06-18";
            return new McpResult(ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                "result", Ports.jsonObject("protocolVersion", version,
                    "serverInfo", Ports.jsonObject("name", "lili-mcp", "version", SkillMeta.LILI_VERSION),
                    "capabilities", Ports.jsonObject("tools", new LinkedHashMap<>()),
                    "instructions", SkillMeta.LILI_INSTRUCTIONS))), true);
        }
        if ("ping".equals(body.get("method")))
            return new McpResult(ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                "result", new LinkedHashMap<>())), true);
        if ("notifications/initialized".equals(body.get("method")) && !body.containsKey("id"))
            return new McpResult(ApiResponse.empty(202), true);
        if ("tools/list".equals(body.get("method")))
            return new McpResult(ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                "result", catalog(skills))), false);
        final Map<String, Object> toolParams = params;
        if ("tools/call".equals(body.get("method")) && toolParams.get("name") instanceof String
            && (SKILL_NAMES.contains(toolParams.get("name")) || PublicResearchTools.PUBLIC_RESEARCH_TOOLS.stream()
                .anyMatch(t -> t.name.equals(toolParams.get("name"))))) {
            String name = (String) toolParams.get("name");
            Map<String, Object> args = JsonSupport.obj(toolParams.get("arguments"));
            if (args == null) args = new LinkedHashMap<>();
            try {
                boolean isPublicResearch = PublicResearchTools.PUBLIC_RESEARCH_TOOLS.stream().anyMatch(t -> t.name.equals(name));
                Object result = isPublicResearch ? PublicResearchTools.callPublicResearchTool(name, args)
                    : skills.call(name, args);
                return new McpResult(ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                    "result", Ports.jsonObject("content", List.of(Ports.jsonObject("type", "text",
                        "text", JsonSupport.stringify(result))), "isError", false))), true);
            } catch (Exception e) {
                Map<String, Object> failure = Ports.jsonObject("error", "INVALID_PUBLIC_SKILL_ARGUMENTS");
                Map<String, Object> content = Ports.jsonObject("type", "text", "text", JsonSupport.stringify(failure));
                Map<String, Object> result = Ports.jsonObject("content", List.of(content), "isError", true);
                return new McpResult(ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id, "result", result)), true);
            }
        }
        return null;
    }
}

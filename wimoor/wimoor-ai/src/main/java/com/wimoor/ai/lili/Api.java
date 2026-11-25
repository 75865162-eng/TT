package com.wimoor.ai.lili;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wimoor.ai.lili.Ports.ApiRequest;
import com.wimoor.ai.lili.Ports.ApiResponse;
import com.wimoor.ai.lili.Ports.Authorization;
import com.wimoor.ai.lili.Ports.LiliAuthorize;
import com.wimoor.ai.lili.Ports.PermissionAction;
import com.wimoor.ai.lili.Ports.ToolNotDispatchedException;
import com.wimoor.ai.lili.ResearchBearerMcp.ResearchBearerDependencies;
import com.wimoor.ai.lili.ResearchTools.ResearchDispatcher;
import com.wimoor.ai.lili.RuntimeTools.RuntimeToolError;
import com.wimoor.ai.lili.ToolConfigApi.ToolConfigDependencies;

/**
 * 移植自 src/lib/lili/api.ts：统一 Lili HTTP/JSON-RPC 路由。
 * 两个命名空间共用公开 Skill 定义，业务操作共用宿主作用域鉴权；
 * 请求体限长、origin/协议版本校验、方法校验、错误码映射均为真实逻辑。
 */
public final class Api {

    private Api() {}

    public static class RequestFormatException extends RuntimeException {
        RequestFormatException(String message) { super(message); }
    }

    public static final class Endpoint {
        public final List<String> methods;
        public final String moduleId, nextPath;
        Endpoint(List<String> methods, String moduleId, String nextPath) {
            this.methods = methods; this.moduleId = moduleId; this.nextPath = nextPath;
        }
    }

    private static Endpoint ep(List<String> methods) { return new Endpoint(methods, null, null); }
    private static Endpoint ep(List<String> methods, String moduleId, String nextPath) {
        return new Endpoint(methods, moduleId, nextPath);
    }

    public static final Map<String, Endpoint> ENDPOINTS;
    static {
        Map<String, Endpoint> m = new LinkedHashMap<>();
        m.put("health", ep(List.of("GET")));
        m.put("mcp", ep(List.of("GET", "POST")));
        m.put("codex-bundle", ep(List.of("GET")));
        m.put("preferences", ep(List.of("POST")));
        m.put("tools/run", ep(List.of("POST")));
        m.put("tool-config", ep(List.of("GET", "PUT"), "settings", null));
        m.put("tool-config/mappings", ep(List.of("GET"), "settings", null));
        m.put("tool-config/test", ep(List.of("POST"), "settings", null));
        m.put("team", ep(List.of("POST"), "accounts", "/accounts"));
        m.put("session", ep(List.of("POST", "DELETE"), null, "/login"));
        m.put("stores", ep(List.of("POST"), "operations", "/operations"));
        m.put("ads-profiles", ep(List.of("POST"), "operations", "/amazon-ads"));
        m.put("connections", ep(List.of("POST"), "operations", "/operations"));
        m.put("connections/[id]", ep(List.of("PATCH", "DELETE"), "operations", "/operations"));
        m.put("approvals/[id]", ep(List.of("POST"), null, "/agents"));
        m.put("shopify", ep(List.of("POST", "DELETE"), "settings", "/settings"));
        m.put("credits", ep(List.of("POST")));
        m.put("feedback", ep(List.of("POST")));
        m.put("workflows/pack", ep(List.of("POST"), null, "/agents"));
        m.put("workflows/run", ep(List.of("POST"), null, "/agents"));
        m.put("workflows/selection-doc", ep(List.of("POST"), null, "/agents/market/selection"));
        m.put("workflows/selection-html", ep(List.of("GET"), null, "/agents/market/selection"));
        ENDPOINTS = Map.copyOf(m);
    }

    public static String endpointKey(List<String> parts) {
        if (parts.size() == 2 && Set.of("approvals", "connections").contains(parts.get(0)))
            return parts.get(0) + "/[id]";
        return String.join("/", parts);
    }

    /** boundedJson：16KiB 上限、仅 application/json、顶层必须是对象。 */
    public static Map<String, Object> boundedJson(String contentType, byte[] raw) {
        if (contentType == null || !contentType.contains("application/json")) throw new RequestFormatException("Expected JSON");
        if (raw == null) throw new RequestFormatException("Missing body");
        if (raw.length > 16_384) throw new RequestFormatException("Body too large");
        Object parsed = JsonSupport.parse(new String(raw, StandardCharsets.UTF_8));
        Map<String, Object> body = JsonSupport.obj(parsed);
        if (body == null) throw new RequestFormatException("Expected object");
        return body;
    }

    /** 宿主依赖注入端口。 */
    public static final class Dependencies {
        public final ToolConfigDependencies toolConfig;
        public final RuntimeTools runtimeTools;
        public final ResearchBearerDependencies bearer;
        public final ResearchDispatcher researchDispatcher;
        public Dependencies(ToolConfigDependencies toolConfig, RuntimeTools runtimeTools,
                            ResearchBearerDependencies bearer, ResearchDispatcher researchDispatcher) {
            this.toolConfig = toolConfig; this.runtimeTools = runtimeTools;
            this.bearer = bearer; this.researchDispatcher = researchDispatcher;
        }
        public Dependencies() { this(null, null, null, null); }
    }

    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    private static void requireReviewedMarketCall(String name) {
        if (name.startsWith("sellersprite__") || name.startsWith("xiyou__"))
            throw new IllegalStateException("RESEARCH_REQUEST_REQUIRES_HUMAN_REVIEW");
    }

    public static ApiResponse handleLiliRequest(ApiRequest request, List<String> parts,
                                                LiliAuthorize authorize, Dependencies deps) {
        Dependencies d = deps == null ? new Dependencies() : deps;
        LiliAuthorize auth = authorize == null ? (m, a, h, b) -> Authorization.deny(503, "lili_unavailable") : authorize;
        try {
            String key = endpointKey(parts);
            if ("mcp".equals(key)) {
                String origin = request.header("origin");
                if (origin != null && !origin.equals(request.origin()))
                    return ApiResponse.json(Ports.jsonObject("error", "origin_mismatch"), 403);
                String version = request.header("mcp-protocol-version");
                if (version != null && !SUPPORTED_PROTOCOLS.contains(version))
                    return ApiResponse.json(Ports.jsonObject("error", "unsupported_protocol_version"), 400);
            }
            Endpoint endpoint = ENDPOINTS.get(key);
            String moduleId = endpoint != null && endpoint.moduleId != null ? endpoint.moduleId : "agents";
            Map<String, Object> parsedBody = request.body;

            // 自带 bearer 永不回退到浏览器 cookie，即使 bearer 无效。
            if ("mcp".equals(key) && request.header("authorization") != null) {
                if (d.bearer == null) return ApiResponse.json(Ports.jsonObject("error", "lili_unavailable"), 503);
                return ResearchBearerMcp.handleResearchBearerMcp(request, parsedBody, d.bearer);
            }
            RuntimeTools skills = d.runtimeTools != null ? d.runtimeTools : RuntimeTools.createRuntimeTools(new ArrayList<>());
            PublicMcp.McpResult publicResult = "mcp".equals(key)
                ? PublicMcp.publicMcpResponse(request, parsedBody, skills) : null;
            if (publicResult != null && publicResult.terminal) return publicResult.response;

            Authorization permission;
            try { permission = auth.authorize(moduleId, PermissionAction.VIEW, request.headers, parsedBody); }
            catch (RuntimeException e) {
                if (publicResult != null) return publicResult.response;
                throw e;
            }
            if (!permission.ok) {
                if (publicResult != null) return publicResult.response;
                if ("mcp".equals(key) && permission.status == 401) {
                    Map<String, Object> body = Ports.jsonObject("jsonrpc", "2.0",
                        "id", parsedBody == null ? null : parsedBody.get("id"),
                        "error", Ports.jsonObject("code", -32001,
                            "message", "This tool requires an authenticated data scope. Connecting and loading Skills do not require ERP browser login.",
                            "data", Ports.jsonObject("businessCode", "AUTHENTICATED_DATA_SCOPE_REQUIRED",
                                "authenticationRequired", true)));
                    String metadata = metadataUrl(request.origin());
                    return ApiResponse.json(body, 401).withHeader("WWW-Authenticate",
                        "Bearer realm=\"lili-mcp\", resource_metadata=\"" + metadata + "\"");
                }
                return ApiResponse.json(Ports.jsonObject("ok", false, "error", permission.error), permission.status);
            }
            if (endpoint == null) return ApiResponse.json(Ports.jsonObject("ok", false, "error", "not_found"), 404);
            if (!endpoint.methods.contains(request.method))
                return ApiResponse.empty(405).withHeader("Allow", String.join(", ", endpoint.methods));
            if (!"GET".equals(request.method)) {
                String origin = request.header("origin");
                if (origin != null && !origin.equals(request.origin()))
                    return ApiResponse.json(Ports.jsonObject("ok", false, "error", "origin_mismatch"), 403);
            }

            if ("health".equals(key))
                return ApiResponse.json(Ports.jsonObject("ok", true, "source", "shipped-definitions",
                    "businessExecutionReady", false, "tools", Catalog.listTools().size(),
                    "skills", Catalog.listSkills().size(), "workflows", Catalog.listWorkflows().size()));
            if ("tool-config".equals(key) || "tool-config/test".equals(key)) {
                if (!"GET".equals(request.method)) {
                    Authorization edit = auth.authorize("settings", PermissionAction.EDIT, request.headers, parsedBody);
                    if (!edit.ok) return ApiResponse.json(Ports.jsonObject("ok", false, "error", edit.error), edit.status);
                    if (edit.userId == null || !edit.userId.equals(permission.userId)
                        || edit.organizationId == null || !edit.organizationId.equals(permission.organizationId))
                        return ApiResponse.json(Ports.jsonObject("message", "配置身份校验失败。"), 403);
                }
                if (permission.userId == null) return ApiResponse.json(Ports.jsonObject("message", "配置身份校验失败。"), 403);
                return ToolConfigApi.handleToolConfiguration(request.method, key, parsedBody,
                    permission.userId, permission.organizationId, request.headers, d.toolConfig);
            }
            if ("tool-config/mappings".equals(key)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Catalog.CatalogTool t : Catalog.listTools()) rows.add(Ports.jsonObject("name", t.name, "availability", t.availability));
                return ApiResponse.json(Ports.jsonObject("tools", rows, "source", "host-catalog"));
            }
            if ("codex-bundle".equals(key)) {
                Authorization export = auth.authorize("agents", PermissionAction.EXPORT, request.headers, parsedBody);
                if (!export.ok) return ApiResponse.json(Ports.jsonObject("ok", false, "error", export.error), export.status);
                return new ApiResponse(200, Map.of("Content-Type", "application/zip",
                    "Content-Disposition", "attachment; filename=\"lili-skills.zip\"",
                    "Cache-Control", "no-store", "X-Content-Type-Options", "nosniff"), SkillBundle.createSkillBundle());
            }
            if ("preferences".equals(key)) {
                Map<String, Object> body = parsedBody;
                if (body == null) throw new RequestFormatException("Expected JSON");
                Object locale = body.get("locale");
                if (!"zh".equals(locale) && !"en".equals(locale))
                    return ApiResponse.json(Ports.jsonObject("ok", false, "error", "invalid_locale"), 400);
                return ApiResponse.json(Ports.jsonObject("ok", true, "locale", locale)).withHeader("Set-Cookie",
                    "lili_locale=" + locale + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000");
            }

            if ("mcp".equals(key) && "GET".equals(request.method)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("source", "runtime-registry");
                result.put("scope", "authenticated-ERP-session");
                result.putAll(skills.discover().toMap());
                return ApiResponse.json(result);
            }
            if ("mcp".equals(key) || "tools/run".equals(key)) {
                Map<String, Object> body = parsedBody;
                if (body == null) throw new RequestFormatException("Expected JSON");
                if ("tools/run".equals(key)) {
                    if (!(body.get("name") instanceof String))
                        return ApiResponse.json(Ports.jsonObject("ok", false, "error", "invalid_tool_name"), 400);
                    String name = (String) body.get("name");
                    if (name.startsWith("sellersprite__") || name.startsWith("xiyou__"))
                        return ApiResponse.json(Ports.jsonObject("ok", false, "error", "RESEARCH_REQUEST_REQUIRES_HUMAN_REVIEW",
                            "nextPath", "/agents/selection-research"), 409);
                    Map<String, Object> args = JsonSupport.obj(body.get("input"));
                    if (args == null) args = new LinkedHashMap<>();
                    return ApiResponse.json(Ports.jsonObject("ok", true, "source", "runtime-adapter", "result", skills.call(name, args)));
                }
                Object id = body.get("id");
                if ("notifications/initialized".equals(body.get("method"))) return ApiResponse.empty(202);
                if ("initialize".equals(body.get("method")))
                    return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                        "result", Ports.jsonObject("protocolVersion", "2025-06-18",
                            "serverInfo", Ports.jsonObject("name", "lili-mcp", "version", "1.1.0"),
                            "capabilities", Ports.jsonObject("tools", new LinkedHashMap<>()))));
                if ("tools/list".equals(body.get("method")))
                    return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id, "result", skills.discover().toMap()));
                if ("tools/call".equals(body.get("method"))) {
                    Map<String, Object> params = JsonSupport.obj(body.get("params"));
                    if (params == null || !(params.get("name") instanceof String))
                        return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                            "error", Ports.jsonObject("code", -32602, "message", "Invalid tool parameters")), 400);
                    String name = (String) params.get("name");
                    Map<String, Object> args = JsonSupport.obj(params.get("arguments"));
                    if (args == null) args = new LinkedHashMap<>();
                    try {
                        boolean isResearch = ResearchTools.RESEARCH_MCP_TOOLS.stream().anyMatch(t -> t.name.equals(name));
                        Object output = isResearch
                            ? ResearchTools.callResearchMcpTool(request.url, name, args, null, d.researchDispatcher)
                            : dispatchRuntime(skills, name, args);
                        return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                            "result", Ports.jsonObject("content", List.of(Ports.jsonObject("type", "text",
                                "text", JsonSupport.stringify(output))), "isError", false)));
                    } catch (RuntimeException e) {
                        String code = "RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN";
                        String message = "请检查真实 Schema、当前授权；外部市场请求请通过 research_start/research_step 执行，审批在 Codex 对话里确认。";
                        if (e instanceof RuntimeToolError) code = ((RuntimeToolError) e).code;
                        else if (e instanceof ToolNotDispatchedException) code = ((ToolNotDispatchedException) e).code;
                        else if (e instanceof ResearchBearerMcp.AmazonReadException)
                            code = ((ResearchBearerMcp.AmazonReadException) e).businessCode;
                        return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                            "result", Ports.jsonObject("content", List.of(Ports.jsonObject("type", "text",
                                "text", JsonSupport.stringify(Ports.jsonObject("error", code, "message", message)))),
                                "isError", true)));
                    }
                }
                return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                    "error", Ports.jsonObject("code", -32601, "message", "Method not found")));
            }

            PermissionAction action = "GET".equals(request.method) ? PermissionAction.VIEW
                : key.startsWith("approvals/") ? PermissionAction.APPROVE
                : Set.of("PUT", "PATCH", "DELETE").contains(request.method) ? PermissionAction.EDIT : PermissionAction.CREATE;
            Authorization mutation = auth.authorize(moduleId, action, request.headers, parsedBody);
            if (!mutation.ok) return ApiResponse.json(Ports.jsonObject("ok", false, "error", mutation.error), mutation.status);
            return ApiResponse.json(Ports.jsonObject("ok", false, "error", "lili_operation_not_mapped",
                "nextPath", endpoint.nextPath,
                "nextAction", Ports.jsonObject("kind", "UNSUPPORTED_EXECUTOR", "channel", "codex_chat", "browserRequired", false),
                "message", "此步骤执行器尚未接入。请在对话中说明未执行部分并保留分析或草稿；无需返回浏览器。没有修改配置、创建业务记录、启动任务或调用外部服务。"), 501);
        } catch (SyncInputException e) {
            String error = e.status == 401 ? "AUTHENTICATED_DATA_SCOPE_REQUIRED" : "ACCOUNT_ACCESS_DENIED";
            String message = e.status == 401 ? "ERP 登录会话已失效，请重新登录。" : "当前用户不能访问请求的账号数据。";
            return ApiResponse.json(Ports.jsonObject("ok", false, "error", error, "message", message), e.status);
        } catch (RuntimeToolError e) {
            boolean invalid = "INVALID_TOOL_ARGUMENTS".equals(e.code);
            String message = invalid ? "参数不符合当前真实 Schema，请展开参数定义后修正。"
                : "工具不可用、权限不足或 Schema 已改变，请重新发现工具。";
            int status = invalid ? 400 : "RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN".equals(e.code) ? 403 : 409;
            return ApiResponse.json(Ports.jsonObject("ok", false, "error", e.code, "message", message), status);
        } catch (RequestFormatException | IllegalArgumentException e) {
            return ApiResponse.json(Ports.jsonObject("ok", false, "error", "invalid_request",
                "message", "请求格式无效，请检查参数后重试。"), 400);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if ("RESEARCH_REQUEST_REQUIRES_HUMAN_REVIEW".equals(message))
                return ApiResponse.json(Ports.jsonObject("ok", false, "error", message), 409);
            return ApiResponse.json(Ports.jsonObject("ok", false, "error", "lili_unavailable",
                "message", "服务暂不可用，请稍后重试。"), 503);
        }
    }

    private static Object dispatchRuntime(RuntimeTools skills, String name, Map<String, Object> args) {
        requireReviewedMarketCall(name);
        return skills.call(name, args);
    }

    private static String metadataUrl(String origin) {
        try { return new java.net.URL(origin + "/.well-known/oauth-protected-resource/api/lili/mcp").toExternalForm(); }
        catch (Exception e) { return origin + "/.well-known/oauth-protected-resource/api/lili/mcp"; }
    }
}

package com.wimoor.ai.lili;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wimoor.ai.lili.Ports.ApiRequest;
import com.wimoor.ai.lili.Ports.ApiResponse;
import com.wimoor.ai.lili.Ports.Authorization;
import com.wimoor.ai.lili.Ports.McpTransport;
import com.wimoor.ai.lili.Ports.PermissionAction;
import com.wimoor.ai.lili.Ports.ToolDefinition;
import com.wimoor.ai.lili.ResearchTools.ResearchDispatcher;
import com.wimoor.ai.lili.ResearchTools.ResearchRequest;

/**
 * 移植自 src/lib/lili/research-bearer-mcp.ts：Bearer 访问仅限本地 Skill 读取与显式授权的研究命令。
 * 每次权限检查都重新认证（过期/撤销/revision/角色变化立即失败），并全程审计 STARTED/SUCCEEDED/DENIED。
 */
public final class ResearchBearerMcp {

    private ResearchBearerMcp() {}

    public static final class ResearchMcpIdentity {
        public final String id, organizationId, workspaceId, userId;
        public final List<String> tools;
        public final long revision;
        public ResearchMcpIdentity(String id, String organizationId, String workspaceId, String userId,
                                   List<String> tools, long revision) {
            this.id = id; this.organizationId = organizationId; this.workspaceId = workspaceId;
            this.userId = userId; this.tools = tools == null ? new ArrayList<>() : tools; this.revision = revision;
        }
    }

    /** 宿主注入的 Amazon 读取异常，用于 -32004 错误映射。 */
    public static class AmazonReadException extends RuntimeException {
        public final String businessCode;
        public final int status;
        public AmazonReadException(String message, String businessCode, int status) {
            super(message); this.businessCode = businessCode; this.status = status;
        }
    }

    public static final class AuditEvent {
        public final String requestId, method, tool, status;
        public final Map<String, Object> skill;
        AuditEvent(String requestId, String method, String tool, String status, Map<String, Object> skill) {
            this.requestId = requestId; this.method = method; this.tool = tool; this.status = status; this.skill = skill;
        }
        public static AuditEvent of(String requestId, String method, String tool, String status) {
            return new AuditEvent(requestId, method, tool, status, null);
        }
    }

    public interface ResearchBearerDependencies {
        ResearchMcpIdentity authenticate(String authorization, ApiRequest request) throws Exception;
        Authorization permission(ResearchMcpIdentity identity, String moduleId, PermissionAction action, ApiRequest request);
        void audit(ResearchMcpIdentity identity, AuditEvent event);
        McpTransport erp(ResearchMcpIdentity identity) throws Exception;
        Map<String, Object> marketAccess(ResearchMcpIdentity identity, ApiRequest request);
        ResearchDispatcher dispatch();
        /** 可选：由宿主提供真实的 sync-chat 分发器；缺省时研究工具返回未执行审阅态。 */
        default ResearchTools.SyncChatCaller syncCaller() { return null; }
    }

    private static ApiResponse failure(Object id, int status) {
        int code = status == 401 ? -32001 : status == 403 ? -32003 : status == 400 ? -32602 : -32603;
        String message = status == 401 ? "Unauthenticated." : status == 403 ? "Forbidden."
            : status == 400 ? "Invalid request." : "Research request failed.";
        ApiResponse response = ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
            "error", Ports.jsonObject("code", code, "message", message)), status);
        if (status == 401) response = response.withHeader("WWW-Authenticate", "Bearer realm=\"lili-mcp\"");
        return response;
    }

    /** handleResearchBearerMcp：完整 Bearer MCP 分发。 */
    public static ApiResponse handleResearchBearerMcp(ApiRequest request, Map<String, Object> body,
                                                      ResearchBearerDependencies deps) {
        ResearchMcpIdentity identity = null;
        String tool = null;
        String requestId = CryptoSupport.uuid();
        String method = "UNKNOWN";
        Object id = null;
        boolean recorded = false;
        Map<String, Object> skillUsed = null;
        try {
            if (!"POST".equals(request.method)) return ApiResponse.empty(405).withHeader("Allow", "POST");
            String origin = request.header("origin");
            if (origin != null && !origin.equals(request.origin())) return failure(null, 403);
            identity = deps.authenticate(request.header("authorization"), request);
            if (body == null || !"2.0".equals(body.get("jsonrpc")) || !(body.get("method") instanceof String)
                || (body.containsKey("id") && body.get("id") != null && !(body.get("id") instanceof String)
                    && !(body.get("id") instanceof Number))) return failure(null, 400);
            id = body.get("id");
            method = Set.of("initialize", "notifications/initialized", "ping", "tools/list", "tools/call")
                .contains(body.get("method")) ? (String) body.get("method") : "UNKNOWN";
            Map<String, Object> params = JsonSupport.obj(body.get("params"));
            if (body.get("params") != null && params == null) return failure(id, 400);
            if (params == null) params = new LinkedHashMap<>();
            Map<String, Object> args = params;
            tool = "tools/call".equals(method) && args.get("name") instanceof String ? (String) args.get("name") : null;
            boolean needsErp = "tools/list".equals(method)
                || ("tools/call".equals(method) && args.get("name") instanceof String
                    && identity.tools.contains(args.get("name")) && !ResearchGrants.isResearchTool((String) args.get("name")));
            List<Map<String, Object>> erpDefinitions = new ArrayList<>();
            McpTransport erp = null;
            if (needsErp) {
                McpTransport candidate = deps.erp(identity);
                if (candidate != null) {
                    erp = candidate;
                    erpDefinitions = erp.listTools();
                }
            }
            Set<String> known = new java.util.HashSet<>();
            ResearchTools.RESEARCH_MCP_TOOLS.forEach(t -> known.add(t.name));
            PublicResearchTools.PUBLIC_RESEARCH_TOOLS.forEach(t -> known.add(t.name));
            known.add("meta_search_skills"); known.add("meta_load_skill"); known.add("meta_list_workflows");
            SkillMeta.SKILL_META_TOOLS.forEach(t -> known.add(t.name));
            erpDefinitions.forEach(t -> known.add(JsonSupport.str(t.get("name"))));
            if (tool != null && !known.contains(tool)) tool = "UNKNOWN";
            final String toolName = tool;
            final String mtd = method;
            final ResearchMcpIdentity currentIdentity = identity;
            deps.audit(identity, AuditEvent.of(requestId, method, tool, "STARTED"));
            if (identity.tools.isEmpty()) return failure(id, 403);

            java.util.function.BiFunction<String, PermissionAction, Authorization> authorize = (moduleId, action) -> {
                ResearchMcpIdentity current;
                try { current = deps.authenticate(request.header("authorization"), request); }
                catch (Exception e) { return Authorization.deny(403, "Forbidden."); }
                boolean changed = !current.id.equals(currentIdentity.id) || current.revision != currentIdentity.revision
                    || !current.userId.equals(currentIdentity.userId)
                    || !current.organizationId.equals(currentIdentity.organizationId)
                    || !current.workspaceId.equals(currentIdentity.workspaceId);
                boolean revoked = toolName != null && ResearchGrants.isResearchTool(toolName) && !current.tools.contains(toolName);
                boolean approvalMissing = action == PermissionAction.APPROVE
                    && current.tools.stream().noneMatch(n -> ResearchGrants.isResearchTool(n)
                        && ResearchGrants.RESEARCH_TOOL_ACTIONS.get(n) == PermissionAction.APPROVE
                        && ResearchGrants.liliToolModule(n).equals(moduleId)
                        && (n.equals(toolName) || "tools/list".equals(mtd)));
                if (changed || revoked || approvalMissing) return Authorization.deny(403, "Forbidden.");
                Authorization permission = deps.permission(current, moduleId, action, request);
                if (permission.ok && (permission.userId == null || !permission.userId.equals(current.userId)
                    || permission.organizationId == null || !permission.organizationId.equals(current.organizationId)))
                    return Authorization.deny(403, "Forbidden.");
                return permission;
            };
            Authorization verified = authorize.apply("amazon_account", PermissionAction.VIEW);
            if (!verified.ok) return failure(id, 403);
            RuntimeTools skills = RuntimeTools.createRuntimeTools(new ArrayList<>());
            Object result;
            if ("initialize".equals(method)) {
                if (!Set.of("2024-11-05", "2025-03-26", "2025-06-18").contains(String.valueOf(args.get("protocolVersion"))))
                    return failure(id, 400);
                result = Ports.jsonObject("protocolVersion", args.get("protocolVersion"),
                    "serverInfo", Ports.jsonObject("name", "lili-mcp", "version", SkillMeta.LILI_VERSION),
                    "capabilities", Ports.jsonObject("tools", new LinkedHashMap<>()),
                    "instructions", SkillMeta.LILI_INSTRUCTIONS);
            } else if ("notifications/initialized".equals(method)) {
                if (body.containsKey("id")) return failure(id, 400);
                deps.audit(identity, AuditEvent.of(requestId, method, tool, "SUCCEEDED"));
                recorded = true;
                return ApiResponse.empty(202);
            } else if ("ping".equals(method)) {
                result = new LinkedHashMap<>();
            } else if ("tools/list".equals(method)) {
                List<Map<String, Object>> granted = new ArrayList<>();
                for (ToolDefinition definition : ResearchTools.RESEARCH_MCP_TOOLS) {
                    if (!ResearchGrants.isResearchTool(definition.name) || !identity.tools.contains(definition.name)) continue;
                    PermissionAction action = ResearchGrants.RESEARCH_TOOL_ACTIONS.get(definition.name);
                    if (!authorize.apply(ResearchGrants.liliToolModule(definition.name), action).ok) continue;
                    Map<String, Object> schema = JsonSupport.copy(definition.inputSchema);
                    Map<String, Object> props = JsonSupport.obj(schema.get("properties"));
                    if (props == null) { props = new LinkedHashMap<>(); schema.put("properties", props); }
                    props.put("workspaceId", Ports.jsonObject("type", "string", "const", identity.workspaceId));
                    granted.add(Ports.jsonObject("name", definition.name, "description", definition.description,
                        "inputSchema", schema, "annotations", definition.annotations));
                }
                List<Map<String, Object>> listed = new ArrayList<>();
                skills.discover().tools.forEach(t -> listed.add(t.toMap()));
                PublicResearchTools.PUBLIC_RESEARCH_TOOLS.forEach(t -> listed.add(t.toMap()));
                listed.addAll(granted);
                listed.addAll(erpDefinitions);
                result = Ports.jsonObject("tools", listed);
            } else if ("tools/call".equals(method)) {
                if (tool == null || "UNKNOWN".equals(tool)) return failure(id, 403);
                Map<String, Object> input = JsonSupport.obj(args.get("arguments"));
                if (args.get("arguments") != null && input == null) return failure(id, 400);
                if (input == null) input = new LinkedHashMap<>();
                Object output;
                boolean erpTool = erpDefinitions.stream().anyMatch(t -> toolName.equals(JsonSupport.str(t.get("name"))));
                boolean metaTool = SkillMeta.SKILL_META_TOOLS.stream().anyMatch(t -> t.name.equals(toolName));
                boolean publicTool = PublicResearchTools.PUBLIC_RESEARCH_TOOLS.stream().anyMatch(t -> t.name.equals(toolName));
                if (erpTool) {
                    ResearchMcpIdentity current = deps.authenticate(request.header("authorization"), request);
                    if (!current.id.equals(identity.id) || current.revision != identity.revision) return failure(id, 403);
                    output = erp.callTool(tool, input, null);
                } else if (metaTool) {
                    ToolDefinition def = SkillMeta.SKILL_META_TOOLS.stream().filter(t -> t.name.equals(toolName)).findFirst().get();
                    SchemaValidator.validate(def.inputSchema, input);
                    Map<String, Object> market = "meta_get_market_access".equals(tool) ? deps.marketAccess(identity, request) : null;
                    output = market != null ? market : SkillMeta.callSkillMeta(tool, input, true);
                } else if (ResearchGrants.isResearchTool(tool)) {
                    if (!identity.tools.contains(tool)) return failure(id, 403);
                    Authorization permission = authorize.apply(ResearchGrants.liliToolModule(tool),
                        ResearchGrants.RESEARCH_TOOL_ACTIONS.get(tool));
                    if (!permission.ok) return failure(id, 403);
                    Map<String, Object> command = new LinkedHashMap<>(input);
                    if (command.get("workspaceId") != null && !identity.workspaceId.equals(command.get("workspaceId"))
                        || command.get("erpCredentialId") != null && !identity.id.equals(command.get("erpCredentialId")))
                        return failure(id, 403);
                    command.put("workspaceId", identity.workspaceId);
                    if ("research_start".equals(tool)) command.put("erpCredentialId", identity.id);
                    ResearchDispatcher dispatcher = deps.dispatch() == null ? (r, runId) -> ApiResponse.json(
                        Ports.jsonObject("ok", false, "error", "RESEARCH_DISPATCH_UNAVAILABLE"), 503) : deps.dispatch();
                    output = ResearchTools.callResearchMcpTool(request.url, tool, command, deps.syncCaller(), dispatcher);
                    absolutizeLinks(output, request.url);
                } else if (publicTool) {
                    output = PublicResearchTools.callPublicResearchTool(tool, input);
                } else {
                    output = skills.call(tool, input);
                }
                Map<String, Object> out = JsonSupport.obj(output);
                if (("meta_load_skill".equals(tool) || "meta_get_skill_manifest".equals(tool)) && out != null && out.get("manifest") != null) {
                    Map<String, Object> manifest = JsonSupport.obj(out.get("manifest"));
                    skillUsed = Ports.jsonObject("id", manifest.get("skill_id"), "sha256", manifest.get("sha256"),
                        "version", manifest.get("version"));
                }
                boolean isError = out != null && Boolean.FALSE.equals(out.get("ok"));
                deps.audit(identity, new AuditEvent(requestId, method, tool, isError ? "DENIED" : "SUCCEEDED", skillUsed));
                recorded = true;
                return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                    "result", Ports.jsonObject("content", List.of(Ports.jsonObject("type", "text", "text", JsonSupport.stringify(output))),
                        "isError", isError)));
            } else {
                return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                    "error", Ports.jsonObject("code", -32601, "message", "Method not found.")));
            }
            deps.audit(identity, AuditEvent.of(requestId, method, tool, "SUCCEEDED"));
            recorded = true;
            return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id, "result", result));
        } catch (AmazonReadException e) {
            return ApiResponse.json(Ports.jsonObject("jsonrpc", "2.0", "id", id,
                "error", Ports.jsonObject("code", -32004, "message", e.getMessage(),
                    "data", Ports.jsonObject("businessCode", e.businessCode, "requestId", requestId))), e.status);
        } catch (Exception e) {
            int status = 503;
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.matches("^(SCHEMA_|CREDENTIAL).*")) status = 400;
            return failure(id, status);
        } finally {
            if (identity != null && !recorded) deps.audit(identity, AuditEvent.of(requestId, method, tool, "DENIED"));
        }
    }

    /** 外部客户端需要可导航的人工审核链接；浏览器路由保留相对链接。 */
    @SuppressWarnings("unchecked")
    private static void absolutizeLinks(Object output, String baseUrl) {
        Map<String, Object> m = JsonSupport.obj(output);
        if (m == null) return;
        if (m.get("reviewUrl") instanceof String) m.put("reviewUrl", absolute(baseUrl, (String) m.get("reviewUrl")));
        if (m.get("downloadUrl") instanceof String) m.put("downloadUrl", absolute(baseUrl, (String) m.get("downloadUrl")));
        Object reports = m.get("stageReports");
        if (reports instanceof List) {
            for (Object item : (List<Object>) reports) {
                Map<String, Object> row = JsonSupport.obj(item);
                if (row != null && row.get("url") instanceof String) row.put("url", absolute(baseUrl, (String) row.get("url")));
            }
        }
    }

    private static String absolute(String base, String relative) {
        try { return new URL(new URL(base), relative).toExternalForm(); }
        catch (Exception e) { return relative; }
    }
}

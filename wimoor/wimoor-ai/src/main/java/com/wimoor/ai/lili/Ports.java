package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部依赖的端口（接口）集合：对应 api.ts / runtime-tools.ts / tool-config-api.ts /
 * research-bearer-mcp.ts / sync-chat-tools.ts 中的函数参数注入点。
 * 业务规则（参数校验、路由分发、工具注册、积分判定）留在各移植类中；
 * HTTP / DB / LLM / MCP 运行时由宿主通过这里的实现注入，模块内提供内存桩实现。
 */
public final class Ports {

    private Ports() {}

    public enum PermissionAction {
        VIEW("view"), CREATE("create"), EDIT("edit"), APPROVE("approve"), EXPORT("export");
        private final String wire;
        PermissionAction(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static PermissionAction fromWire(String value) {
            for (PermissionAction a : values()) if (a.wire.equals(value)) return a;
            throw new IllegalArgumentException("UNKNOWN_PERMISSION_ACTION");
        }
    }

    /** api.ts 的 Authorization 联合类型。 */
    public static final class Authorization {
        public final boolean ok;
        public final String userId;
        public final String organizationId;
        public final int status;
        public final String error;

        private Authorization(boolean ok, String userId, String organizationId, int status, String error) {
            this.ok = ok; this.userId = userId; this.organizationId = organizationId;
            this.status = status; this.error = error;
        }
        public static Authorization allow(String userId, String organizationId) {
            return new Authorization(true, userId, organizationId, 200, null);
        }
        public static Authorization deny(int status, String error) {
            return new Authorization(false, null, null, status, error);
        }
    }

    /** api.ts 的 LiliAuthorize。 */
    public interface LiliAuthorize {
        Authorization authorize(String moduleId, PermissionAction action, Map<String, String> headers, Map<String, Object> body);
    }

    /** selection-research/types.ts 的 Transport。 */
    public interface McpTransport {
        List<Map<String, Object>> listTools() throws Exception;
        Object callTool(String name, Map<String, Object> args, Map<String, Object> expectedSchema) throws Exception;
    }

    /** 内存/桩 MCP 传输：注册表为空，调用直接拒绝，用于无宿主依赖的可运行骨架。 */
    public static final class StubMcpTransport implements McpTransport {
        private final List<Map<String, Object>> tools;
        public StubMcpTransport() { this(new ArrayList<>()); }
        public StubMcpTransport(List<Map<String, Object>> tools) { this.tools = tools; }
        @Override public List<Map<String, Object>> listTools() { return new ArrayList<>(tools); }
        @Override public Object callTool(String name, Map<String, Object> args, Map<String, Object> schema) {
            throw new ToolNotDispatchedException("RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN");
        }
    }

    /** runtime-tools.ts 的 ToolExecutor / call 语义。 */
    public interface ToolExecutor {
        Object execute(String name, Map<String, Object> args) throws Exception;
    }

    /** selection-research/types.ts 的 ToolNotDispatchedError。 */
    public static class ToolNotDispatchedException extends RuntimeException {
        public final String code;
        public ToolNotDispatchedException(String code) { super(code); this.code = code; }
    }

    /** sync-chat-tools.ts 的 ChatTransport（inspect / approve / apply 之外的人工对话通道）。 */
    public interface ChatTransport {
        Object dispatch(String channel, Map<String, Object> message) throws Exception;
    }

    /** oauth.ts / tool-config-api.ts 的 fetch 抽象。 */
    public interface HttpTransport {
        HttpResponse send(String method, String url, Map<String, String> headers, String body) throws Exception;
    }

    public static final class HttpResponse {
        public final int status;
        public final Map<String, String> headers;
        public final String body;
        public HttpResponse(int status, Map<String, String> headers, String body) {
            this.status = status; this.headers = headers == null ? new LinkedHashMap<>() : headers; this.body = body;
        }
        public boolean ok() { return status >= 200 && status < 300; }
    }

    /** tool-config-api.ts 的连接探测注入点。 */
    public interface ConnectionProbe {
        void probe(Map<String, Object> settings) throws Exception;
    }

    /** api.ts 返回的 Response 抽象。 */
    public static final class ApiResponse {
        public final int status;
        public final Map<String, String> headers;
        public final Object body;
        public ApiResponse(int status, Map<String, String> headers, Object body) {
            this.status = status; this.headers = headers == null ? new LinkedHashMap<>() : headers; this.body = body;
        }
        public static ApiResponse json(Object body) { return json(body, 200); }
        public static ApiResponse json(Object body, int status) { return new ApiResponse(status, securityHeaders(), body); }
        public static ApiResponse empty(int status) { return new ApiResponse(status, securityHeaders(), null); }
        private static Map<String, String> securityHeaders() {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Cache-Control", "no-store");
            h.put("X-Content-Type-Options", "nosniff");
            return h;
        }
        public ApiResponse withHeader(String key, String value) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(key, value);
            return new ApiResponse(status, h, body);
        }
    }

    /** api.ts / research-bearer-mcp.ts 的入站请求抽象。 */
    public static final class ApiRequest {
        public final String method;
        public final String url;
        public final Map<String, String> headers;
        public final Map<String, Object> body;
        public ApiRequest(String method, String url, Map<String, String> headers, Map<String, Object> body) {
            this.method = method; this.url = url;
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
            this.body = body;
        }
        public String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            return null;
        }
        public String origin() {
            String p = url == null ? "" : url;
            int idx = p.indexOf("://");
            if (idx < 0) return "";
            int end = p.indexOf('/', idx + 3);
            return end < 0 ? p : p.substring(0, end);
        }
    }

    /** 审计日志写入端口（tool-config-server.ts / research-bearer-mcp.ts / device-pairing.ts 的 auditLog.create）。 */
    public interface AuditSink {
        void record(String organizationId, String userId, String action, String entityType, String entityId,
                    Map<String, Object> metadata);
    }

    /** 空审计实现，供无宿主环境直接运行。 */
    public static final AuditSink NOOP_AUDIT = (organizationId, userId, action, entityType, entityId, metadata) -> { };

    /** 通用工具定义（selection-research/types.ts 的 ToolDefinition）。 */
    public static final class ToolDefinition {
        public final String name;
        public final String description;
        public final Map<String, Object> inputSchema;
        public final Map<String, Object> annotations;
        public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
            this(name, description, inputSchema, null);
        }
        public ToolDefinition(String name, String description, Map<String, Object> inputSchema, Map<String, Object> annotations) {
            this.name = name; this.description = description; this.inputSchema = inputSchema; this.annotations = annotations;
        }
        public boolean readOnly() {
            return annotations != null && Boolean.TRUE.equals(annotations.get("readOnlyHint"));
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("description", description);
            m.put("inputSchema", JsonSupport.copy(inputSchema));
            if (annotations != null) m.put("annotations", JsonSupport.copy(annotations));
            return m;
        }
    }

    public static Map<String, Object> jsonObject(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static Map<String, Object> annotations(boolean readOnly) {
        return jsonObject("readOnlyHint", readOnly);
    }
}

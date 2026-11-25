package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lili /api/mcp JSON-RPC 传输（移植自 src/lib/selection-research/lili-transport.ts）。
 * 不安装或修改 MCP 服务；端点、握手、分页目录、SSE 事件与凭证回显检查都在这里真实实现。
 */
public final class LiliTransport implements ResearchTransport {

    public static final class HttpRequest {
        public String url;
        public String method;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String body;
    }

    public static final class HttpResponse {
        public int status;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String body;
    }

    /** 宿主提供实际 HTTP 客户端；本类只构造请求与解释响应。 */
    public interface HttpFetcher {
        HttpResponse fetch(HttpRequest request);
    }

    private static final int MAX_RESPONSE_BYTES = 8_000_000;
    private static final Set<String> PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18");
    private final String endpoint;
    private final String authorization;
    private final HttpFetcher fetcher;
    private final long timeoutMs;
    private String session;
    private String protocol;
    private boolean initialized;

    public LiliTransport(String endpoint, String authorization, HttpFetcher fetcher, long timeoutMs) {
        validateEndpoint(endpoint);
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.fetcher = fetcher;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 30_000L;
    }

    private static void validateEndpoint(String endpoint) {
        try {
            URI url = new URI(endpoint);
            String host = url.getHost();
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
            if (url.getUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                    || !"https".equalsIgnoreCase(url.getScheme()) && !(local && "http".equalsIgnoreCase(url.getScheme())))
                throw new ResearchError("UNSAFE_LILI_ENDPOINT");
        } catch (java.net.URISyntaxException e) {
            throw new ResearchError("UNSAFE_LILI_ENDPOINT", e);
        }
    }

    /** 在同一文本中查找 jsonrpc=2.0 且 id 匹配的完整回包；兼容多行 SSE 事件。 */
    public static ObjectNode parseRpc(String text, String id) {
        List<String> candidates = new ArrayList<>();
        candidates.add(text);
        for (String event : text.split("\\r?\\n\\r?\\n", -1)) {
            StringBuilder sb = new StringBuilder();
            for (String line : event.split("\\r?\\n")) {
                if (line.startsWith("data:")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line.substring(5).stripLeading());
                }
            }
            candidates.add(sb.toString());
        }
        for (String candidate : candidates) {
            try {
                JsonNode value = ResearchSchema.MAPPER.readTree(candidate);
                if (value != null && value.isObject() && "2.0".equals(value.path("jsonrpc").asText())
                        && value.path("id").isTextual() && id.equals(value.path("id").asText()))
                    return (ObjectNode) value;
            } catch (Exception ignored) {
                // keepalive 或非目标事件，继续查找
            }
        }
        throw new ResearchError("LILI_MATCHING_RPC_RESPONSE_MISSING");
    }

    private ObjectNode request(String method, Map<String, Object> params, boolean notification) {
        String id = UUID.randomUUID().toString();
        HttpRequest req = new HttpRequest();
        req.url = endpoint;
        req.method = "POST";
        req.headers.put("content-type", "application/json");
        req.headers.put("accept", "application/json, text/event-stream");
        if (authorization != null) req.headers.put("authorization", authorization);
        if (session != null) req.headers.put("mcp-session-id", session);
        if (protocol != null) req.headers.put("mcp-protocol-version", protocol);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (!notification) body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        try {
            req.body = ResearchSchema.MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new ResearchError("LILI_REQUEST_INVALID", e);
        }
        HttpResponse response = Bounded.call(() -> fetcher.fetch(req), timeoutMs, "LILI_HTTP_TIMEOUT");
        if (response.status < 200 || response.status >= 300) throw new ResearchError("LILI_HTTP_" + response.status);
        if ("initialize".equals(method)) {
            String header = response.headers.entrySet().stream()
                    .filter(e -> "mcp-session-id".equalsIgnoreCase(e.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            session = header;
        }
        if (notification) return ResearchSchema.MAPPER.createObjectNode();
        String text = response.body == null ? "" : response.body;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES)
            throw new ResearchError("LILI_RESPONSE_TOO_LARGE");
        String secret = authorization == null ? null : authorization.replaceFirst("(?i)^Bearer\\s+", "");
        if (secret != null && !secret.isEmpty() && text.contains(secret)) throw new ResearchError("LILI_SECRET_ECHO_REJECTED");
        return parseRpc(text, id);
    }

    private void initialize() {
        if (initialized) return;
        ObjectNode envelope = request("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "erp-selection-research", "version", "1")), false);
        JsonNode result = envelope.path("result");
        String version = result.path("protocolVersion").asText();
        if (!envelope.path("error").isMissingNode() || !result.isObject() || !PROTOCOLS.contains(version))
            throw new ResearchError("LILI_INITIALIZE_FAILED");
        protocol = version;
        request("notifications/initialized", Map.of(), true);
        initialized = true;
    }

    @Override
    public List<ResearchTypes.ToolDefinition> listTools() {
        initialize();
        List<ResearchTypes.ToolDefinition> tools = new ArrayList<>();
        Set<String> cursors = new java.util.HashSet<>();
        String cursor = null;
        do {
            ObjectNode envelope = request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor), false);
            JsonNode result = envelope.path("result");
            if (!envelope.path("error").isMissingNode() || !result.isObject() || !result.path("tools").isArray())
                throw new ResearchError("LILI_REGISTRY_UNAVAILABLE");
            for (JsonNode value : result.path("tools")) {
                if (!value.isObject() || !value.path("name").isTextual() || !value.path("inputSchema").isObject())
                    throw new ResearchError("LILI_INVALID_TOOL_DEFINITION");
                String name = value.path("name").asText();
                if (tools.stream().anyMatch(t -> t.name.equals(name)) || tools.size() >= 1000)
                    throw new ResearchError("LILI_INVALID_TOOL_DEFINITION");
                ResearchTypes.ToolDefinition tool = new ResearchTypes.ToolDefinition(name, value.path("inputSchema").deepCopy());
                if (value.has("description")) tool.description = value.path("description").asText();
                if (value.path("annotations").has("readOnlyHint"))
                    tool.readOnlyHint = value.path("annotations").path("readOnlyHint").asBoolean();
                tools.add(tool);
            }
            JsonNode next = result.path("nextCursor");
            cursor = next.isTextual() && !next.asText().isEmpty() ? next.asText() : null;
            if (cursor != null) {
                if (cursors.contains(cursor) || cursors.size() > 100) throw new ResearchError("LILI_REGISTRY_CURSOR_LOOP");
                cursors.add(cursor);
            }
        } while (cursor != null);
        return tools;
    }

    @Override
    public JsonNode callTool(String name, JsonNode args, JsonNode expectedSchema) {
        initialize();
        return request("tools/call", Map.of("name", name, "arguments", args == null ? Map.of() : args), false);
    }
}

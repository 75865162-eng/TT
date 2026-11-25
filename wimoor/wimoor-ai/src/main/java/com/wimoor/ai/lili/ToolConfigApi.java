package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wimoor.ai.lili.Ports.ApiResponse;
import com.wimoor.ai.lili.Ports.ConnectionProbe;
import com.wimoor.ai.lili.Ports.HttpResponse;
import com.wimoor.ai.lili.Ports.HttpTransport;
import com.wimoor.ai.lili.ToolConfig.ToolConfigInputError;
import com.wimoor.ai.lili.ToolConfig.XiyouSettings;
import com.wimoor.ai.lili.ToolConfigServer.ToolConfigScope;
import com.wimoor.ai.lili.ToolConfigServer.ToolConfigStore;

/**
 * 移植自 src/lib/lili/tool-config-api.ts：配置读写路由 + 连接探测。
 * 探测只发 MCP initialize，不执行工具调用、工作流、跳转或业务数据查询。
 */
public final class ToolConfigApi {

    private ToolConfigApi() {}

    public static class WorkspaceScopeException extends RuntimeException {
        public final int status;
        WorkspaceScopeException(String message, int status) { super(message); this.status = status; }
    }

    /** workspaceScopeFromRequest 的等价：workspaceId 来自 JSON body 或 x-workspace-id。 */
    public static ToolConfigScope workspaceScope(Map<String, Object> body, Map<String, String> headers,
                                                  String organizationId, String userId) {
        String workspaceId = body == null ? null : JsonSupport.str(body.get("workspaceId"));
        if (workspaceId == null || workspaceId.isEmpty()) workspaceId = headers.get("x-workspace-id");
        if (workspaceId == null || workspaceId.isEmpty()) throw new WorkspaceScopeException("缺少工作区范围。", 400);
        return new ToolConfigScope(organizationId, workspaceId, userId);
    }

    public static final class ToolConfigDependencies {
        public final ToolConfigStore store;
        public final ConnectionProbe probe;
        public ToolConfigDependencies(ToolConfigStore store, ConnectionProbe probe) {
            this.store = store; this.probe = probe;
        }
    }

    /** probeXiyouConnection：解析 JSON 或 SSE 帧，严格校验 MCP initialize 响应。 */
    public static void probeXiyouConnection(XiyouSettings settings, HttpTransport transport) throws Exception {
        if (isEmpty(settings.mcpToken) || !ToolConfig.XIYOU_ENDPOINT.equals(settings.mcpUrl))
            throw new ToolConfigInputError("请先保存有效的西柚 MCP Token。");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Authorization", "Bearer " + settings.mcpToken);
        String body = JsonSupport.stringify(Ports.jsonObject(
            "jsonrpc", "2.0", "id", "lili-connection-test", "method", "initialize",
            "params", Ports.jsonObject("protocolVersion", "2025-03-26", "capabilities", new LinkedHashMap<>(),
                "clientInfo", Ports.jsonObject("name", "erp-lili", "version", "1.0.0"))));
        HttpResponse response = transport.send("POST", ToolConfig.XIYOU_ENDPOINT, headers, body);
        if (!response.ok()) throw new IllegalStateException("Provider HTTP failure");
        String text = response.body == null ? "" : response.body;
        if (text.length() > 65_536) throw new IllegalStateException("Provider response too large");
        Map<String, Object> envelope = null;
        if (response.headers.containsKey("content-type") && response.headers.get("content-type").contains("text/event-stream")) {
            String[] frames = text.replace("\r\n", "\n").split("\n\n", -1);
            for (String frame : frames) {
                StringBuilder data = new StringBuilder();
                for (String line : frame.split("\n")) if (line.startsWith("data:")) data.append(line.substring(5).trim());
                Map<String, Object> candidate = tryParse(data.toString());
                if (candidate != null && "lili-connection-test".equals(candidate.get("id"))) { envelope = candidate; break; }
            }
        } else {
            Map<String, Object> candidate = tryParse(text);
            if (candidate != null && "lili-connection-test".equals(candidate.get("id"))) envelope = candidate;
        }
        Map<String, Object> result = JsonSupport.obj(envelope == null ? null : envelope.get("result"));
        if (envelope == null || !"2.0".equals(envelope.get("jsonrpc")) || envelope.containsKey("error")
            || result == null || !(result.get("protocolVersion") instanceof String)
            || JsonSupport.obj(result.get("serverInfo")) == null)
            throw new IllegalStateException("Invalid MCP initialize response");
    }

    private static Map<String, Object> tryParse(String text) {
        try { return JsonSupport.parseObject(text); }
        catch (RuntimeException e) { return null; }
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    /** handleToolConfiguration：GET 读、PUT 存、POST test 探测。 */
    public static ApiResponse handleToolConfiguration(String method, String key, Map<String, Object> body,
                                                      String userId, String organizationId,
                                                      Map<String, String> headers, ToolConfigDependencies deps) {
        try {
            if (!"GET".equals(method) && (body == null || !"xiyou".equals(body.get("id"))))
                return ApiResponse.json(Ports.jsonObject("message", "请选择西柚配置；卖家精灵沿用主系统已有配置接口。"), 400);
            ToolConfigStore store = deps != null && deps.store != null ? deps.store
                : new ToolConfigServer.InMemoryToolConfigStore(new ToolConfigServer.AesGcmSealer(new byte[32]), Ports.NOOP_AUDIT);
            ToolConfigScope scope = workspaceScope(body, headers, organizationId, userId);
            if ("GET".equals(method))
                return ApiResponse.json(Ports.jsonObject("settings",
                    ToolConfig.publicXiyouSettings(store.read(scope)).toMap()), 200);
            if ("tool-config".equals(key) && "PUT".equals(method)) {
                XiyouSettings settings = store.save(scope, body == null ? null : JsonSupport.obj(body.get("settings")));
                return ApiResponse.json(Ports.jsonObject("settings", ToolConfig.publicXiyouSettings(settings).toMap(),
                    "message", "已保存西柚配置；尚未验证连接。"), 200);
            }
            XiyouSettings settings = store.read(scope);
            if (!settings.enabled || isEmpty(settings.mcpToken))
                return ApiResponse.json(Ports.jsonObject("message", "请先保存并启用西柚 MCP 配置。"), 400);
            if (deps != null && deps.probe != null) deps.probe.probe(settings.toMap());
            else probeXiyouConnection(settings, (m, u, h, b) -> { throw new IllegalStateException("No transport"); });
            return ApiResponse.json(Ports.jsonObject("ok", true,
                "message", "西柚 MCP 初始化已响应；未执行业务工具或工作流。"), 200);
        } catch (WorkspaceScopeException e) {
            return ApiResponse.json(Ports.jsonObject("message", e.getMessage()), e.status);
        } catch (ToolConfigInputError e) {
            return ApiResponse.json(Ports.jsonObject("message", e.getMessage()), 400);
        } catch (Exception e) {
            String message = "tool-config/test".equals(key)
                ? "西柚连接测试失败，请检查 Token、网络及服务状态。"
                : "西柚配置读取或保存失败，请检查数据库及服务端加密密钥后重试。";
            return ApiResponse.json(Ports.jsonObject("message", message), 503);
        }
    }
}

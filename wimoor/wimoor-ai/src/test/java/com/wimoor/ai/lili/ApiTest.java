package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wimoor.ai.lili.Ports.ApiRequest;
import com.wimoor.ai.lili.Ports.ApiResponse;
import com.wimoor.ai.lili.Ports.Authorization;
import com.wimoor.ai.lili.Ports.LiliAuthorize;

/** 路由分发、请求体限长与 MCP 协议校验的确定性测试（移植自 api.ts）。 */
class ApiTest {

    private static final LiliAuthorize ALLOW = (m, a, h, b) -> Authorization.allow("user-1", "org-1");

    private static ApiRequest req(String method, String path, Map<String, Object> body, Map<String, String> headers) {
        return new ApiRequest(method, "https://erp.example.com" + path, headers, body);
    }

    private static ApiRequest req(String method, String path, Map<String, Object> body) {
        return req(method, path, body, new LinkedHashMap<>());
    }

    @Test
    void endpointKeyNormalizesDynamicSegments() {
        assertEquals("approvals/[id]", Api.endpointKey(List.of("approvals", "req-1")));
        assertEquals("connections/[id]", Api.endpointKey(List.of("connections", "c-1")));
        assertEquals("tool-config/mappings", Api.endpointKey(List.of("tool-config", "mappings")));
        assertEquals("mcp", Api.endpointKey(List.of("mcp")));
    }

    @Test
    void boundedJsonEnforcesContentTypeAndSize() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, Api.boundedJson("application/json; charset=utf-8", body).size());
        assertThrows(Api.RequestFormatException.class, () -> Api.boundedJson("text/plain", body));
        assertThrows(Api.RequestFormatException.class, () -> Api.boundedJson("application/json", null));
        assertThrows(Api.RequestFormatException.class,
            () -> Api.boundedJson("application/json", new byte[16_385]));
        assertThrows(Api.RequestFormatException.class,
            () -> Api.boundedJson("application/json", "[1,2]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void healthReportsShippedDefinitionCounts() {
        ApiResponse response = Api.handleLiliRequest(req("GET", "/api/lili/health", null),
            List.of("health"), ALLOW, new Api.Dependencies());
        assertEquals(200, response.status);
        Map<String, Object> body = JsonSupport.obj(response.body);
        assertEquals(true, body.get("ok"));
        assertEquals(false, body.get("businessExecutionReady"));
        assertEquals(116, body.get("skills"));
        assertEquals(107, body.get("tools"));
        assertEquals(13, body.get("workflows"));
    }

    @Test
    void unsupportedMethodAndDeniedPermissionAreMapped() {
        assertEquals(405, Api.handleLiliRequest(req("POST", "/api/lili/health", Map.of()),
            List.of("health"), ALLOW, new Api.Dependencies()).status);
        LiliAuthorize deny = (m, a, h, b) -> Authorization.deny(503, "lili_unavailable");
        ApiResponse health = Api.handleLiliRequest(req("GET", "/api/lili/health", null),
            List.of("health"), deny, new Api.Dependencies());
        assertEquals(503, health.status);
    }

    @Test
    void mcpProtocolVersionIsValidatedAndInitializeIsPublic() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("mcp-protocol-version", "1999-01-01");
        assertEquals(400, Api.handleLiliRequest(req("GET", "/api/lili/mcp", null, headers),
            List.of("mcp"), ALLOW, new Api.Dependencies()).status);
        ApiResponse init = Api.handleLiliRequest(req("POST", "/api/lili/mcp", Ports.jsonObject(
            "jsonrpc", "2.0", "id", 1, "method", "initialize",
            "params", Ports.jsonObject("protocolVersion", "2025-06-18"))),
            List.of("mcp"), ALLOW, new Api.Dependencies());
        assertEquals(200, init.status);
        Map<String, Object> result = JsonSupport.obj(JsonSupport.obj(init.body).get("result"));
        assertEquals("lili-mcp", JsonSupport.obj(result.get("serverInfo")).get("name"));
        assertTrue(result.containsKey("instructions"));
    }

    @Test
    void preferencesAndMappingsAreRouted() {
        ApiResponse badLocale = Api.handleLiliRequest(req("POST", "/api/lili/preferences", Map.of("locale", "fr")),
            List.of("preferences"), ALLOW, new Api.Dependencies());
        assertEquals(400, badLocale.status);
        ApiResponse ok = Api.handleLiliRequest(req("POST", "/api/lili/preferences", Map.of("locale", "zh")),
            List.of("preferences"), ALLOW, new Api.Dependencies());
        assertEquals(200, ok.status);
        assertTrue(ok.headers.get("Set-Cookie").contains("lili_locale=zh"));
        ApiResponse mappings = Api.handleLiliRequest(req("GET", "/api/lili/tool-config/mappings", null),
            List.of("tool-config", "mappings"), ALLOW, new Api.Dependencies());
        assertEquals(200, mappings.status);
        assertEquals(107, ((List<?>) JsonSupport.obj(mappings.body).get("tools")).size());
    }

    @Test
    void codexBundleProducesZipAndUnmappedOperationReturns501() {
        ApiResponse bundle = Api.handleLiliRequest(req("GET", "/api/lili/codex-bundle", null),
            List.of("codex-bundle"), ALLOW, new Api.Dependencies());
        assertEquals(200, bundle.status);
        byte[] zip = (byte[]) bundle.body;
        assertEquals('P', zip[0]);
        assertEquals('K', zip[1]);
        assertTrue(zip.length > 1000);
        ApiResponse unmapped = Api.handleLiliRequest(req("POST", "/api/lili/credits", Map.of()),
            List.of("credits"), ALLOW, new Api.Dependencies());
        assertEquals(501, unmapped.status);
        assertEquals("UNSUPPORTED_EXECUTOR",
            JsonSupport.obj(JsonSupport.obj(unmapped.body).get("nextAction")).get("kind"));
    }
}

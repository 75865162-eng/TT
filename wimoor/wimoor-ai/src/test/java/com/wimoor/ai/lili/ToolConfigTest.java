package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wimoor.ai.lili.ToolConfig.ToolConfigInputError;
import com.wimoor.ai.lili.ToolConfig.XiyouSettings;
import com.wimoor.ai.lili.ToolConfigServer.AesGcmSealer;
import com.wimoor.ai.lili.ToolConfigServer.ToolConfigScope;
import com.wimoor.ai.lili.ToolConfigServer.EncryptedConfig;

/** 工具配置合并校验、脱敏输出与 AES-GCM 加密存储的确定性测试。 */
class ToolConfigTest {

    private static final ToolConfigScope SCOPE = new ToolConfigScope("org-1", "ws-1", "user-1");
    private static final AesGcmSealer SEALER = new AesGcmSealer(new byte[32]);

    private static Map<String, Object> patch(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void mergeAcceptsValidTokenAndEnabled() {
        XiyouSettings settings = ToolConfig.mergeXiyouSettings(patch(
            "mcpToken", "real-token-123", "clientId", "cid", "enabled", true));
        assertTrue(settings.enabled);
        assertEquals("real-token-123", settings.mcpToken);
        Map<String, Object> pub = ToolConfig.publicXiyouSettings(settings).toMap();
        assertEquals(true, pub.get("hasMcpToken"));
        assertEquals(true, pub.get("configured"));
        assertFalse(pub.containsKey("mcpToken"));
        assertFalse(pub.containsKey("clientSecret"));
    }

    @Test
    void mergeRejectsUnknownFieldsAndWrongEndpoints() {
        ToolConfigInputError unknown = assertThrows(ToolConfigInputError.class,
            () -> ToolConfig.mergeXiyouSettings(patch("surprise", "x")));
        assertEquals("配置包含未知字段。", unknown.getMessage());
        assertEquals("西柚 MCP 地址须为 https://mcp.xydc.com/mcp，Token 请单独填写。",
            assertThrows(ToolConfigInputError.class,
                () -> ToolConfig.mergeXiyouSettings(patch("mcpUrl", "https://evil.example/mcp"))).getMessage());
        assertThrows(ToolConfigInputError.class,
            () -> ToolConfig.mergeXiyouSettings(patch("enabled", "yes")));
        assertThrows(ToolConfigInputError.class,
            () -> ToolConfig.mergeXiyouSettings(patch("clientId", "line\nbreak")));
    }

    @Test
    void mergeRejectsBearerPlaceholderAndEnabledWithoutToken() {
        assertEquals("请填写 MCP Token 原文，不含 Bearer 前缀或占位符。",
            assertThrows(ToolConfigInputError.class,
                () -> ToolConfig.mergeXiyouSettings(patch("mcpToken", "Bearer abc"))).getMessage());
        assertThrows(ToolConfigInputError.class,
            () -> ToolConfig.mergeXiyouSettings(patch("mcpToken", "YOUR_MCP_TOKEN")));
        assertEquals("启用西柚 MCP 前请填写 Token。",
            assertThrows(ToolConfigInputError.class,
                () -> ToolConfig.mergeXiyouSettings(patch("enabled", true))).getMessage());
    }

    @Test
    void blankSecretPatchesAreIgnored() {
        XiyouSettings existing = ToolConfig.mergeXiyouSettings(patch("mcpToken", "keep-me"));
        XiyouSettings next = ToolConfig.mergeXiyouSettings(patch("mcpToken", "   ", "clientId", "new"), existing);
        assertEquals("keep-me", next.mcpToken);
        assertEquals("new", next.clientId);
    }

    @Test
    void aesGcmRoundTripAndTamperDetection() {
        XiyouSettings settings = ToolConfig.mergeXiyouSettings(patch("mcpToken", "secret-value", "enabled", true));
        EncryptedConfig encrypted = ToolConfigServer.encryptXiyouSettings(settings, SCOPE, SEALER);
        assertEquals(1, encrypted.version);
        assertNotEquals(JsonSupport.stringify(settings.toMap()), encrypted.ciphertext);
        XiyouSettings restored = ToolConfigServer.decryptXiyouSettings(encrypted.toMap(), SCOPE, SEALER);
        assertEquals(settings.mcpToken, restored.mcpToken);
        assertEquals(settings.enabled, restored.enabled);
        Map<String, Object> tampered = new LinkedHashMap<>(encrypted.toMap());
        tampered.put("ciphertext", "YWJj");
        assertThrows(IllegalStateException.class, () -> ToolConfigServer.decryptXiyouSettings(tampered, SCOPE, SEALER));
        assertThrows(IllegalStateException.class, () -> ToolConfigServer.decryptXiyouSettings(Map.of("version", 2), SCOPE, SEALER));
    }

    @Test
    void inMemoryStoreAuditsTokenReplacement() {
        List<String> actions = new ArrayList<>();
        Ports.AuditSink audit = (o, u, a, t, id, meta) -> actions.add(a + ":" + meta.get("tokenReplaced"));
        ToolConfigServer.InMemoryToolConfigStore store = new ToolConfigServer.InMemoryToolConfigStore(SEALER, audit);
        assertEquals(false, store.read(SCOPE).enabled);
        store.save(SCOPE, patch("mcpToken", "token-a", "enabled", true));
        assertTrue(store.read(SCOPE).enabled);
        store.save(SCOPE, patch("clientId", "cid"));
        assertEquals(List.of("external_integration_setting_save:true", "external_integration_setting_save:false"), actions);
    }
}

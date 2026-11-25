package com.wimoor.ai.lili;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 移植自 src/lib/lili/tool-config.ts：西柚 MCP 的表单契约、合并校验与脱敏输出。 */
public final class ToolConfig {

    private ToolConfig() {}

    public static final String XIYOU_PROVIDER = "xiyou_mcp";
    public static final String XIYOU_ENDPOINT = "https://mcp.xydc.com/mcp";
    public static final String XIYOU_OPENAPI_BASE = "https://openapi.xiyouzhaoci.com";

    public static class ToolConfigInputError extends RuntimeException {
        public ToolConfigInputError(String message) { super(message); }
    }

    public static final class XiyouSettings {
        public final boolean enabled;
        public final String mcpUrl, mcpToken, openapiBase, clientId, clientSecret;
        public XiyouSettings(boolean enabled, String mcpUrl, String mcpToken, String openapiBase, String clientId, String clientSecret) {
            this.enabled = enabled; this.mcpUrl = mcpUrl; this.mcpToken = mcpToken;
            this.openapiBase = openapiBase; this.clientId = clientId; this.clientSecret = clientSecret;
        }
        public XiyouSettings with(String field, Object value) {
            boolean en = field.equals("enabled") ? (Boolean) value : enabled;
            String url = field.equals("mcpUrl") ? (String) value : mcpUrl;
            String token = field.equals("mcpToken") ? (String) value : mcpToken;
            String base = field.equals("openapiBase") ? (String) value : openapiBase;
            String cid = field.equals("clientId") ? (String) value : clientId;
            String secret = field.equals("clientSecret") ? (String) value : clientSecret;
            return new XiyouSettings(en, url, token, base, cid, secret);
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled); m.put("mcpUrl", mcpUrl); m.put("mcpToken", mcpToken);
            m.put("openapiBase", openapiBase); m.put("clientId", clientId); m.put("clientSecret", clientSecret);
            return m;
        }
    }

    public static final XiyouSettings DEFAULT = new XiyouSettings(false, XIYOU_ENDPOINT, "", XIYOU_OPENAPI_BASE, "", "");

    /** XiyouPublicSettings：凭证绝不返回给配置 API。 */
    public static final class XiyouPublicSettings {
        public final boolean enabled;
        public final String mcpUrl, openapiBase, clientId;
        public final boolean hasMcpToken, hasClientSecret, configured;
        XiyouPublicSettings(XiyouSettings s) {
            this.enabled = s.enabled; this.mcpUrl = s.mcpUrl; this.openapiBase = s.openapiBase; this.clientId = s.clientId;
            this.hasMcpToken = !isEmpty(s.mcpToken); this.hasClientSecret = !isEmpty(s.clientSecret);
            this.configured = hasMcpToken;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled); m.put("mcpUrl", mcpUrl); m.put("openapiBase", openapiBase); m.put("clientId", clientId);
            m.put("hasMcpToken", hasMcpToken); m.put("hasClientSecret", hasClientSecret); m.put("configured", configured);
            return m;
        }
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static final Set<String> KNOWN_FIELDS = Set.of("enabled", "mcpUrl", "mcpToken", "openapiBase", "clientId", "clientSecret");
    private static final Set<String> SECRET_FIELDS = Set.of("mcpToken", "clientSecret");

    /** Object.hasOwn(defaultXiyouSettings, field) 等价校验。 */
    public static XiyouSettings mergeXiyouSettings(Map<String, Object> patch, XiyouSettings existing) {
        if (patch == null) throw new ToolConfigInputError("配置必须是对象。");
        XiyouSettings next = existing == null ? DEFAULT : existing;
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String field = e.getKey();
            Object value = e.getValue();
            if (!KNOWN_FIELDS.contains(field)) throw new ToolConfigInputError("配置包含未知字段。");
            if (field.equals("enabled")) {
                if (!(value instanceof Boolean)) throw new ToolConfigInputError("启用状态无效。");
                next = next.with(field, value);
                continue;
            }
            if (!(value instanceof String) || ((String) value).length() > 8192)
                throw new ToolConfigInputError("配置字段格式或长度无效。");
            String text = ((String) value).trim();
            if (SECRET_FIELDS.contains(field) && text.isEmpty()) continue;
            if (text.matches("(?s).*[\\r\\n\\u0000].*")) throw new ToolConfigInputError("配置字段不能包含换行或控制字符。");
            next = next.with(field, text);
        }
        if (!XIYOU_ENDPOINT.equals(next.mcpUrl))
            throw new ToolConfigInputError("西柚 MCP 地址须为 https://mcp.xydc.com/mcp，Token 请单独填写。");
        if (!XIYOU_OPENAPI_BASE.equals(next.openapiBase))
            throw new ToolConfigInputError("西柚 OpenAPI 根地址无效。");
        if (!isEmpty(next.mcpToken) && (next.mcpToken.matches("(?i)^Bearer\\s.*")
            || next.mcpToken.matches("(?i).*(YOUR_MCP_TOKEN|__keep__|[•●]).*")))
            throw new ToolConfigInputError("请填写 MCP Token 原文，不含 Bearer 前缀或占位符。");
        if (next.enabled && isEmpty(next.mcpToken))
            throw new ToolConfigInputError("启用西柚 MCP 前请填写 Token。");
        return next;
    }

    public static XiyouSettings mergeXiyouSettings(Map<String, Object> patch) {
        return mergeXiyouSettings(patch, DEFAULT);
    }

    public static XiyouPublicSettings publicXiyouSettings(XiyouSettings settings) {
        return new XiyouPublicSettings(settings);
    }
}

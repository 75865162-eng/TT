package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.wimoor.ai.lili.DevicePairing.Credential;
import com.wimoor.ai.lili.ResearchTools.ResearchRequest;
import com.wimoor.ai.lili.RuntimeTools.ProviderId;
import com.wimoor.ai.lili.RuntimeTools.RuntimeProvider;
import com.wimoor.ai.lili.ToolConfigServer.ToolConfigScope;
import com.wimoor.ai.lili.ToolConfigServer.ToolConfigStore;
import com.wimoor.ai.lili.Ports.McpTransport;

/**
 * 移植自 src/lib/lili/runtime-server.ts：按用户/工作区构造运行时工具集。
 * ERP 会话使用自身用户身份；bearer 调用方保留其精确凭证。每个来源的配置失败相互隔离，密钥留在服务端适配器内。
 */
public final class RuntimeServer {

    private RuntimeServer() {}

    public static final class User {
        public final String id, organizationId;
        public User(String id, String organizationId) { this.id = id; this.organizationId = organizationId; }
    }

    public static final class SellerSettings {
        public final boolean enabled;
        public final String apiKey, serverUrl;
        public SellerSettings(boolean enabled, String apiKey, String serverUrl) {
            this.enabled = enabled; this.apiKey = apiKey; this.serverUrl = serverUrl;
        }
    }

    /** 宿主适配器端口。 */
    public interface RuntimeServerDeps {
        boolean assertWorkspaceAccess(String organizationId, String workspaceId, String userId);
        void assertSession() throws Exception;
        Credential findActiveCredential(DevicePairing.Scope scope, String credentialId);
        Credential createErpSessionIdentity(DevicePairing.Scope scope) throws Exception;
        Credential validateCredential(Credential credential) throws Exception;
        McpTransport erpTransport(Credential identity);
        SellerSettings sellerSettings(User user, String workspaceId);
        McpTransport sellerTransport(SellerSettings settings);
        McpTransport liliTransport(String endpoint, String authorization);
        boolean authorizationPresent();
        boolean authorizeEdit();
        void assertAmazonAction(DevicePairing.Scope scope, String userId, String action, String moduleId) throws Exception;
        ToolConfigStore toolConfigStore();
    }

    public static final class ErpTransportResult {
        public final McpTransport transport;
        public final String reason;
        public final String credentialId;
        ErpTransportResult(McpTransport transport, String reason, String credentialId) {
            this.transport = transport; this.reason = reason; this.credentialId = credentialId;
        }
    }

    /** createErpResearchTransport：断言会话 → 解析凭证 → 包装 list/call 两侧。 */
    public static ErpTransportResult createErpResearchTransport(DevicePairing.Scope scope, String credentialId,
                                                               RuntimeServerDeps deps) throws Exception {
        deps.assertSession();
        Credential credential = credentialId != null ? deps.findActiveCredential(scope, credentialId) : null;
        if (credentialId != null && credential == null) return new ErpTransportResult(null, "ERP_PERSONAL_MCP_CREDENTIAL_INVALID", null);
        Credential identity = credential != null ? deps.validateCredential(credential) : deps.createErpSessionIdentity(scope);
        final McpTransport base = deps.erpTransport(identity);
        McpTransport wrapped = new McpTransport() {
            @Override public List<Map<String, Object>> listTools() throws Exception {
                deps.assertSession();
                List<Map<String, Object>> tools = base.listTools();
                deps.assertSession();
                return tools;
            }
            @Override public Object callTool(String name, Map<String, Object> args, Map<String, Object> schema) throws Exception {
                deps.assertSession();
                Object result = base.callTool(name, args, schema);
                deps.assertSession();
                return result;
            }
        };
        return new ErpTransportResult(wrapped, null, credential == null ? null : credential.id);
    }

    public static final class RuntimeToolsResult {
        public final RuntimeTools tools;
        public final String erpCredentialId;
        public final List<Map<String, Object>> erpCredentialOptions;
        RuntimeToolsResult(RuntimeTools tools, String erpCredentialId, List<Map<String, Object>> options) {
            this.tools = tools; this.erpCredentialId = erpCredentialId; this.erpCredentialOptions = options;
        }
    }

    /** runtimeToolsForUser：配置失败按来源隔离，返回 { ...createRuntimeTools, erpCredentialId, options }。 */
    public static RuntimeToolsResult runtimeToolsForUser(User user, String workspaceId, Map<String, Object> body,
                                                         RuntimeServerDeps deps) {
        if (!deps.assertWorkspaceAccess(user.organizationId, workspaceId, user.id))
            throw new IllegalStateException("RESEARCH_SCOPE_DENIED");
        DevicePairing.Scope scope = new DevicePairing.Scope(user.organizationId, workspaceId);
        final String userId = user.id;
        Runnable assertMarketAccess = () -> {
            if (deps.authorizationPresent() && !deps.authorizeEdit()) throw new IllegalStateException("RESEARCH_SCOPE_DENIED");
            try { deps.assertAmazonAction(scope, userId, "edit", "agents"); }
            catch (Exception e) { throw new IllegalStateException("RESEARCH_SCOPE_DENIED", e); }
        };
        List<RuntimeProvider> providers = new ArrayList<>();
        String erpCredentialId = null;
        List<Map<String, Object>> erpCredentialOptions = new ArrayList<>();
        try {
            ErpTransportResult erp = createErpResearchTransport(scope, null, deps);
            erpCredentialId = erp.credentialId;
            Predicate<Map<String, Object>> readOnly = t -> Boolean.TRUE.equals(JsonSupport.obj(t.get("annotations")).get("readOnlyHint"));
            providers.add(new RuntimeProvider(ProviderId.ERP, erp.transport, erp.reason, readOnly));
        } catch (Exception e) {
            providers.add(new RuntimeProvider(ProviderId.ERP, null, "ERP_MCP_CONFIGURATION_UNAVAILABLE", t -> false));
        }
        try {
            SellerSettings settings = deps.sellerSettings(user, workspaceId);
            McpTransport live = MarketAccess.liveMarketTransport(() -> {
                assertMarketAccess.run();
                SellerSettings current = deps.sellerSettings(user, workspaceId);
                if (!current.enabled || isEmpty(current.apiKey) || isEmpty(current.serverUrl)) return null;
                return deps.sellerTransport(current);
            });
            boolean configured = settings.enabled && !isEmpty(settings.apiKey);
            Predicate<Map<String, Object>> readOnly = t -> {
                Map<String, Object> ann = JsonSupport.obj(t.get("annotations"));
                return ann == null || !Boolean.FALSE.equals(ann.get("readOnlyHint"));
            };
            providers.add(new RuntimeProvider(ProviderId.SELLERSRITE, configured ? live : null,
                configured ? null : "SELLERSPRITE_MCP_NOT_CONFIGURED", readOnly));
        } catch (Exception e) {
            providers.add(new RuntimeProvider(ProviderId.SELLERSRITE, null, "SELLERSPRITE_CONFIGURATION_UNAVAILABLE", t -> false));
        }
        try {
            ToolConfigStore store = deps.toolConfigStore();
            com.wimoor.ai.lili.ToolConfig.XiyouSettings settings = store == null
                ? com.wimoor.ai.lili.ToolConfig.DEFAULT
                : store.read(new ToolConfigScope(user.organizationId, workspaceId, user.id));
            boolean configured = settings.enabled && !isEmpty(settings.mcpToken);
            Predicate<Map<String, Object>> readOnly = t -> {
                Map<String, Object> ann = JsonSupport.obj(t.get("annotations"));
                return (ann == null || !Boolean.FALSE.equals(ann.get("readOnlyHint")))
                    && RuntimeTools.XIYOU_RESEARCH_READ_TOOLS.contains(JsonSupport.str(t.get("name")));
            };
            McpTransport live = MarketAccess.liveMarketTransport(() -> {
                assertMarketAccess.run();
                com.wimoor.ai.lili.ToolConfig.XiyouSettings current = store.read(new ToolConfigScope(user.organizationId, workspaceId, user.id));
                if (!current.enabled || isEmpty(current.mcpToken)) return null;
                return deps.liliTransport(current.mcpUrl, "Bearer " + current.mcpToken);
            });
            providers.add(new RuntimeProvider(ProviderId.XIYOU, configured ? live : null,
                configured ? null : "XIYOU_MCP_NOT_CONFIGURED", readOnly));
        } catch (Exception e) {
            providers.add(new RuntimeProvider(ProviderId.XIYOU, null, "XIYOU_CONFIGURATION_UNAVAILABLE", t -> false));
        }
        return new RuntimeToolsResult(RuntimeTools.createRuntimeTools(providers), erpCredentialId, erpCredentialOptions);
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
}

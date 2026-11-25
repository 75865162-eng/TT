package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wimoor.ai.lili.MarketAccess.MarketProviderAccess;
import com.wimoor.ai.lili.MarketAccess.ProviderStatus;
import com.wimoor.ai.lili.Ports.ApiRequest;
import com.wimoor.ai.lili.Ports.Authorization;
import com.wimoor.ai.lili.Ports.McpTransport;
import com.wimoor.ai.lili.Ports.PermissionAction;
import com.wimoor.ai.lili.ResearchBearerMcp.AuditEvent;
import com.wimoor.ai.lili.ResearchBearerMcp.ResearchBearerDependencies;
import com.wimoor.ai.lili.ResearchBearerMcp.ResearchMcpIdentity;
import com.wimoor.ai.lili.ResearchTools.ResearchDispatcher;
import com.wimoor.ai.lili.ToolConfig.XiyouSettings;

/**
 * 移植自 src/lib/lili/research-bearer-server.ts：Bearer 依赖的具体装配，
 * 复用凭证认证与实时组织成员关系，绝不创建浏览器会话。所有宿主要求通过 HostAdapters 注入。
 */
public final class ResearchBearerServer {

    private ResearchBearerServer() {}

    public static final class Member {
        public final String id, name, email, role, organizationName;
        public final boolean active;
        public Member(String id, String name, String email, String role, String organizationName, boolean active) {
            this.id = id; this.name = name; this.email = email; this.role = role;
            this.organizationName = organizationName; this.active = active;
        }
    }

    public interface HostAdapters {
        ResearchMcpIdentity authenticate(String authorization, String resourceUrl, ApiRequest request) throws Exception;
        Member member(ResearchMcpIdentity identity);
        boolean workspaceAccess(ResearchMcpIdentity identity);
        void assertAmazonAction(ResearchMcpIdentity identity, String userId, String action, String moduleId) throws Exception;
        McpTransport erp(ResearchMcpIdentity identity) throws Exception;
        RuntimeServer.SellerSettings sellerSettings(ResearchMcpIdentity identity);
        XiyouSettings xiyouSettings(ResearchMcpIdentity identity);
        Object readPaidConsent(ResearchMcpIdentity identity);
        ResearchDispatcher dispatch();
        ResearchTools.SyncChatCaller syncCaller();
        void markUsed(ResearchMcpIdentity identity);
        void audit(String organizationId, String userId, String action, String entityType, String entityId,
                   Map<String, Object> metadata);
    }

    private static String resourceUrl(ApiRequest request) {
        return request.origin() + "/api/lili/mcp";
    }

    public static ResearchBearerDependencies dependencies(HostAdapters adapters) {
        return new ResearchBearerDependencies() {
            @Override public ResearchMcpIdentity authenticate(String authorization, ApiRequest request) throws Exception {
                return adapters.authenticate(authorization, resourceUrl(request), request);
            }
            @Override public Authorization permission(ResearchMcpIdentity identity, String moduleId,
                                                       PermissionAction action, ApiRequest request) {
                String queryWorkspace = queryParam(request.url, "workspaceId");
                String headerWorkspace = request.header("x-workspace-id");
                if ((queryWorkspace != null && !queryWorkspace.equals(identity.workspaceId))
                    || (headerWorkspace != null && !headerWorkspace.equals(identity.workspaceId)))
                    return Authorization.deny(403, "Forbidden.");
                Member member = adapters.member(identity);
                if (member == null || !member.active || !adapters.workspaceAccess(identity))
                    return Authorization.deny(403, "Forbidden.");
                try {
                    if (!"amazon_account".equals(moduleId) || action != PermissionAction.VIEW)
                        adapters.assertAmazonAction(identity, identity.userId, action.wire(), moduleId);
                } catch (SyncInputException e) {
                    if (e.status == 403) return Authorization.deny(403, "Forbidden.");
                    throw e;
                } catch (Exception e) {
                    return Authorization.deny(403, "Forbidden.");
                }
                return Authorization.allow(member.id, identity.organizationId);
            }
            @Override public void audit(ResearchMcpIdentity identity, AuditEvent event) {
                if ("SUCCEEDED".equals(event.status)) adapters.markUsed(identity);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("workspaceId", identity.workspaceId);
                meta.put("credentialId", identity.id);
                meta.put("credentialRevision", identity.revision);
                meta.put("requestId", event.requestId);
                meta.put("method", event.method);
                meta.put("tool", event.tool);
                meta.put("status", event.status);
                if (event.skill != null) meta.put("skill", event.skill);
                adapters.audit(identity.organizationId, identity.userId,
                    "lili.mcp." + event.status.toLowerCase(), "AmazonMcpCredential", identity.id, meta);
            }
            @Override public McpTransport erp(ResearchMcpIdentity identity) throws Exception { return adapters.erp(identity); }
            @Override public Map<String, Object> marketAccess(ResearchMcpIdentity identity, ApiRequest request) {
                Authorization permission = permission(identity, "agents", PermissionAction.EDIT, request);
                boolean canResearch = identity.tools.contains("research_step") && permission.ok;
                if (!canResearch) return MarketAccess.marketAccessSummary(true, false, new ArrayList<>());
                RuntimeServer.SellerSettings seller = adapters.sellerSettings(identity);
                XiyouSettings xiyou = adapters.xiyouSettings(identity);
                boolean sellerConfigured = seller != null && seller.enabled && !empty(seller.apiKey) && !empty(seller.serverUrl);
                boolean xiyouConfigured = xiyou != null && xiyou.enabled && !empty(xiyou.mcpToken);
                List<MarketProviderAccess> providers = List.of(
                    new MarketProviderAccess("sellersprite", sellerConfigured,
                        sellerConfigured ? ProviderStatus.CONFIGURED_NOT_PROBED : ProviderStatus.DISABLED_OR_MISSING),
                    new MarketProviderAccess("xiyou", xiyouConfigured,
                        xiyouConfigured ? ProviderStatus.CONFIGURED_NOT_PROBED : ProviderStatus.DISABLED_OR_MISSING));
                Map<String, Object> summary = MarketAccess.marketAccessSummary(true, true, providers);
                Object consent = adapters.readPaidConsent(identity);
                if (consent != null) {
                    summary.put("approval_required", false);
                    summary.put("reason", "SAVED_PAID_RESEARCH_CONSENT");
                    summary.put("paid_call_consent", consent);
                }
                return summary;
            }
            @Override public ResearchDispatcher dispatch() { return adapters.dispatch(); }
            @Override public ResearchTools.SyncChatCaller syncCaller() { return adapters.syncCaller(); }
        };
    }

    private static String queryParam(String url, String key) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String pair : url.substring(q + 1).split("&")) {
            int idx = pair.indexOf('=');
            String k = idx < 0 ? pair : pair.substring(0, idx);
            if (key.equals(k)) return idx < 0 ? "" : java.net.URLDecoder.decode(pair.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    private static boolean empty(String s) { return s == null || s.isEmpty(); }
}

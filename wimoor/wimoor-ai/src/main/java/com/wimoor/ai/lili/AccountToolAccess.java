package com.wimoor.ai.lili;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 移植自 src/lib/lili/account-tool-access.ts：凭据工具集与 OAuth/MCP 授权集的判定。
 * Amazon 只读工具继承实时账号分配；其他能力必须由服务端签发的设备授权继承。
 */
public final class AccountToolAccess {

    private AccountToolAccess() {}

    public static final class ToolCredential {
        public final String id, name;
        public final List<String> tools;
        public ToolCredential(String id, String name, List<String> tools) {
            this.id = id; this.name = name; this.tools = tools == null ? new ArrayList<>() : tools;
        }
    }

    /** host 的 amazonMcpToolDefinitions / amazonMcpTools 端口。 */
    public interface ToolRegistry {
        List<String> amazonAllNames();
        List<String> amazonReadOnlyNames();
        /** 默认实现从 shipped 工具目录推导只读工具集（宿主可覆盖注入真实注册中心）。 */
        static ToolRegistry shipped() {
            return new ToolRegistry() {
                @Override public List<String> amazonAllNames() {
                    List<String> names = new ArrayList<>();
                    Catalog.listTools().forEach(t -> names.add(t.name));
                    return names;
                }
                @Override public List<String> amazonReadOnlyNames() {
                    List<String> names = new ArrayList<>();
                    for (Catalog.CatalogTool t : Catalog.listTools()) if (t.readOnly) names.add(t.name);
                    return names;
                }
            };
        }
    }

    /** liliOAuthGrant 查询端口。 */
    public interface GrantLookup {
        Grant findByDeviceId(String deviceId);
    }

    public static final class Grant {
        public final String resource;
        public final Long revokedAt, expiresAt;
        public Grant(String resource, Long revokedAt, Long expiresAt) {
            this.resource = resource; this.revokedAt = revokedAt; this.expiresAt = expiresAt;
        }
        public boolean valid(long now) {
            return revokedAt == null && expiresAt != null && expiresAt > now;
        }
    }

    public static final class McpToolAccessScope {
        public final String organizationId, workspaceId, userId, provider, roleId;
        public McpToolAccessScope(String organizationId, String workspaceId, String userId, String provider, String roleId) {
            this.organizationId = organizationId; this.workspaceId = workspaceId; this.userId = userId;
            this.provider = provider; this.roleId = roleId;
        }
    }

    /** getMcpToolGrantScope 端口；返回 null 表示双读期无授权记录，回退凭据自身工具集。 */
    public interface ScopeLookup {
        List<String> grantedTools(McpToolAccessScope scope);
    }

    private static Set<String> withAccountReads(List<String> tools, ToolRegistry registry) {
        Set<String> out = new LinkedHashSet<>(tools);
        out.addAll(registry.amazonReadOnlyNames());
        return out;
    }

    public static Set<String> accountToolsForLili(ToolCredential credential, GrantLookup grants,
                                                   ToolRegistry registry, long now) {
        if (credential.name == null || !credential.name.startsWith("Lili device: "))
            return withAccountReads(credential.tools, registry);
        Grant grant = grants == null ? null : grants.findByDeviceId(credential.id);
        if (grant == null || !grant.valid(now)) return withAccountReads(credential.tools, registry);
        String path;
        try { path = URI.create(grant.resource).getPath(); }
        catch (IllegalArgumentException e) { path = null; }
        if (!"/api/lili/mcp".equals(path)) return withAccountReads(credential.tools, registry);
        Set<String> registered = new LinkedHashSet<>(registry.amazonAllNames());
        for (java.util.Map.Entry<String, String> e : ResearchGrants.RESEARCH_CREDENTIAL_TOOLS) registered.add(e.getKey());
        return withAccountReads(new ArrayList<>(registered), registry);
    }

    /** 存在明确 MCP_TOOL 授权时取交集；无授权记录时回退凭据工具集。 */
    public static boolean credentialAllowsTool(ToolCredential credential, String tool, GrantLookup grants,
                                               ToolRegistry registry, ScopeLookup scopeLookup,
                                               McpToolAccessScope accessScope, long now) {
        Set<String> allowed = accountToolsForLili(credential, grants, registry, now);
        if (!allowed.contains(tool)) return false;
        if (accessScope != null && scopeLookup != null) {
            List<String> granted = scopeLookup.grantedTools(accessScope);
            if (granted != null && !granted.contains(tool)) return false;
        }
        return true;
    }
}

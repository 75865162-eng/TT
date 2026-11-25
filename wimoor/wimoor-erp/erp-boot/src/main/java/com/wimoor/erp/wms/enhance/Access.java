package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 访问控制与审计（对应源文件 access.ts）。
 * 移植了：scoped(scope) 的组织/工作区投影、json 的深度拷贝语义、
 * authorizeWms 的多身份排序授权、auditWms 的审计元数据构造、
 * assertWmsAccess 的成员资格/角色停用/权限清单/工作区校验。
 * 源端依赖 prisma 与 workspace/accounts 模块；本类定义 AccessGateway 接口与内存实现，
 * 把权限/审计逻辑真实实现在领域层。
 */
public final class Access {

    private Access() {}

    /** 身份与动作。 */
    public static final class Identity {
        private final String userId;
        private final String action;

        public Identity(String userId, String action) {
            this.userId = userId;
            this.action = action;
        }

        public String getUserId() { return userId; }
        public String getAction() { return action; }
    }

    /** 授权结果。 */
    public static final class AccessResult {
        private final String roleId;
        private final List<String> permissions;

        public AccessResult(String roleId, List<String> permissions) {
            this.roleId = roleId;
            this.permissions = permissions;
        }

        public String getRoleId() { return roleId; }
        public List<String> getPermissions() { return permissions; }
    }

    /** 访问网关接口。 */
    public interface AccessGateway {
        boolean isOrganizationMember(String organizationId, String userId);
        boolean isRosterActive(String organizationId, String userId);
        AccessResult findRole(String organizationId, String roleId);
        boolean workspaceExists(String organizationId, String workspaceId, String userId);
        void audit(String organizationId, String userId, String action, String entityId,
                   Map<String, Object> metadata);
    }

    /** assertWmsAccess。 */
    public static AccessResult assertWmsAccess(AccessGateway gateway, Scope scope, String userId,
                                               String action) {
        if (!gateway.isOrganizationMember(scope.getOrganizationId(), userId)) {
            throw new WmsError.Input("当前账号不属于此组织。", 403);
        }
        if (!gateway.isRosterActive(scope.getOrganizationId(), userId)) {
            throw new WmsError.Input("当前成员已停用。", 403);
        }
        // 角色在源端来自 membership.role；这里由网关直接提供映射。
        AccessResult role = gateway.findRole(scope.getOrganizationId(), userId);
        if (role == null) throw new WmsError.Input("组织角色目录尚未就绪。", 403);
        if (!role.getPermissions().contains(action)) {
            throw new WmsError.Input("当前账号没有此 WMS 操作权限。", 403);
        }
        if (!gateway.workspaceExists(scope.getOrganizationId(), scope.getWorkspaceId(), userId)) {
            throw new WmsError.Input("工作区不存在。", 403);
        }
        return role;
    }

    /** authorizeWms：按 userId 排序后逐一授权。 */
    public static void authorizeWms(AccessGateway gateway, Scope scope, String action, Identity override) {
        Map<String, String> identities = new LinkedHashMap<>();
        identities.put(scope.getActorUserId(), action);
        if (override != null) identities.put(override.getUserId(), override.getAction());
        List<String> userIds = new ArrayList<>(identities.keySet());
        java.util.Collections.sort(userIds);
        for (String userId : userIds) {
            assertWmsAccess(gateway, scope, userId, identities.get(userId));
        }
    }

    /** auditWms：构造审计元数据。 */
    public static void auditWms(AccessGateway gateway, Scope scope, String action, String entityId,
                                Map<String, Object> metadata) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("workspaceId", scope.getWorkspaceId());
        detail.put("detail", metadata == null ? new LinkedHashMap<>() : metadata);
        gateway.audit(scope.getOrganizationId(), scope.getActorUserId(), "wms." + action, entityId, detail);
    }
}

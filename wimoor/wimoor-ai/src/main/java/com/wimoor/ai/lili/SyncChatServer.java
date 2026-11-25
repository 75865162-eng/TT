package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wimoor.ai.lili.DevicePairing.Credential;
import com.wimoor.ai.lili.SyncChatTools.AccessScope;
import com.wimoor.ai.lili.SyncChatTools.Source;
import com.wimoor.ai.lili.SyncChatTools.SyncChatDependencies;
import com.wimoor.ai.lili.SyncChatTools.SyncChatPreview;
import com.wimoor.ai.lili.SyncChatTools.WorkflowException;

/**
 * 移植自 src/lib/lili/sync-chat-server.ts：每次 inspect/approve/apply 前重新认证，
 * 保持原始凭证身份，校验工作区、角色、数据来源范围后才调用 executeSyncChat。
 */
public final class SyncChatServer {

    private SyncChatServer() {}

    public interface SyncChatHostDeps {
        Credential authenticate(String authorization, String resourceUrl) throws Exception;
        void assertAction(DevicePairing.Scope scope, String userId, String action, String moduleId) throws Exception;
        Map<String, Object> findRequest(DevicePairing.Scope scope, String requestId, String userId) throws Exception;
        List<AccessScope> explicitDataAccessScopes(DevicePairing.Scope scope, String userId) throws Exception;
        List<AccessScope> parseAccessScopes(Object scopes);
        List<Source> findSources(DevicePairing.Scope scope, List<String> sourceIds) throws Exception;
        Map<String, Object> decideChange(DevicePairing.Scope scope, String userId, String requestId,
                                         String decision, String reason) throws Exception;
        Object applyChange(DevicePairing.Scope scope, String userId, String requestId) throws Exception;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> callSyncChatTool(Ports.ApiRequest request, String name,
                                                       Map<String, Object> args, SyncChatHostDeps deps) throws Exception {
        String resourceUrl = request.origin() + "/api/lili/mcp";
        String authorization = request.header("authorization");
        Credential initial = deps.authenticate(authorization, resourceUrl);
        DevicePairing.Scope scope = new DevicePairing.Scope(initial.organizationId, initial.workspaceId);

        SyncChatDependencies orchestration = new SyncChatDependencies() {
            @Override public SyncChatPreview inspect() throws Exception {
                Credential current = deps.authenticate(authorization, resourceUrl);
                List<String> tools = JsonSupport.strings(current.tools);
                if (current.id == null || !current.id.equals(initial.id)
                    || current.revision != initial.revision || !tools.contains(name)
                    || !tools.contains("data_sync_start")
                    || !String.valueOf(args.get("workspaceId")).equals(current.workspaceId))
                    throw new WorkflowException("WORKFLOW_ACCESS_DENIED");
                deps.assertAction(scope, current.userId, "view", "operations");
                if ("workflow_sync_confirm".equals(name)) deps.assertAction(scope, current.userId, "approve", "operations");
                Map<String, Object> row = deps.findRequest(scope, String.valueOf(args.get("requestId")), current.userId);
                if (row == null || !SyncChatTools.supportedSyncChatRequest(SyncChatPreview.from(row)))
                    throw new WorkflowException("WORKFLOW_REQUEST_NOT_FOUND");
                List<Object> sourceIds = JsonSupport.arr(row.get("sourceIds"));
                if (sourceIds == null || sourceIds.isEmpty()) throw new WorkflowException("WORKFLOW_REQUEST_NOT_FOUND");
                List<AccessScope> credentialScopes = deps.parseAccessScopes(current.scopes);
                List<AccessScope> accessScopes = deps.explicitDataAccessScopes(scope, current.userId);
                List<Source> sources = deps.findSources(scope, JsonSupport.strings(sourceIds));
                if (!SyncChatTools.syncSourcesWithinScope(sources, JsonSupport.strings(sourceIds), credentialScopes, accessScopes))
                    throw new WorkflowException("WORKFLOW_ACCESS_DENIED");
                return SyncChatPreview.from(row);
            }
            @Override public SyncChatPreview approve(String quote) throws Exception {
                inspect();
                return SyncChatPreview.from(deps.decideChange(scope, initial.userId,
                    String.valueOf(args.get("requestId")), "APPROVED", "Codex chat: " + quote));
            }
            @Override public Object apply() throws Exception {
                inspect();
                return deps.applyChange(scope, initial.userId, String.valueOf(args.get("requestId")));
            }
        };
        return SyncChatTools.executeSyncChat(name, args, orchestration, System.currentTimeMillis());
    }
}

package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wimoor.ai.lili.Ports.ToolDefinition;

/**
 * 移植自 src/lib/lili/sync-chat-tools.ts：工作流同步的对话预览/批准工具定义，
 * 以及 supportedSyncChatRequest、executeSyncChat、syncSourcesWithinScope 的纯判定逻辑。
 * 副作用留在 SyncChatDependencies 的宿主实现中，编排逻辑可脱离凭证/数据库测试。
 */
public final class SyncChatTools {

    private SyncChatTools() {}

    public static class WorkflowException extends RuntimeException {
        public final String code;
        public WorkflowException(String code) { super(code); this.code = code; }
    }

    private static final Map<String, Object> WORKSPACE = Ports.jsonObject("type", "string", "minLength", 1);
    private static final Map<String, Object> REQUEST_ID = Ports.jsonObject("type", "string", "minLength", 1, "maxLength", 100);

    public static final List<ToolDefinition> SYNC_CHAT_TOOLS = List.of(
        new ToolDefinition("workflow_sync_review",
            "Preview one owned ERP data_sync_start request in the current credential's seller/workspace scope. Returns exact digest, revision, targets, effects and status. No dispatch. Present it in Codex chat before requesting one approval. Unsupported store writes are not accepted.",
            Ports.jsonObject("type", "object",
                "properties", Ports.jsonObject("workspaceId", WORKSPACE, "requestId", REQUEST_ID),
                "required", List.of("workspaceId", "requestId"), "additionalProperties", false),
            Ports.annotations(true)),
        new ToolDefinition("workflow_sync_confirm",
            "After the human explicitly approves the exact workflow_sync_review plan in Codex chat, record that quote, approve and apply that single bounded data sync request using existing services. No second browser approval. Requires expectedDigest and expectedRevision from the preview. Never invent human consent. If interrupted, read the request status before resuming; never recreate requests. Does not support configuration changes, access grants, arbitrary store writes or budget increases.",
            Ports.jsonObject("type", "object",
                "properties", Ports.jsonObject("workspaceId", WORKSPACE, "requestId", REQUEST_ID,
                    "expectedDigest", Ports.jsonObject("type", "string", "pattern", "^[a-f0-9]{64}$"),
                    "expectedRevision", Ports.jsonObject("type", "integer", "minimum", 0),
                    "userConfirmation", Ports.jsonObject("type", "string", "minLength", 1, "maxLength", 2000)),
                "required", List.of("workspaceId", "requestId", "expectedDigest", "expectedRevision", "userConfirmation"),
                "additionalProperties", false),
            Ports.annotations(false))
    );

    /** skillSyncRetryResources（来自 amazon-sync/skill-sync-retry.ts），确定性的白名单。 */
    public static final Set<String> SKILL_SYNC_RETRY_RESOURCES = Set.of(
        "fba_inventory_report", "reserved_inventory", "stranded_inventory", "orders", "finance",
        "returns", "reimbursements", "inventory_events", "fulfillment", "catalog_items",
        "listing_details", "promotions", "customer_feedback");

    /** SyncChatPreview 的不可变视图，保留其余字段以便原样回传。 */
    public static final class SyncChatPreview {
        public final Map<String, Object> raw;
        SyncChatPreview(Map<String, Object> raw) { this.raw = raw; }
        public static SyncChatPreview from(Map<String, Object> m) { return m == null ? null : new SyncChatPreview(m); }
        public String id() { return JsonSupport.str(raw.get("id")); }
        public String operation() { return JsonSupport.str(raw.get("operation")); }
        public String changeDigest() { return JsonSupport.str(raw.get("changeDigest")); }
        public long baseRevision() { Number n = JsonSupport.num(raw.get("baseRevision")); return n == null ? -1 : n.longValue(); }
        public String status() { return JsonSupport.str(raw.get("status")); }
        public long expiresAt() { Object v = raw.get("expiresAt"); if (v instanceof Number) return ((Number) v).longValue(); return 0; }
        public Map<String, Object> toMap() { return JsonSupport.copy(raw); }
    }

    public interface SyncChatDependencies {
        SyncChatPreview inspect() throws Exception;
        SyncChatPreview approve(String quote) throws Exception;
        Object apply() throws Exception;
    }

    public static boolean supportedSyncChatRequest(SyncChatPreview row) {
        if (row == null) return false;
        if ("run".equals(row.operation())) return true;
        if (!"control-task".equals(row.operation())) return false;
        Map<String, Object> command = JsonSupport.obj(row.raw.get("afterSummary"));
        String control = command == null ? null : JsonSupport.str(command.get("control"));
        if (!List.of("retry", "resume").contains(control)) return false;
        String targetId = JsonSupport.str(row.raw.get("targetId"));
        if (targetId == null || targetId.isEmpty() || !targetId.equals(command.get("id"))) return false;
        List<Object> domains = JsonSupport.arr(row.raw.get("domains"));
        if (domains == null || domains.size() != 1 || !SKILL_SYNC_RETRY_RESOURCES.contains(String.valueOf(domains.get(0)))) return false;
        List<Object> sourceIds = JsonSupport.arr(row.raw.get("sourceIds"));
        List<Object> marketplaceIds = JsonSupport.arr(row.raw.get("marketplaceIds"));
        return sourceIds != null && sourceIds.size() == 1 && marketplaceIds != null && marketplaceIds.size() == 1;
    }

    /** executeSyncChat：先 inspect 判支持性，再按确认逐字引用、摘要/版本/过期状态推进。 */
    public static Map<String, Object> executeSyncChat(String name, Map<String, Object> args,
                                                       SyncChatDependencies deps, long now) throws Exception {
        ToolDefinition tool = null;
        for (ToolDefinition t : SYNC_CHAT_TOOLS) if (t.name.equals(name)) { tool = t; break; }
        if (tool == null) throw new WorkflowException("UNKNOWN_WORKFLOW_TOOL");
        SchemaValidator.rejectCredentials(args);
        SchemaValidator.validate(tool.inputSchema, args);
        SyncChatPreview row = deps.inspect();
        if (!supportedSyncChatRequest(row)) throw new WorkflowException("WORKFLOW_EXECUTOR_UNSUPPORTED");
        if ("workflow_sync_review".equals(name))
            return Ports.jsonObject("ok", true, "request", row.toMap(),
                "nextAction", Ports.jsonObject("kind", "APPLIED".equals(row.status()) ? "DONE" : "CHAT_CONFIRM",
                    "channel", "codex_chat", "browserRequired", false));
        String confirmation = JsonSupport.str(args.get("userConfirmation"));
        if (confirmation == null || confirmation.trim().isEmpty()) throw new WorkflowException("HUMAN_CONFIRMATION_REQUIRED");
        if (!row.changeDigest().equals(args.get("expectedDigest")) || row.baseRevision() != ((Number) args.get("expectedRevision")).longValue())
            throw new WorkflowException("STALE_WORKFLOW_PLAN");
        if ("APPLIED".equals(row.status())) return Ports.jsonObject("ok", true, "alreadyApplied", true, "request", row.toMap());
        if (row.expiresAt() <= now) throw new WorkflowException("WORKFLOW_PLAN_EXPIRED");
        if ("REQUESTED".equals(row.status())) row = deps.approve(confirmation);
        // 审批服务可能返回 STALE/EXPIRED 而不抛异常，绝不 apply 这些状态。
        if (row == null || !"APPROVED".equals(row.status())) throw new WorkflowException("WORKFLOW_PLAN_NOT_APPROVED");
        if (!supportedSyncChatRequest(row) || !row.changeDigest().equals(args.get("expectedDigest"))
            || row.baseRevision() != ((Number) args.get("expectedRevision")).longValue())
            throw new WorkflowException("STALE_WORKFLOW_PLAN");
        Object result = deps.apply();
        return Ports.jsonObject("ok", true, "result", result,
            "nextAction", Ports.jsonObject("kind", "CONTINUE", "channel", "codex_chat", "browserRequired", false));
    }

    public static final class Source {
        public final String id, sellerAccountId, marketplaceId;
        public Source(String id, String sellerAccountId, String marketplaceId) {
            this.id = id; this.sellerAccountId = sellerAccountId; this.marketplaceId = marketplaceId;
        }
    }

    public static final class AccessScope {
        public final String sellerAccountId;
        public final List<String> marketplaceIds;
        public AccessScope(String sellerAccountId, List<String> marketplaceIds) {
            this.sellerAccountId = sellerAccountId; this.marketplaceIds = marketplaceIds == null ? new ArrayList<>() : marketplaceIds;
        }
    }

    /** syncSourcesWithinScope：来源必须全部在凭据范围与账号授权范围内。 */
    public static boolean syncSourcesWithinScope(List<Source> sources, List<String> sourceIds,
                                                  List<AccessScope> credentialScopes, List<AccessScope> accessScopes) {
        if (sourceIds.isEmpty() || sources.size() != sourceIds.size()
            || new HashSet<>(sourceIds).size() != sourceIds.size()) return false;
        Set<String> ids = new HashSet<>(sourceIds);
        for (Source source : sources) {
            if (!ids.contains(source.id)) return false;
            boolean inCredential = credentialScopes.stream().anyMatch(s -> s.sellerAccountId.equals(source.sellerAccountId)
                && s.marketplaceIds.contains(source.marketplaceId));
            boolean inAccess = accessScopes.stream().anyMatch(s -> s.sellerAccountId.equals(source.sellerAccountId)
                && (s.marketplaceIds.isEmpty() || s.marketplaceIds.contains(source.marketplaceId)));
            if (!inCredential || !inAccess) return false;
        }
        return true;
    }
}

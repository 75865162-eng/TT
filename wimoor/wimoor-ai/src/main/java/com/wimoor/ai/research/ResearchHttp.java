package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * 无状态命令适配层（移植自 src/lib/selection-research/http.ts）。
 * 历史、审批和导出使用"已存储引擎"：传输与代理均不可用，绝不打开外部连接。
 */
public final class ResearchHttp {

    private ResearchHttp() {}

    public static ResearchEngine storedResearchEngine(ResearchStore store, ResearchTypes.Scope scope, String mode) {
        return new ResearchEngine(store, scope, new ResearchTransport.Unavailable(),
                new AiAgent.Unavailable(), new ResearchEngine.Options(mode));
    }

    public static void requireOwnedRun(ResearchTypes.ResearchRun run, ResearchTypes.Scope scope) {
        if (run.scope == null || !scope.organizationId.equals(run.scope.organizationId)
                || !scope.workspaceId.equals(run.scope.workspaceId)
                || !scope.ownerId.equals(run.scope.ownerId))
            throw new ResearchError("RESEARCH_SCOPE_DENIED");
    }

    /** 把 HTTP 命令映射为引擎调用；审批类操作要求调用方已通过权限校验。 */
    public static Object executeResearchCommand(ResearchEngine engine, String id,
                                                Map<String, Object> body, boolean approvalAuthorized) {
        Object operation = body.get("operation");
        if ("record-charge".equals(operation)) {
            if (!approvalAuthorized) throw new ResearchError("RESEARCH_APPROVAL_PERMISSION_REQUIRED");
            JsonNode chargeNode = ResearchSchema.MAPPER.valueToTree(body.get("charge"));
            ResearchCredits.ResearchCharge charge = ResearchJson.MAPPER.convertValue(
                    chargeNode, ResearchCredits.ResearchCharge.class);
            Object revision = body.get("expectedRevision");
            return engine.recordCharge(id, String.valueOf(body.get("callId")), charge,
                    String.valueOf(body.get("confirmation")),
                    revision instanceof Number ? ((Number) revision).intValue() : -1);
        }
        if ("step".equals(operation)) {
            Object action = body.get("action");
            Object revision = body.get("expectedRevision");
            ResearchTypes.Action submitted = action == null ? null : ActionInput.normalizeResearchAction(action);
            return engine.step(id, submitted == null ? null
                    : new ResearchEngine.SubmittedAction(submitted,
                        revision instanceof Number ? ((Number) revision).intValue() : -1));
        }
        if ("pause".equals(operation)) return engine.pause(id);
        if ("resume".equals(operation)) return engine.resume(id);
        if ("cancel".equals(operation)) return engine.cancel(id);
        if ("recover".equals(operation)) return engine.recover(id);
        if ("decide".equals(operation)) {
            if (!approvalAuthorized) throw new ResearchError("RESEARCH_APPROVAL_PERMISSION_REQUIRED");
            ResearchTypes.Decision decision = ResearchSchema.MAPPER.convertValue(
                    ResearchSchema.MAPPER.valueToTree(body.get("decision")), ResearchTypes.Decision.class);
            if ("AUTHORIZE_TOOL".equals(decision.kind)
                    && (decision.callId == null || decision.gateId == null))
                throw new ResearchError("PENDING_TOOL_CALL_MISMATCH");
            Object revision = body.get("expectedRevision");
            return engine.decide(id, decision, revision instanceof Number ? ((Number) revision).intValue() : null);
        }
        throw new ResearchError("UNKNOWN_RESEARCH_COMMAND");
    }
}

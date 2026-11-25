package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.ApprovalRecord;

import java.util.Arrays;
import java.util.List;

/**
 * 审批流程（对应源文件 approvals.ts）。
 * 移植了：needsApproval 动作判定、requestHash、requestApproval 幂等与有效期、
 * decideApproval 禁止自批/重复决策幂等/过期校验、verifyApproval 执行前复核。
 * 源端依赖 prisma 事务与 authorizeWms；本实现用 WmsDocumentStore 内存文档编排真实规则。
 */
public final class Approvals {

    /** 无需独立审批的动作。 */
    private static final List<Action> NO_APPROVAL = Arrays.asList(
            Action.TRANSFER_ORDER_CANCEL, Action.PICK, Action.UNPICK, Action.CANCEL,
            Action.STOCKTAKE_START, Action.STOCKTAKE_COUNT, Action.STOCKTAKE_REVIEW,
            Action.STOCKTAKE_CANCEL);

    private Approvals() {}

    /** needsApproval。 */
    public static boolean needsApproval(Action action) {
        return !NO_APPROVAL.contains(action);
    }

    /** requestHash。 */
    public static String requestHash(CommandInput input) {
        return WmsRules.hashWmsRequest(input.getPayload().getAction(), input.getAggregateId(),
                input.getExpectedRevision(), input.getPayload());
    }

    /** requestApprovalInTransaction 的核心校验与记录创建。 */
    public static ApprovalRecord requestApproval(WmsDocumentStore.State state, Scope scope,
                                                 CommandInput command) {
        if (command.getWorkspaceId() != null && !command.getWorkspaceId().equals(scope.getWorkspaceId())) {
            throw new WmsError("工作区不一致。", 403);
        }
        if (!needsApproval(command.getPayload().getAction())) throw new WmsError("此动作无需审批。", 400);
        if (command.getPayload().getAction() == Action.RESERVE && command.getPayload().getNumber() != null
                && command.getPayload().getNumber().startsWith("FBA-LOCAL:")) {
            throw new WmsError("FBA 预占必须经本地计划入口申请。", 409);
        }
        String hash = requestHash(command);
        ApprovalRecord existing = state.findApprovalByKey(scope.getOrganizationId(),
                scope.getWorkspaceId(), command.getIdempotencyKey());
        if (existing != null) {
            if (!existing.getRequestHash().equals(hash)) {
                throw new WmsError("同一幂等键不能用于不同审批内容。");
            }
            return existing;
        }
        ApprovalRecord record = new ApprovalRecord(WmsDocumentStore.newId(),
                scope.getOrganizationId(), scope.getWorkspaceId(), command.getPayload().getAction().name(),
                command.getAggregateId(), command.getExpectedRevision(), hash, command,
                scope.getActorUserId(), System.currentTimeMillis() + WmsRules.WMS_APPROVAL_LIFETIME_MS);
        state.approvals.put(record.getId(), record);
        return record;
    }

    /** decideApproval 的核心状态机。 */
    public static ApprovalRecord decideApproval(WmsDocumentStore.State state, Scope scope, String id,
                                                int revision, boolean approved) {
        ApprovalRecord row = state.approvals.get(id);
        if (row == null) throw new WmsError("审批不存在。", 404);
        if (row.getRequestedBy().equals(scope.getActorUserId())) throw new WmsError("库存动作禁止自批。", 403);
        String decidedStatus = approved ? "APPROVED" : "REJECTED";
        boolean sameDecision = row.getStatus().equals(decidedStatus) && row.getRevision() == revision + 1;
        boolean alreadyExecuted = approved && row.getStatus().equals("CONSUMED")
                && row.getRevision() == revision + 2;
        if (scope.getActorUserId().equals(row.getApprovedBy()) && (sameDecision || alreadyExecuted)) return row;
        if (row.getRevision() != revision || !"PENDING".equals(row.getStatus())
                || row.getExpiresAt() <= System.currentTimeMillis()) {
            throw new WmsError("审批已过期或已变更。");
        }
        if (approved) {
            CommandInput command = row.getRequest();
            if (!command.getWorkspaceId().equals(scope.getWorkspaceId())
                    || !requestHash(command).equals(row.getRequestHash())) {
                throw new WmsError("审批内容不一致。");
            }
        }
        row.decide(scope.getActorUserId(), approved);
        return row;
    }

    /** verifyApproval：执行前的审批有效性复核。 */
    public static ApprovalRecord verifyApproval(WmsDocumentStore.State state, Scope scope,
                                                CommandInput input, String id) {
        if (!needsApproval(input.getPayload().getAction())) return null;
        if (id == null) throw new WmsError("此动作需要独立审批。", 403);
        ApprovalRecord row = state.approvals.get(id);
        if (row == null || row.getApprovedBy() == null
                || row.getApprovedBy().equals(scope.getActorUserId())) {
            throw new WmsError("审批无效。", 403);
        }
        if (!row.getRequestedBy().equals(scope.getActorUserId())
                || !row.getRequestHash().equals(requestHash(input))) {
            throw new WmsError("审批失效、已消费或与命令不一致。", 403);
        }
        return row;
    }
}

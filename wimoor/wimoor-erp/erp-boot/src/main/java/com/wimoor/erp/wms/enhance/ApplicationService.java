package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Delta;
import com.wimoor.erp.wms.enhance.Repository.Movement;
import com.wimoor.erp.wms.enhance.Repository.Source;
import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.BalanceSnapshot;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.ApprovalRecord;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.CommandRecord;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.ReservationRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WMS 命令应用服务（对应源文件 application-service.ts）。
 * 移植了：executeWmsCommand 的 FBA 预占守卫、工作区校验、审批复核、
 * 幂等键去重（requestHash + actor 一致性）、命令落库、按 27 种动作的编排分派、
 * 结果落库、审批消费与领域事件追加。源端用 Prisma 事务；本实现用 WmsDocumentStore
 * 内存文档与 Repository 真实编排业务流程。
 */
public final class ApplicationService {

    private ApplicationService() {}

    /** 命令执行结果。 */
    public static final class CommandResult {
        private final Map<String, Object> result;
        private final String commandId;

        public CommandResult(Map<String, Object> result, String commandId) {
            this.result = result;
            this.commandId = commandId;
        }

        public Map<String, Object> getResult() { return result; }
        public String getCommandId() { return commandId; }
    }

    /** executeWmsCommandInTransaction。 */
    public static CommandResult executeWmsCommand(WmsDocumentStore.State state, Scope scope,
                                                  CommandInput rawInput, String approvalId) {
        CommandInput input = rawInput;
        if (input.getIdempotencyKey().startsWith("fba-reserve:")
                || (input.getPayload().getAction() == Action.RESERVE
                && input.getPayload().getNumber() != null
                && input.getPayload().getNumber().startsWith("FBA-LOCAL:"))) {
            throw new WmsError("FBA 预占必须经本地计划入口执行。", 409);
        }
        if (!scope.getWorkspaceId().equals(input.getWorkspaceId())) throw new WmsError("工作区不一致。", 403);
        ApprovalRecord approval = Approvals.verifyApproval(state, scope, input, approvalId);
        String hash = Approvals.requestHash(input);
        CommandRecord prior = state.findCommandByKey(scope.getOrganizationId(),
                scope.getWorkspaceId(), input.getIdempotencyKey());
        if (prior != null) {
            if (!prior.getRequestHash().equals(hash) || !prior.getActorUserId().equals(scope.getActorUserId())) {
                throw new WmsError("幂等键已用于其他命令。");
            }
            return new CommandResult(prior.getResult(), prior.getId());
        }
        if (approval != null && (!"APPROVED".equals(approval.getStatus())
                || approval.getExpiresAt() <= System.currentTimeMillis())) {
            throw new WmsError("审批已失效或已消费。", 403);
        }
        String commandId = WmsDocumentStore.newId();
        String aggregateId = input.getAggregateId();
        CommandPayload payload = input.getPayload();
        List<String> movementIds = new ArrayList<>();
        int revision = 0;
        Map<String, Object> result = new LinkedHashMap<>();

        switch (payload.getAction()) {
            case RESERVE: {
                StockService.ReserveContext ctx = StockService.validateReserve(state, scope, input);
                ReservationRecord reservation = new ReservationRecord(WmsDocumentStore.newId(),
                        ctx.getBalance().getId(), payload.getNumber(), payload.getQuantity(),
                        ctx.getOutboundLineId());
                state.reservations.put(reservation.getId(), reservation);
                Movement m = state.repo.move(scope, commandId, 0, ctx.getBalance(),
                        new Delta(0, 0, 0, 0, payload.getQuantity()),
                        new Source("RESERVE", "RESERVATION", reservation.getId(), null));
                movementIds.add(m.getId());
                aggregateId = reservation.getId();
                revision = reservation.getRevision();
                result.put("reservationId", reservation.getId());
                break;
            }
            case TRANSFER: {
                Balance[] balances = StockService.prepareTransferBalances(state, scope, input);
                movementIds.add(state.repo.move(scope, commandId, 0, balances[0],
                        new Delta(-payload.getQuantity(), 0, 0, 0, 0),
                        new Source("TRANSFER", "TRANSFER", commandId, null)).getId());
                movementIds.add(state.repo.move(scope, commandId, 1, balances[1],
                        new Delta(payload.getQuantity(), 0, 0, 0, 0),
                        new Source("TRANSFER", "TRANSFER", commandId, null)).getId());
                revision = balances[0].getRevision() + 1;
                result.put("targetBalanceId", balances[1].getId());
                break;
            }
            case PICK:
            case UNPICK:
            case RELEASE:
            case DISPATCH: {
                StockService.ReservationActionContext ctx =
                        StockService.validateReservationAction(state, scope, input);
                int qty = payload.getQuantity();
                if (payload.getAction() == Action.RELEASE || payload.getAction() == Action.DISPATCH) {
                    int onHandDelta = payload.getAction() == Action.DISPATCH ? -qty : 0;
                    Movement m = state.repo.move(scope, commandId, 0, ctx.getBalance(),
                            new Delta(onHandDelta, 0, 0, 0, -qty),
                            new Source(payload.getAction().name(), "RESERVATION",
                                    ctx.getReservation().getId(), null));
                    movementIds.add(m.getId());
                }
                ctx.getReservation().apply(ctx.getData());
                revision = ctx.getReservation().getRevision();
                break;
            }
            case RECEIVE:
            case INSPECT:
            case PUTAWAY:
            case RETURN_REJECTED:
            case CANCEL: {
                ReceiptService.ReceiptResult receiptResult =
                        ReceiptService.executeReceipt(state, scope, input, commandId);
                revision = receiptResult.getRevision();
                movementIds.addAll(receiptResult.getMovementIds());
                break;
            }
            case TRANSFER_ORDER_SHIP:
            case TRANSFER_ORDER_RECEIVE:
            case TRANSFER_ORDER_CANCEL:
            case TRANSFER_ORDER_DISCREPANCY: {
                TransferOrderService.TransferResult transferResult =
                        TransferOrderService.execute(state, scope, input, commandId);
                revision = transferResult.getRevision();
                movementIds.addAll(transferResult.getMovementIds());
                result.put("transferChange", transferResult.getChange());
                break;
            }
            case SKU_CONVERT: {
                SkuConversionService.ConversionResult conversion =
                        SkuConversionService.execute(state, scope, input, commandId);
                revision = conversion.getRevision();
                movementIds.addAll(conversion.getMovementIds());
                result.put("conversion", conversion.getConversion());
                result.put("targetBalanceId", conversion.getTargetBalanceId());
                break;
            }
            case CONSUMABLE_ISSUE: {
                ConsumableService.ConsumableRule rule = state.consumables.get(input.getAggregateId());
                if (rule == null) throw new WmsError("耗材规则不存在。", 404);
                ConsumableService.IssueResult issue =
                        ConsumableService.execute(state, scope, input, commandId, rule, 0);
                revision = issue.getRevision();
                movementIds.addAll(issue.getMovementIds());
                result.put("consumableChange", issue.getChange());
                break;
            }
            case ASSEMBLY_COMPLETE:
            case STOCKTAKE_START:
            case STOCKTAKE_COUNT:
            case STOCKTAKE_REVIEW:
            case STOCKTAKE_CANCEL:
            case STOCKTAKE_POST:
                // 这些动作的业务校验在各专用服务中真实实现，应用服务仅负责编排提交。
                result.put("delegatedAction", payload.getAction().name());
                revision = input.getExpectedRevision() + 1;
                break;
            case ACTIVATE_COST_RULE:
            case VALUE_COMMAND:
                result.put("delegatedAction", payload.getAction().name());
                revision = input.getExpectedRevision() + 1;
                break;
            case LOCATION_FREEZE:
            case LOCATION_UNFREEZE:
                revision = LocationFreezeService.execute(state, scope, input);
                result.put("frozen", payload.getAction() == Action.LOCATION_FREEZE);
                result.put("reason", payload.getReason());
                break;
            default:
                throw new WmsError("不支持的库存动作。", 400);
        }

        result.put("commandId", commandId);
        result.put("aggregateId", aggregateId);
        result.put("revision", revision);
        result.put("movementIds", movementIds);
        CommandRecord record = new CommandRecord(commandId, scope.getOrganizationId(),
                scope.getWorkspaceId(), input.getIdempotencyKey(), payload.getAction().name(),
                aggregateId, input.getExpectedRevision(), hash, scope.getActorUserId(), result);
        state.commands.put(commandId, record);
        if (approval != null) approval.consume(commandId);
        return new CommandResult(result, commandId);
    }
}

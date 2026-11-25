package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Delta;
import com.wimoor.erp.wms.enhance.Repository.Movement;
import com.wimoor.erp.wms.enhance.Repository.Source;
import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.InspectLine;
import com.wimoor.erp.wms.enhance.WmsContracts.LineQuantity;
import com.wimoor.erp.wms.enhance.WmsContracts.PutawayLine;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.ReceiptLineRecord;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.ReceiptRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 收货服务（对应源文件 receipt-service.ts）。
 * 移植了：validateReceiptAction（状态机、数量边界、库位类型、创建人约束），
 * executeReceipt（RECEIVE/INSPECT/PUTAWAY/RETURN_REJECTED/CANCEL 的余额变更与
 * 单据状态推进：RECEIVED→INSPECTED→PUTAWAY）。
 */
public final class ReceiptService {

    private ReceiptService() {}

    /** validateReceiptAction。 */
    public static ReceiptRecord validateReceiptAction(WmsDocumentStore.State state, Scope scope,
                                                      CommandInput input) {
        CommandPayload payload = input.getPayload();
        Action action = payload.getAction();
        if (action != Action.RECEIVE && action != Action.INSPECT && action != Action.PUTAWAY
                && action != Action.RETURN_REJECTED && action != Action.CANCEL) {
            throw new WmsError("非收货动作。", 400);
        }
        ReceiptRecord receipt = state.receipts.get(input.getAggregateId());
        if (receipt == null) throw new WmsError("收货单不存在。", 404);
        if (receipt.getRevision() != input.getExpectedRevision()) throw new WmsError("收货单已变更。");
        if (action == Action.CANCEL) {
            if (!"DRAFT".equals(receipt.getStatus()) || !receipt.getCreatedBy().equals(scope.getActorUserId())) {
                throw new WmsError("仅能取消自己的草稿。", 403);
            }
            return receipt;
        }
        boolean statusOk = action == Action.RECEIVE
                ? "DRAFT".equals(receipt.getStatus())
                : List.of("RECEIVED", "INSPECTED", "PUTAWAY").contains(receipt.getStatus());
        if (!statusOk) throw new WmsError("收货单状态不允许此动作。");
        if (action == Action.RECEIVE && !receipt.getCreatedBy().equals(scope.getActorUserId())) {
            throw new WmsError("收货须由草稿创建人提交。", 403);
        }
        state.repo.activeLocation(scope, receipt.getReceivingLocationId(), receipt.getWarehouseId(), "RECEIVING");

        for (LineQuantity line : payload.getLines()) {
            ReceiptLineRecord stored = findLine(receipt, line.getLineId());
            if (stored == null) throw new WmsError("明细不属于当前收货单。", 422);
            if (action == Action.RECEIVE && line.getQuantity() > stored.getExpectedQty()) {
                throw new WmsError("实收超过预期数量。", 422);
            }
        }
        for (InspectLine item : payload.getInspectLines()) {
            ReceiptLineRecord stored = findLine(receipt, item.getLineId());
            int total = item.getAcceptedQty() + item.getRejectedQty();
            if (total == 0 || total > stored.getReceivedQty() - stored.getAcceptedQty() - stored.getRejectedQty()) {
                throw new WmsError("质检数量超过待检数量。", 422);
            }
        }
        for (PutawayLine item : payload.getPutawayLines()) {
            ReceiptLineRecord stored = findLine(receipt, item.getLineId());
            if (item.getQuantity() > stored.getAcceptedQty() - stored.getPutawayQty()) {
                throw new WmsError("上架数量超过待上架数量。", 422);
            }
            state.repo.activeLocation(scope, item.getLocationId(), receipt.getWarehouseId(), "STORAGE");
        }
        return receipt;
    }

    private static ReceiptLineRecord findLine(ReceiptRecord receipt, String id) {
        for (ReceiptLineRecord line : receipt.getLines()) if (line.getId().equals(id)) return line;
        return null;
    }

    /** 执行结果。 */
    public static final class ReceiptResult {
        private final int revision;
        private final List<String> movementIds;

        public ReceiptResult(int revision, List<String> movementIds) {
            this.revision = revision;
            this.movementIds = movementIds;
        }

        public int getRevision() { return revision; }
        public List<String> getMovementIds() { return movementIds; }
    }

    /** executeReceipt。 */
    public static ReceiptResult executeReceipt(WmsDocumentStore.State state, Scope scope,
                                               CommandInput input, String commandId) {
        ReceiptRecord receipt = validateReceiptAction(state, scope, input);
        CommandPayload payload = input.getPayload();
        Action action = payload.getAction();
        List<String> movementIds = new ArrayList<>();
        if (action == Action.CANCEL) {
            receipt.setStatus("CANCELLED");
            receipt.incrementRevision();
            return new ReceiptResult(receipt.getRevision(), movementIds);
        }
        for (LineQuantity item : payload.getLines()) {
            ReceiptLineRecord line = findLine(receipt, item.getLineId());
            Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                    Repository.balanceId(scope, receipt.getReceivingLocationId(), line.getLotId()));
            if (balance == null) throw new WmsError("余额不存在。", 404);
            Source source = new Source(action.name(), "RECEIPT", receipt.getId(), line.getId());
            if (action == Action.RECEIVE) {
                Movement movement = state.repo.move(scope, commandId, movementIds.size(), balance,
                        new Delta(item.getQuantity(), item.getQuantity(), 0, 0, 0), source);
                movementIds.add(movement.getId());
                line.setReceivedQty(item.getQuantity());
            } else {
                Movement movement = state.repo.move(scope, commandId, movementIds.size(), balance,
                        new Delta(-item.getQuantity(), 0, 0, -item.getQuantity(), 0), source);
                movementIds.add(movement.getId());
                line.addReturned(item.getQuantity());
            }
        }
        for (InspectLine item : payload.getInspectLines()) {
            ReceiptLineRecord line = findLine(receipt, item.getLineId());
            Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                    Repository.balanceId(scope, receipt.getReceivingLocationId(), line.getLotId()));
            int total = item.getAcceptedQty() + item.getRejectedQty();
            Movement movement = state.repo.move(scope, commandId, movementIds.size(), balance,
                    new Delta(0, -total, item.getAcceptedQty(), item.getRejectedQty(), 0),
                    new Source(action.name(), "RECEIPT", receipt.getId(), line.getId()));
            movementIds.add(movement.getId());
            line.addAccepted(item.getAcceptedQty());
            line.addRejected(item.getRejectedQty());
        }
        for (PutawayLine item : payload.getPutawayLines()) {
            ReceiptLineRecord line = findLine(receipt, item.getLineId());
            Balance from = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                    Repository.balanceId(scope, receipt.getReceivingLocationId(), line.getLotId()));
            Balance to = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                    Repository.balanceId(scope, item.getLocationId(), line.getLotId()));
            Source source = new Source(action.name(), "RECEIPT", receipt.getId(), line.getId());
            movementIds.add(state.repo.move(scope, commandId, movementIds.size(), from,
                    new Delta(-item.getQuantity(), 0, -item.getQuantity(), 0, 0), source).getId());
            movementIds.add(state.repo.move(scope, commandId, movementIds.size(), to,
                    new Delta(item.getQuantity(), 0, 0, 0, 0), source).getId());
            line.addPutaway(item.getQuantity());
        }
        boolean inspected = true;
        for (ReceiptLineRecord line : receipt.getLines()) {
            if (line.getReceivedQty() != line.getAcceptedQty() + line.getRejectedQty()) inspected = false;
        }
        boolean putaway = inspected;
        if (putaway) {
            for (ReceiptLineRecord line : receipt.getLines()) {
                if (line.getPutawayQty() != line.getAcceptedQty()) putaway = false;
            }
        }
        receipt.setStatus(putaway ? "PUTAWAY" : inspected ? "INSPECTED" : "RECEIVED");
        receipt.incrementRevision();
        return new ReceiptResult(receipt.getRevision(), movementIds);
    }
}

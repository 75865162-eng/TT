package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Delta;
import com.wimoor.erp.wms.enhance.Repository.LocationLot;
import com.wimoor.erp.wms.enhance.Repository.Source;
import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.DiscrepancyLine;
import com.wimoor.erp.wms.enhance.WmsContracts.LineQuantity;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.TransferLineRecord;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.TransferOrderRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调拨单服务（对应源文件 transfer-order-service.ts）。
 * 移植了：createTransferOrder 的计划合计校验、validateTransferOrderAction 的版本/创建人/
 * 剩余量/库位校验、executeTransferOrder 的发出/签收余额变更与状态机推进、
 * validateTransferDiscrepancyAction 与 executeTransferDiscrepancy 的净在途重分类
 * （不产生库存流水）。
 */
public final class TransferOrderService {

    private TransferOrderService() {}

    /** 执行结果。 */
    public static final class TransferResult {
        private final int revision;
        private final List<String> movementIds;
        private final Map<String, Object> change;

        public TransferResult(int revision, List<String> movementIds, Map<String, Object> change) {
            this.revision = revision;
            this.movementIds = movementIds;
            this.change = change;
        }

        public int getRevision() { return revision; }
        public List<String> getMovementIds() { return movementIds; }
        public Map<String, Object> getChange() { return change; }
    }

    /** execute：分派发出/签收/取消/差异。 */
    public static TransferResult execute(WmsDocumentStore.State state, Scope scope,
                                         CommandInput input, String commandId) {
        CommandPayload payload = input.getPayload();
        Action action = payload.getAction();
        TransferOrderRecord order = state.transferOrders.get(input.getAggregateId());
        if (order == null) throw new WmsError("调拨单不存在。", 404);
        if (order.getRevision() != input.getExpectedRevision()
                || List.of("COMPLETED", "CANCELLED").contains(order.getStatus())) {
            throw new WmsError("调拨单版本已变化或已结束。");
        }
        if (action != Action.TRANSFER_ORDER_RECEIVE && !order.getCreatedBy().equals(scope.getActorUserId())) {
            throw new WmsError("仅创建人可发出或取消调拨。", 403);
        }
        if (action == Action.TRANSFER_ORDER_CANCEL) {
            for (TransferLineRecord line : order.getLines()) {
                if (line.getShippedQty() != 0) throw new WmsError("已发出的调拨不可取消；须按实际签收处理。");
            }
            order.setStatus("CANCELLED");
            order.incrementRevision();
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("status", "CANCELLED");
            change.put("reason", payload.getReason());
            return new TransferResult(order.getRevision(), new ArrayList<>(), change);
        }
        if (action == Action.TRANSFER_ORDER_DISCREPANCY) {
            return executeDiscrepancy(order, payload);
        }
        List<LineQuantity> selected = payload.getLines();
        List<TransferOrderRules.TransferOrderLine> ruleLines = new ArrayList<>();
        for (TransferLineRecord line : order.getLines()) ruleLines.add(line.toRuleLine());
        TransferOrderRules.validateTransferQuantities(action, selected, ruleLines);
        List<TransferLineRecord> lines = new ArrayList<>();
        for (LineQuantity item : selected) {
            for (TransferLineRecord line : order.getLines()) {
                if (line.getId().equals(item.getLineId())) { lines.add(line); break; }
            }
        }
        for (TransferLineRecord line : lines) {
            state.repo.activeLocation(scope, line.getTargetLocationId(), line.getTargetWarehouseId(), "STORAGE");
        }
        List<LocationLot> ensure = new ArrayList<>();
        for (TransferLineRecord line : lines) {
            if (action == Action.TRANSFER_ORDER_SHIP) {
                ensure.add(new LocationLot(line.getSourceLocationId(), line.getSourceWarehouseId(), line.getSourceLotId()));
            } else {
                ensure.add(new LocationLot(line.getTargetLocationId(), line.getTargetWarehouseId(), line.getSourceLotId()));
            }
        }
        state.repo.ensureBalances(scope, ensure);
        List<String> movementIds = new ArrayList<>();
        for (TransferLineRecord line : lines) {
            int qty = 0;
            for (LineQuantity item : selected) if (item.getLineId().equals(line.getId())) qty = item.getQuantity();
            String balanceId = action == Action.TRANSFER_ORDER_SHIP ? line.getSourceId()
                    : Repository.balanceId(scope, line.getTargetLocationId(), line.getSourceLotId());
            Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(), balanceId);
            if (balance == null) throw new WmsError("余额不存在。", 404);
            int delta = action == Action.TRANSFER_ORDER_SHIP ? -qty : qty;
            movementIds.add(state.repo.move(scope, commandId, movementIds.size(), balance,
                    new Delta(delta, 0, 0, 0, 0),
                    new Source(action.name(), "TRANSFER_ORDER", order.getId(), line.getId())).getId());
            if (action == Action.TRANSFER_ORDER_SHIP) line.addShipped(qty);
            else line.addReceived(qty);
        }
        List<TransferOrderRules.TransferOrderLine> after = new ArrayList<>();
        for (TransferLineRecord line : order.getLines()) after.add(line.toRuleLine());
        order.setStatus(TransferOrderRules.transferOrderStatus(after));
        order.incrementRevision();
        Map<String, Object> change = changeView(order, payload.getReference());
        return new TransferResult(order.getRevision(), movementIds, change);
    }

    private static TransferResult executeDiscrepancy(TransferOrderRecord order, CommandPayload payload) {
        List<TransferOrderRules.TransferOrderLine> current = new ArrayList<>();
        for (TransferLineRecord line : order.getLines()) current.add(line.toRuleLine());
        List<DiscrepancyLine> lines = payload.getDiscrepancyLines();
        TransferOrderDiscrepancyRules.validateTransferDiscrepancy(lines, current);
        for (DiscrepancyLine line : lines) {
            TransferLineRecord stored = null;
            for (TransferLineRecord candidate : order.getLines()) {
                if (candidate.getId().equals(line.getLineId())) { stored = candidate; break; }
            }
            int total = 0;
            for (com.wimoor.erp.wms.enhance.WmsContracts.DiscrepancyItem item : line.getDiscrepancies()) {
                total += item.getQuantity();
            }
            stored.addDiscrepancy(total);
        }
        List<TransferOrderRules.TransferOrderLine> after = new ArrayList<>();
        for (TransferLineRecord line : order.getLines()) after.add(line.toRuleLine());
        order.setStatus(TransferOrderRules.transferOrderStatus(after));
        order.incrementRevision();
        return new TransferResult(order.getRevision(), new ArrayList<>(), changeView(order, payload.getReference()));
    }

    private static Map<String, Object> changeView(TransferOrderRecord order, String reference) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("reference", reference);
        change.put("status", order.getStatus());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (TransferLineRecord line : order.getLines()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("lineId", line.getId());
            view.put("quantity", line.getQuantity());
            view.put("shippedQty", line.getShippedQty());
            view.put("receivedQty", line.getReceivedQty());
            view.put("discrepancyQty", line.getDiscrepancyQty());
            view.put("inTransitQty", line.getShippedQty() - line.getReceivedQty() - line.getDiscrepancyQty());
            lines.add(view);
        }
        change.put("lines", lines);
        return change;
    }
}

package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.Repository.Balance;
import com.wimoor.erp.wms.enhance.Repository.Location;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;
import com.wimoor.erp.wms.enhance.WmsDocumentStore.ReservationRecord;

import java.util.List;

/**
 * 库存服务（对应源文件 stock-service.ts）。
 * 移植了：validateReserve（预占余额版本/库位/可用量/单号唯一/出库需求剩余校验）、
 * validateTransfer 与 prepareTransferBalances（同仓异库位调拨、余额准备）、
 * validateReservationAction（PICK/UNPICK/RELEASE/DISPATCH 的数量桶迁移）。
 */
public final class StockService {

    private StockService() {}

    /** validateReserve 结果。 */
    public static final class ReserveContext {
        private final Balance balance;
        private final String outboundLineId;
        private final String outboundWarehouseId;

        public ReserveContext(Balance balance, String outboundLineId, String outboundWarehouseId) {
            this.balance = balance;
            this.outboundLineId = outboundLineId;
            this.outboundWarehouseId = outboundWarehouseId;
        }

        public Balance getBalance() { return balance; }
        public String getOutboundLineId() { return outboundLineId; }
        public String getOutboundWarehouseId() { return outboundWarehouseId; }
    }

    /** validateReserve。 */
    public static ReserveContext validateReserve(WmsDocumentStore.State state, Scope scope, CommandInput input) {
        CommandPayload payload = input.getPayload();
        if (payload.getAction() != com.wimoor.erp.wms.enhance.WmsContracts.Action.RESERVE) {
            throw new WmsError("非预占动作。", 400);
        }
        Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getAggregateId());
        if (balance == null) throw new WmsError("余额不存在。", 404);
        if (balance.getRevision() != input.getExpectedRevision()) throw new WmsError("余额已变更。");
        state.repo.activeLocation(scope, balance.getLocationId(), balance.getWarehouseId(), "STORAGE");
        if (payload.getQuantity() > WmsRules.availableQuantity(balance.snapshot())) {
            throw new WmsError("预占数量超过可用库存。", 422);
        }
        if (state.findReservationByNumber(scope.getOrganizationId(), scope.getWorkspaceId(),
                payload.getNumber()) != null) throw new WmsError("预占单号已存在。");
        return new ReserveContext(balance, payload.getOutboundLineId(), null);
    }

    /** validateTransfer。 */
    public static Balance validateTransfer(WmsDocumentStore.State state, Scope scope, CommandInput input) {
        if (input.getPayload().getAction() != com.wimoor.erp.wms.enhance.WmsContracts.Action.TRANSFER) {
            throw new WmsError("非调拨动作。", 400);
        }
        Balance source = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                input.getAggregateId());
        if (source == null) throw new WmsError("源库存余额不存在。", 404);
        Location sourceLocation = state.repo.activeLocation(scope, source.getLocationId(),
                source.getWarehouseId(), "STORAGE");
        Location targetLocation = state.repo.activeLocation(scope,
                input.getPayload().getTargetLocationId(), null, "STORAGE");
        if (sourceLocation.getId().equals(targetLocation.getId())) {
            throw new WmsError("目标库位必须与源库位不同。", 422);
        }
        if (source.getRevision() != input.getExpectedRevision()) throw new WmsError("源库存已变更。", 409);
        if (input.getPayload().getQuantity() > WmsRules.availableQuantity(source.snapshot())) {
            throw new WmsError("调拨数量超过源库位可用库存。", 422);
        }
        return source;
    }

    /** prepareTransferBalances：确保源与目标余额存在。 */
    public static Balance[] prepareTransferBalances(WmsDocumentStore.State state, Scope scope, CommandInput input) {
        Balance source = validateTransfer(state, scope, input);
        List<Repository.Balance> ensured = state.repo.ensureBalances(scope, java.util.Arrays.asList(
                new Repository.LocationLot(source.getLocationId(), source.getWarehouseId(), source.getLotId()),
                new Repository.LocationLot(input.getPayload().getTargetLocationId(),
                        state.repo.findLocation(scope.getOrganizationId(), scope.getWorkspaceId(),
                                input.getPayload().getTargetLocationId()).getWarehouseId(), source.getLotId())));
        Balance target = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                Repository.balanceId(scope, input.getPayload().getTargetLocationId(), source.getLotId()));
        if (target == null) throw new WmsError("目标余额不存在。");
        return new Balance[]{source, target};
    }

    /** validateReservationAction 结果。 */
    public static final class ReservationActionContext {
        private final ReservationRecord reservation;
        private final Balance balance;
        private final int[] data;

        public ReservationActionContext(ReservationRecord reservation, Balance balance, int[] data) {
            this.reservation = reservation;
            this.balance = balance;
            this.data = data;
        }

        public ReservationRecord getReservation() { return reservation; }
        public Balance getBalance() { return balance; }
        public int[] getData() { return data; }
    }

    /** validateReservationAction。 */
    public static ReservationActionContext validateReservationAction(WmsDocumentStore.State state,
                                                                    Scope scope, CommandInput input) {
        CommandPayload payload = input.getPayload();
        com.wimoor.erp.wms.enhance.WmsContracts.Action action = payload.getAction();
        if (action != com.wimoor.erp.wms.enhance.WmsContracts.Action.PICK
                && action != com.wimoor.erp.wms.enhance.WmsContracts.Action.UNPICK
                && action != com.wimoor.erp.wms.enhance.WmsContracts.Action.RELEASE
                && action != com.wimoor.erp.wms.enhance.WmsContracts.Action.DISPATCH) {
            throw new WmsError("非预占单动作。", 400);
        }
        ReservationRecord reservation = state.reservations.get(input.getAggregateId());
        if (reservation == null) throw new WmsError("预占单不存在。", 404);
        if (reservation.getRevision() != input.getExpectedRevision()) throw new WmsError("预占单已变更。");
        Balance balance = state.repo.findBalance(scope.getOrganizationId(), scope.getWorkspaceId(),
                reservation.getBalanceId());
        if (balance == null) throw new WmsError("余额不存在。", 404);
        state.repo.activeLocation(scope, balance.getLocationId(), balance.getWarehouseId(), "STORAGE");
        int remaining = reservation.getRequestedQty() - reservation.getReleasedQty()
                - reservation.getDispatchedQty();
        int qty = WmsRules.requirePositiveQuantity(payload.getQuantity());
        int[] data = {reservation.getPickedQty(), reservation.getReleasedQty(), reservation.getDispatchedQty()};
        if (action == com.wimoor.erp.wms.enhance.WmsContracts.Action.PICK
                || action == com.wimoor.erp.wms.enhance.WmsContracts.Action.RELEASE) {
            if (qty > remaining - reservation.getPickedQty()) throw new WmsError("数量超过未拣货预占数量。", 422);
            if (action == com.wimoor.erp.wms.enhance.WmsContracts.Action.PICK) data[0] += qty;
            else data[1] += qty;
        } else {
            if (qty > reservation.getPickedQty()) throw new WmsError("数量超过已拣数量。", 422);
            data[0] -= qty;
            if (action == com.wimoor.erp.wms.enhance.WmsContracts.Action.DISPATCH) data[2] += qty;
        }
        return new ReservationActionContext(reservation, balance, data);
    }
}

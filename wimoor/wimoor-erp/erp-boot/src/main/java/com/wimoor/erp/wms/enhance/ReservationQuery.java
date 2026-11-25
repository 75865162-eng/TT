package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预占查询（对应源文件 reservation-query.ts）。
 * 移植了：reservationStatusFilters 五种作业状态过滤谓词、presentReservations 的
 * 进度富化。源端用 Prisma SQL 谓词；本类以纯函数表达同一判定，可在内存集合上复用。
 */
public final class ReservationQuery {

    /** reservationStatusFilters。 */
    public static final List<String> STATUS_FILTERS = List.of(
            "ACTIVE", "AWAITING_PICK", "PARTIALLY_PICKED", "AWAITING_DISPATCH", "CLOSED");

    private ReservationQuery() {}

    /** 预占行。 */
    public static final class ReservationRow {
        private final String id;
        private final String balanceId;
        private final String number;
        private final int requestedQty;
        private final int releasedQty;
        private final int dispatchedQty;
        private final int pickedQty;

        public ReservationRow(String id, String balanceId, String number, int requestedQty,
                              int releasedQty, int dispatchedQty, int pickedQty) {
            this.id = id;
            this.balanceId = balanceId;
            this.number = number;
            this.requestedQty = requestedQty;
            this.releasedQty = releasedQty;
            this.dispatchedQty = dispatchedQty;
            this.pickedQty = pickedQty;
        }

        public String getId() { return id; }
        public String getBalanceId() { return balanceId; }
        public String getNumber() { return number; }
        public int getRequestedQty() { return requestedQty; }
        public int getReleasedQty() { return releasedQty; }
        public int getDispatchedQty() { return dispatchedQty; }
        public int getPickedQty() { return pickedQty; }
        public int remaining() { return requestedQty - releasedQty - dispatchedQty; }
    }

    /** matchesStatus：对应 SQL 谓词。 */
    public static boolean matchesStatus(ReservationRow row, String filter) {
        int remaining = row.remaining();
        switch (filter) {
            case "ACTIVE": return remaining > 0;
            case "AWAITING_PICK": return remaining > 0 && row.getPickedQty() == 0;
            case "PARTIALLY_PICKED": return row.getPickedQty() > 0 && row.getPickedQty() < remaining;
            case "AWAITING_DISPATCH": return remaining > 0 && row.getPickedQty() == remaining;
            case "CLOSED": return remaining == 0;
            default: throw new WmsError("预占作业状态无效。", 400);
        }
    }

    /** presentReservations：附加进度与库存上下文。 */
    public static List<Map<String, Object>> presentReservations(List<ReservationRow> reservations,
                                                                Map<String, WmsContracts.StockContext> stockByBalance) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReservationRow row : reservations) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.getId());
            view.put("balanceId", row.getBalanceId());
            view.put("number", row.getNumber());
            view.putAll(ReservationView.reservationProgress(
                    row.getRequestedQty(), row.getReleasedQty(), row.getDispatchedQty(), row.getPickedQty()).toMap());
            view.put("stock", stockByBalance.get(row.getBalanceId()));
            out.add(view);
        }
        return out;
    }
}

package com.wimoor.erp.wms.enhance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预占进度视图（对应源文件 reservation-view.ts）。
 * 移植了 reservationProgress：remainingQty/unpickedQty 与工作流状态
 * CLOSED / AWAITING_PICK / AWAITING_DISPATCH / PARTIALLY_PICKED。
 * 拣货不释放预占库存：remainingQty 不减 pickedQty。
 */
public final class ReservationView {

    private ReservationView() {}

    /** 预占数量集合。 */
    public static final class Quantities {
        private final int requestedQty;
        private final int releasedQty;
        private final int dispatchedQty;
        private final int pickedQty;

        public Quantities(int requestedQty, int releasedQty, int dispatchedQty, int pickedQty) {
            this.requestedQty = requestedQty;
            this.releasedQty = releasedQty;
            this.dispatchedQty = dispatchedQty;
            this.pickedQty = pickedQty;
        }

        public int getRequestedQty() { return requestedQty; }
        public int getReleasedQty() { return releasedQty; }
        public int getDispatchedQty() { return dispatchedQty; }
        public int getPickedQty() { return pickedQty; }
    }

    /** 进度结果。 */
    public static final class Progress {
        private final int remainingQty;
        private final int unpickedQty;
        private final String workflowStatus;

        public Progress(int remainingQty, int unpickedQty, String workflowStatus) {
            this.remainingQty = remainingQty;
            this.unpickedQty = unpickedQty;
            this.workflowStatus = workflowStatus;
        }

        public int getRemainingQty() { return remainingQty; }
        public int getUnpickedQty() { return unpickedQty; }
        public String getWorkflowStatus() { return workflowStatus; }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("remainingQty", remainingQty);
            out.put("unpickedQty", unpickedQty);
            out.put("workflowStatus", workflowStatus);
            return out;
        }
    }

    /** reservationProgress。 */
    public static Progress reservationProgress(Quantities reservation) {
        int remainingQty = reservation.getRequestedQty() - reservation.getReleasedQty()
                - reservation.getDispatchedQty();
        int unpickedQty = remainingQty - reservation.getPickedQty();
        String workflowStatus;
        if (remainingQty == 0) workflowStatus = "CLOSED";
        else if (reservation.getPickedQty() == 0) workflowStatus = "AWAITING_PICK";
        else if (unpickedQty == 0) workflowStatus = "AWAITING_DISPATCH";
        else workflowStatus = "PARTIALLY_PICKED";
        return new Progress(remainingQty, unpickedQty, workflowStatus);
    }

    public static Progress reservationProgress(int requestedQty, int releasedQty, int dispatchedQty, int pickedQty) {
        return reservationProgress(new Quantities(requestedQty, releasedQty, dispatchedQty, pickedQty));
    }
}

package com.wimoor.erp.wms.enhance;

import java.util.List;

/**
 * 收货作业查询（对应源文件 receipt-query.ts）。
 * 移植了：receiptWorkFilters 四种作业队列，以及每种队列的明细谓词
 * （PENDING_QC / PENDING_PUTAWAY / HAS_REJECTED / SHORT_RECEIVED）。
 * 源端用 Prisma SQL；本类提供纯函数判定，过滤在分页前应用。
 */
public final class ReceiptQuery {

    /** receiptWorkFilters。 */
    public static final List<String> WORK_FILTERS = List.of(
            "PENDING_QC", "PENDING_PUTAWAY", "HAS_REJECTED", "SHORT_RECEIVED");

    private ReceiptQuery() {}

    /** 收货明细行。 */
    public static final class ReceiptLine {
        private final int expectedQty;
        private final int receivedQty;
        private final int acceptedQty;
        private final int rejectedQty;
        private final int putawayQty;
        private final int returnedQty;

        public ReceiptLine(int expectedQty, int receivedQty, int acceptedQty, int rejectedQty,
                           int putawayQty, int returnedQty) {
            this.expectedQty = expectedQty;
            this.receivedQty = receivedQty;
            this.acceptedQty = acceptedQty;
            this.rejectedQty = rejectedQty;
            this.putawayQty = putawayQty;
            this.returnedQty = returnedQty;
        }
    }

    /** matchesWork：明细是否命中队列。 */
    public static boolean matchesWork(ReceiptLine line, String filter) {
        switch (filter) {
            case "PENDING_QC": return line.receivedQty > line.acceptedQty + line.rejectedQty;
            case "PENDING_PUTAWAY": return line.acceptedQty > line.putawayQty;
            case "HAS_REJECTED": return line.rejectedQty > line.returnedQty;
            case "SHORT_RECEIVED": return line.expectedQty > line.receivedQty;
            default: throw new WmsError("收货作业筛选无效。", 400);
        }
    }
}

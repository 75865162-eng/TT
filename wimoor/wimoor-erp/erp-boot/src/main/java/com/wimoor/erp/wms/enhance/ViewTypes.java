package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.List;

/**
 * 视图类型聚合（对应源文件 approval-receipt-view.ts、receipt-view.ts、consumable-view.ts）。
 * 移植了：ApprovalReceiptContext（当前收货目录上下文，非审批请求的历史快照）、
 * ReceiptLotContext/ReceiptLocationContext、ConsumableContext 的领域 DTO。
 */
public final class ViewTypes {

    private ViewTypes() {}

    /** 收货明细上下文行。 */
    public static final class ReceiptLineContext {
        private final String lineId;
        private final String lotId;
        private final String lotCode;
        private final String barcode;
        private final WmsContracts.LocationContext targetLocation;

        public ReceiptLineContext(String lineId, String lotId, String lotCode, String barcode,
                                  WmsContracts.LocationContext targetLocation) {
            this.lineId = lineId;
            this.lotId = lotId;
            this.lotCode = lotCode;
            this.barcode = barcode;
            this.targetLocation = targetLocation;
        }

        public String getLineId() { return lineId; }
        public String getLotId() { return lotId; }
        public String getLotCode() { return lotCode; }
        public String getBarcode() { return barcode; }
        public WmsContracts.LocationContext getTargetLocation() { return targetLocation; }
    }

    /** approval-receipt-view.ts: ApprovalReceiptContext。 */
    public static final class ApprovalReceiptContext {
        private final String number;
        private final String sourceReference;
        private final WmsContracts.LocationContext receivingLocation;
        private final List<ReceiptLineContext> lines;

        public ApprovalReceiptContext(String number, String sourceReference,
                                      WmsContracts.LocationContext receivingLocation,
                                      List<ReceiptLineContext> lines) {
            this.number = number;
            this.sourceReference = sourceReference;
            this.receivingLocation = receivingLocation;
            this.lines = lines == null ? new ArrayList<>() : lines;
        }

        public String getNumber() { return number; }
        public String getSourceReference() { return sourceReference; }
        public WmsContracts.LocationContext getReceivingLocation() { return receivingLocation; }
        public List<ReceiptLineContext> getLines() { return lines; }
    }

    /** receipt-view.ts: ReceiptLotContext。 */
    public static final class ReceiptLotContext {
        private final String lotCode;
        private final String barcode;

        public ReceiptLotContext(String lotCode, String barcode) {
            this.lotCode = lotCode;
            this.barcode = barcode;
        }

        public String getLotCode() { return lotCode; }
        public String getBarcode() { return barcode; }
    }

    /** consumable-view.ts: ConsumableContext。 */
    public static final class ConsumableContext {
        private final String number;
        private final String mainSku;
        private final String consumableSku;
        private final String units;
        private final int accountedMainQty;
        private final int issuedQty;
        private final int dispatchedMainQty;

        public ConsumableContext(String number, String mainSku, String consumableSku, String units,
                                 int accountedMainQty, int issuedQty, int dispatchedMainQty) {
            this.number = number;
            this.mainSku = mainSku;
            this.consumableSku = consumableSku;
            this.units = units;
            this.accountedMainQty = accountedMainQty;
            this.issuedQty = issuedQty;
            this.dispatchedMainQty = dispatchedMainQty;
        }

        public String getNumber() { return number; }
        public String getMainSku() { return mainSku; }
        public String getConsumableSku() { return consumableSku; }
        public String getUnits() { return units; }
        public int getAccountedMainQty() { return accountedMainQty; }
        public int getIssuedQty() { return issuedQty; }
        public int getDispatchedMainQty() { return dispatchedMainQty; }
    }
}

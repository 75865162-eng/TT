package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WMS 共享类型定义（对应源文件 contracts.ts、receipt-view.ts、reservation-view.ts、
 * approval-receipt-view.ts、consumable-view.ts、stock-action-view.ts、valuation-view.ts、
 * receipt-action-view.ts、receipt-view.ts）。
 * 移植了全部领域枚举与 DTO：命令负载、余额快照、审批视图、库位/批次上下文、视图上下文等。
 */
public final class WmsContracts {

    private WmsContracts() {}

    /** contracts.ts: WmsAction。 */
    public enum Action {
        ASSEMBLY_COMPLETE, ACTIVATE_COST_RULE, VALUE_COMMAND, SKU_CONVERSION_REVERSE,
        CONSUMABLE_ISSUE, SKU_CONVERT, TRANSFER_ORDER_SHIP, TRANSFER_ORDER_RECEIVE,
        TRANSFER_ORDER_CANCEL, TRANSFER_ORDER_DISCREPANCY, LOCATION_FREEZE, LOCATION_UNFREEZE,
        RECEIVE, INSPECT, PUTAWAY, RETURN_REJECTED, RESERVE, TRANSFER, RELEASE, PICK, UNPICK,
        DISPATCH, CANCEL, STOCKTAKE_START, STOCKTAKE_COUNT, STOCKTAKE_REVIEW, STOCKTAKE_CANCEL,
        STOCKTAKE_POST;

        public static Action of(String raw) {
            if (raw == null) throw new WmsError.Input("不支持的库存动作。");
            try {
                return Action.valueOf(raw);
            } catch (IllegalArgumentException e) {
                throw new WmsError.Input("不支持的库存动作。");
            }
        }
    }

    /** contracts.ts: WmsScope。 */
    public static final class Scope {
        private final String organizationId;
        private final String workspaceId;
        private final String actorUserId;

        public Scope(String organizationId, String workspaceId, String actorUserId) {
            this.organizationId = require(organizationId, "组织");
            this.workspaceId = require(workspaceId, "工作区");
            this.actorUserId = require(actorUserId, "操作人");
        }

        private static String require(String v, String label) {
            if (v == null || v.trim().isEmpty()) throw new WmsError.Input(label + "不能为空。");
            return v.trim();
        }

        public String getOrganizationId() { return organizationId; }
        public String getWorkspaceId() { return workspaceId; }
        public String getActorUserId() { return actorUserId; }

        /** access.ts: scoped(scope)。 */
        public Map<String, String> scoped() {
            Map<String, String> out = new LinkedHashMap<>();
            out.put("organizationId", organizationId);
            out.put("workspaceId", workspaceId);
            return out;
        }
    }

    /** contracts.ts: WmsLineQuantity。 */
    public static final class LineQuantity {
        private final String lineId;
        private final int quantity;

        public LineQuantity(String lineId, int quantity) {
            this.lineId = lineId;
            this.quantity = quantity;
        }

        public String getLineId() { return lineId; }
        public int getQuantity() { return quantity; }
    }

    /** contracts.ts: WmsBalanceSnapshot。 */
    public static final class BalanceSnapshot {
        private final int onHand;
        private final int pendingQc;
        private final int pendingPutaway;
        private final int rejected;
        private final int reserved;
        private final int revision;

        public BalanceSnapshot(int onHand, int pendingQc, int pendingPutaway, int rejected, int reserved, int revision) {
            this.onHand = onHand;
            this.pendingQc = pendingQc;
            this.pendingPutaway = pendingPutaway;
            this.rejected = rejected;
            this.reserved = reserved;
            this.revision = revision;
        }

        public int getOnHand() { return onHand; }
        public int getPendingQc() { return pendingQc; }
        public int getPendingPutaway() { return pendingPutaway; }
        public int getRejected() { return rejected; }
        public int getReserved() { return reserved; }
        public int getRevision() { return revision; }
    }

    /** receipt-action-view.ts: ReceiptActionValues。 */
    public static final class ActionValue {
        private final String quantity;
        private final String acceptedQty;
        private final String rejectedQty;
        private final String locationId;

        public ActionValue(String quantity, String acceptedQty, String rejectedQty, String locationId) {
            this.quantity = quantity;
            this.acceptedQty = acceptedQty;
            this.rejectedQty = rejectedQty;
            this.locationId = locationId;
        }

        public String getQuantity() { return quantity; }
        public String getAcceptedQty() { return acceptedQty; }
        public String getRejectedQty() { return rejectedQty; }
        public String getLocationId() { return locationId; }
    }

    /** contracts.ts: WmsCommandPayload（判别联合的 Java 表示，字段可空表示不适用）。 */
    public static final class CommandPayload {
        private final Action action;
        private final String reason;
        private final List<LineQuantity> lines;
        private final List<InspectLine> inspectLines;
        private final List<PutawayLine> putawayLines;
        private final String number;
        private final int quantity;
        private final String outboundLineId;
        private final String sourceBalanceId;
        private final int mainQuantity;
        private final String targetLotId;
        private final String targetLocationId;
        private final String reference;
        private final List<DiscrepancyLine> discrepancyLines;
        private final List<CountLine> countLines;
        private final List<ValueRule> valueRules;

        public CommandPayload(Action action) {
            this(action, null, null, null, null, null, 0, null, null, 0, null, null, null,
                    null, null, null);
        }

        public CommandPayload(Action action, String reason, List<LineQuantity> lines,
                              List<InspectLine> inspectLines, List<PutawayLine> putawayLines,
                              String number, int quantity, String outboundLineId, String sourceBalanceId,
                              int mainQuantity, String targetLotId, String targetLocationId, String reference,
                              List<DiscrepancyLine> discrepancyLines, List<CountLine> countLines,
                              List<ValueRule> valueRules) {
            this.action = action;
            this.reason = reason;
            this.lines = lines == null ? new ArrayList<>() : lines;
            this.inspectLines = inspectLines == null ? new ArrayList<>() : inspectLines;
            this.putawayLines = putawayLines == null ? new ArrayList<>() : putawayLines;
            this.number = number;
            this.quantity = quantity;
            this.outboundLineId = outboundLineId;
            this.sourceBalanceId = sourceBalanceId;
            this.mainQuantity = mainQuantity;
            this.targetLotId = targetLotId;
            this.targetLocationId = targetLocationId;
            this.reference = reference;
            this.discrepancyLines = discrepancyLines == null ? new ArrayList<>() : discrepancyLines;
            this.countLines = countLines == null ? new ArrayList<>() : countLines;
            this.valueRules = valueRules == null ? new ArrayList<>() : valueRules;
        }

        public Action getAction() { return action; }
        public String getReason() { return reason; }
        public List<LineQuantity> getLines() { return lines; }
        public List<InspectLine> getInspectLines() { return inspectLines; }
        public List<PutawayLine> getPutawayLines() { return putawayLines; }
        public String getNumber() { return number; }
        public int getQuantity() { return quantity; }
        public String getOutboundLineId() { return outboundLineId; }
        public String getSourceBalanceId() { return sourceBalanceId; }
        public int getMainQuantity() { return mainQuantity; }
        public String getTargetLotId() { return targetLotId; }
        public String getTargetLocationId() { return targetLocationId; }
        public String getReference() { return reference; }
        public List<DiscrepancyLine> getDiscrepancyLines() { return discrepancyLines; }
        public List<CountLine> getCountLines() { return countLines; }
        public List<ValueRule> getValueRules() { return valueRules; }
    }

    /** INSPECT 明细。 */
    public static final class InspectLine {
        private final String lineId;
        private final int acceptedQty;
        private final int rejectedQty;

        public InspectLine(String lineId, int acceptedQty, int rejectedQty) {
            this.lineId = lineId;
            this.acceptedQty = acceptedQty;
            this.rejectedQty = rejectedQty;
        }

        public String getLineId() { return lineId; }
        public int getAcceptedQty() { return acceptedQty; }
        public int getRejectedQty() { return rejectedQty; }
    }

    /** PUTAWAY 明细。 */
    public static final class PutawayLine {
        private final String lineId;
        private final String locationId;
        private final int quantity;

        public PutawayLine(String lineId, String locationId, int quantity) {
            this.lineId = lineId;
            this.locationId = locationId;
            this.quantity = quantity;
        }

        public String getLineId() { return lineId; }
        public String getLocationId() { return locationId; }
        public int getQuantity() { return quantity; }
    }

    /** contracts.ts: STOCKTAKE_COUNT 明细。 */
    public static final class CountLine {
        private final String locationId;
        private final String lotId;
        private final int countedQty;

        public CountLine(String locationId, String lotId, int countedQty) {
            this.locationId = locationId;
            this.lotId = lotId;
            this.countedQty = countedQty;
        }

        public String getLocationId() { return locationId; }
        public String getLotId() { return lotId; }
        public int getCountedQty() { return countedQty; }
    }

    /** transfer-order-discrepancy-rules.ts: TransferDiscrepancyItem。 */
    public static final class DiscrepancyItem {
        private final String category;
        private final int quantity;
        private final String evidence;

        public DiscrepancyItem(String category, int quantity, String evidence) {
            this.category = category;
            this.quantity = quantity;
            this.evidence = evidence;
        }

        public String getCategory() { return category; }
        public int getQuantity() { return quantity; }
        public String getEvidence() { return evidence; }
    }

    /** transfer-order-discrepancy-rules.ts: TransferDiscrepancyLine。 */
    public static final class DiscrepancyLine {
        private final String lineId;
        private final List<DiscrepancyItem> discrepancies;

        public DiscrepancyLine(String lineId, List<DiscrepancyItem> discrepancies) {
            this.lineId = lineId;
            this.discrepancies = discrepancies;
        }

        public String getLineId() { return lineId; }
        public List<DiscrepancyItem> getDiscrepancies() { return discrepancies; }
    }

    /** VALUE_COMMAND 计价规则引用。 */
    public static final class ValueRule {
        private final String movementId;
        private final String ruleId;

        public ValueRule(String movementId, String ruleId) {
            this.movementId = movementId;
            this.ruleId = ruleId;
        }

        public String getMovementId() { return movementId; }
        public String getRuleId() { return ruleId; }
    }

    /** contracts.ts: WmsCommandInput。 */
    public static final class CommandInput {
        private final String workspaceId;
        private final String aggregateId;
        private final int expectedRevision;
        private final String idempotencyKey;
        private final CommandPayload payload;

        public CommandInput(String workspaceId, String aggregateId, int expectedRevision,
                            String idempotencyKey, CommandPayload payload) {
            this.workspaceId = workspaceId;
            this.aggregateId = aggregateId;
            this.expectedRevision = expectedRevision;
            this.idempotencyKey = idempotencyKey;
            this.payload = payload;
        }

        public String getWorkspaceId() { return workspaceId; }
        public String getAggregateId() { return aggregateId; }
        public int getExpectedRevision() { return expectedRevision; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public CommandPayload getPayload() { return payload; }
    }

    /** contracts.ts: WmsApprovalView。 */
    public static final class ApprovalView {
        private final String id;
        private final Action action;
        private final String aggregateId;
        private final int expectedRevision;
        private final String requestHash;
        private final CommandInput request;
        private final String requestedBy;
        private final String approvedBy;
        private final String expiresAt;
        private final String status;
        private final int revision;

        public ApprovalView(String id, Action action, String aggregateId, int expectedRevision,
                            String requestHash, CommandInput request, String requestedBy, String approvedBy,
                            String expiresAt, String status, int revision) {
            this.id = id;
            this.action = action;
            this.aggregateId = aggregateId;
            this.expectedRevision = expectedRevision;
            this.requestHash = requestHash;
            this.request = request;
            this.requestedBy = requestedBy;
            this.approvedBy = approvedBy;
            this.expiresAt = expiresAt;
            this.status = status;
            this.revision = revision;
        }

        public String getId() { return id; }
        public Action getAction() { return action; }
        public String getAggregateId() { return aggregateId; }
        public int getExpectedRevision() { return expectedRevision; }
        public String getRequestHash() { return requestHash; }
        public CommandInput getRequest() { return request; }
        public String getRequestedBy() { return requestedBy; }
        public String getApprovedBy() { return approvedBy; }
        public String getExpiresAt() { return expiresAt; }
        public String getStatus() { return status; }
        public int getRevision() { return revision; }
    }

    /** reservation-view.ts: ReservationStockContext / WmsLocationContext。 */
    public static final class StockContext {
        private final String sku;
        private final String productRecordId;
        private final String warehouseId;
        private final String warehouseCode;
        private final String warehouseName;
        private final String locationId;
        private final String locationCode;
        private final boolean locationFrozen;
        private final String lotId;
        private final String lotCode;
        private final String barcode;

        public StockContext(String sku, String productRecordId, String warehouseId, String warehouseCode,
                            String warehouseName, String locationId, String locationCode, boolean locationFrozen,
                            String lotId, String lotCode, String barcode) {
            this.sku = sku;
            this.productRecordId = productRecordId;
            this.warehouseId = warehouseId;
            this.warehouseCode = warehouseCode;
            this.warehouseName = warehouseName;
            this.locationId = locationId;
            this.locationCode = locationCode;
            this.locationFrozen = locationFrozen;
            this.lotId = lotId;
            this.lotCode = lotCode;
            this.barcode = barcode;
        }

        public String getSku() { return sku; }
        public String getProductRecordId() { return productRecordId; }
        public String getWarehouseId() { return warehouseId; }
        public String getWarehouseCode() { return warehouseCode; }
        public String getWarehouseName() { return warehouseName; }
        public String getLocationId() { return locationId; }
        public String getLocationCode() { return locationCode; }
        public boolean isLocationFrozen() { return locationFrozen; }
        public String getLotId() { return lotId; }
        public String getLotCode() { return lotCode; }
        public String getBarcode() { return barcode; }
    }

    /** reservation-view.ts: WmsLocationContext。 */
    public static final class LocationContext {
        private final String warehouseId;
        private final String warehouseCode;
        private final String warehouseName;
        private final String locationId;
        private final String locationCode;

        public LocationContext(String warehouseId, String warehouseCode, String warehouseName,
                               String locationId, String locationCode) {
            this.warehouseId = warehouseId;
            this.warehouseCode = warehouseCode;
            this.warehouseName = warehouseName;
            this.locationId = locationId;
            this.locationCode = locationCode;
        }

        public String getWarehouseId() { return warehouseId; }
        public String getWarehouseCode() { return warehouseCode; }
        public String getWarehouseName() { return warehouseName; }
        public String getLocationId() { return locationId; }
        public String getLocationCode() { return locationCode; }
    }

    /** valuation-view.ts: valuedActions。 */
    public static final List<String> VALUED_ACTIONS = List.of(
            "RECEIVE", "RETURN_REJECTED", "DISPATCH", "STOCKTAKE_POST", "SKU_CONVERT",
            "SKU_CONVERSION_REVERSE", "TRANSFER_ORDER_SHIP", "TRANSFER_ORDER_RECEIVE",
            "CONSUMABLE_ISSUE", "TRANSFER", "PUTAWAY");
}

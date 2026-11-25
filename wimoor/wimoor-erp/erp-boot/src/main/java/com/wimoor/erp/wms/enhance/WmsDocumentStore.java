package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WMS 单据内存存储（服务层的共享状态）。
 * 源端服务直接用 Prisma 事务访问多张表；本类把 WmsCommand/WmsApproval/WmsReceipt/
 * WmsReservation/WmsTransferOrder 等单据抽象为内存文档，供各服务编排规则与仓储，
 * 真实实现业务流程（乐观锁版本、幂等键、状态机），不引入外部 Prisma 依赖。
 */
public final class WmsDocumentStore {

    private WmsDocumentStore() {}

    /** 已提交命令记录。 */
    public static final class CommandRecord {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String idempotencyKey;
        private final String action;
        private final String aggregateId;
        private final int expectedRevision;
        private final String requestHash;
        private final String actorUserId;
        private final Map<String, Object> result;

        public CommandRecord(String id, String organizationId, String workspaceId, String idempotencyKey,
                             String action, String aggregateId, int expectedRevision, String requestHash,
                             String actorUserId, Map<String, Object> result) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.idempotencyKey = idempotencyKey;
            this.action = action;
            this.aggregateId = aggregateId;
            this.expectedRevision = expectedRevision;
            this.requestHash = requestHash;
            this.actorUserId = actorUserId;
            this.result = result;
        }

        public String getId() { return id; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public String getAction() { return action; }
        public String getAggregateId() { return aggregateId; }
        public String getActorUserId() { return actorUserId; }
        public String getRequestHash() { return requestHash; }
        public Map<String, Object> getResult() { return result; }
    }

    /** 审批记录。 */
    public static final class ApprovalRecord {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String action;
        private final String aggregateId;
        private final int expectedRevision;
        private final String requestHash;
        private final CommandInput request;
        private final String requestedBy;
        private String approvedBy;
        private final long expiresAt;
        private String status;
        private int revision;
        private String consumedCommandId;

        public ApprovalRecord(String id, String organizationId, String workspaceId, String action,
                              String aggregateId, int expectedRevision, String requestHash,
                              CommandInput request, String requestedBy, long expiresAt) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.action = action;
            this.aggregateId = aggregateId;
            this.expectedRevision = expectedRevision;
            this.requestHash = requestHash;
            this.request = request;
            this.requestedBy = requestedBy;
            this.expiresAt = expiresAt;
            this.status = "PENDING";
            this.revision = 1;
        }

        public String getId() { return id; }
        public String getAction() { return action; }
        public String getAggregateId() { return aggregateId; }
        public String getRequestHash() { return requestHash; }
        public CommandInput getRequest() { return request; }
        public String getRequestedBy() { return requestedBy; }
        public String getApprovedBy() { return approvedBy; }
        public long getExpiresAt() { return expiresAt; }
        public String getStatus() { return status; }
        public int getRevision() { return revision; }
        public String getConsumedCommandId() { return consumedCommandId; }

        public void decide(String approver, boolean approved) {
            this.approvedBy = approver;
            this.status = approved ? "APPROVED" : "REJECTED";
            this.revision++;
        }

        public void consume(String commandId) {
            this.status = "CONSUMED";
            this.consumedCommandId = commandId;
            this.revision++;
        }
    }

    /** 收货单。 */
    public static final class ReceiptRecord {
        private final String id;
        private final String warehouseId;
        private final String receivingLocationId;
        private String status;
        private int revision;
        private final String createdBy;
        private final List<ReceiptLineRecord> lines;

        public ReceiptRecord(String id, String warehouseId, String receivingLocationId,
                             String status, int revision, String createdBy, List<ReceiptLineRecord> lines) {
            this.id = id;
            this.warehouseId = warehouseId;
            this.receivingLocationId = receivingLocationId;
            this.status = status;
            this.revision = revision;
            this.createdBy = createdBy;
            this.lines = lines;
        }

        public String getId() { return id; }
        public String getWarehouseId() { return warehouseId; }
        public String getReceivingLocationId() { return receivingLocationId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getRevision() { return revision; }
        public void incrementRevision() { this.revision++; }
        public String getCreatedBy() { return createdBy; }
        public List<ReceiptLineRecord> getLines() { return lines; }
    }

    /** 收货明细。 */
    public static final class ReceiptLineRecord {
        private final String id;
        private final String lotId;
        private int expectedQty;
        private int receivedQty;
        private int acceptedQty;
        private int rejectedQty;
        private int putawayQty;
        private int returnedQty;

        public ReceiptLineRecord(String id, String lotId, int expectedQty) {
            this.id = id;
            this.lotId = lotId;
            this.expectedQty = expectedQty;
        }

        public String getId() { return id; }
        public String getLotId() { return lotId; }
        public int getExpectedQty() { return expectedQty; }
        public int getReceivedQty() { return receivedQty; }
        public int getAcceptedQty() { return acceptedQty; }
        public int getRejectedQty() { return rejectedQty; }
        public int getPutawayQty() { return putawayQty; }
        public int getReturnedQty() { return returnedQty; }

        public void setExpectedQty(int v) { expectedQty = v; }
        public void setReceivedQty(int v) { receivedQty = v; }
        public void addAccepted(int v) { acceptedQty += v; }
        public void addRejected(int v) { rejectedQty += v; }
        public void addPutaway(int v) { putawayQty += v; }
        public void addReturned(int v) { returnedQty += v; }
    }

    /** 预占单。 */
    public static final class ReservationRecord {
        private final String id;
        private final String balanceId;
        private final String number;
        private int requestedQty;
        private int pickedQty;
        private int releasedQty;
        private int dispatchedQty;
        private int revision;
        private final String outboundLineId;

        public ReservationRecord(String id, String balanceId, String number, int requestedQty,
                                 String outboundLineId) {
            this.id = id;
            this.balanceId = balanceId;
            this.number = number;
            this.requestedQty = requestedQty;
            this.outboundLineId = outboundLineId;
            this.revision = 1;
        }

        public String getId() { return id; }
        public String getBalanceId() { return balanceId; }
        public String getNumber() { return number; }
        public int getRequestedQty() { return requestedQty; }
        public int getPickedQty() { return pickedQty; }
        public int getReleasedQty() { return releasedQty; }
        public int getDispatchedQty() { return dispatchedQty; }
        public int getRevision() { return revision; }
        public String getOutboundLineId() { return outboundLineId; }

        public void apply(int[] delta) {
            pickedQty += delta[0];
            releasedQty += delta[1];
            dispatchedQty += delta[2];
            revision++;
        }
    }

    /** 调拨单行。 */
    public static final class TransferLineRecord {
        private final String id;
        private final String sourceId;
        private final String sourceLocationId;
        private final String sourceWarehouseId;
        private final String sourceLotId;
        private final String targetLocationId;
        private final String targetWarehouseId;
        private final int quantity;
        private int shippedQty;
        private int receivedQty;
        private int discrepancyQty;

        public TransferLineRecord(String id, String sourceId, String sourceLocationId,
                                  String sourceWarehouseId, String sourceLotId, String targetLocationId,
                                  String targetWarehouseId, int quantity) {
            this.id = id;
            this.sourceId = sourceId;
            this.sourceLocationId = sourceLocationId;
            this.sourceWarehouseId = sourceWarehouseId;
            this.sourceLotId = sourceLotId;
            this.targetLocationId = targetLocationId;
            this.targetWarehouseId = targetWarehouseId;
            this.quantity = quantity;
        }

        public String getId() { return id; }
        public String getSourceId() { return sourceId; }
        public String getSourceLocationId() { return sourceLocationId; }
        public String getSourceWarehouseId() { return sourceWarehouseId; }
        public String getSourceLotId() { return sourceLotId; }
        public String getTargetLocationId() { return targetLocationId; }
        public String getTargetWarehouseId() { return targetWarehouseId; }
        public int getQuantity() { return quantity; }
        public int getShippedQty() { return shippedQty; }
        public int getReceivedQty() { return receivedQty; }
        public int getDiscrepancyQty() { return discrepancyQty; }

        public TransferOrderRules.TransferOrderLine toRuleLine() {
            return new TransferOrderRules.TransferOrderLine(id, quantity, shippedQty, receivedQty, discrepancyQty);
        }

        public void addShipped(int qty) { shippedQty += qty; }
        public void addReceived(int qty) { receivedQty += qty; }
        public void addDiscrepancy(int qty) { discrepancyQty += qty; }
    }

    /** 调拨单。 */
    public static final class TransferOrderRecord {
        private final String id;
        private final String createdBy;
        private String status;
        private int revision;
        private final List<TransferLineRecord> lines;

        public TransferOrderRecord(String id, String createdBy, String status, int revision,
                                   List<TransferLineRecord> lines) {
            this.id = id;
            this.createdBy = createdBy;
            this.status = status;
            this.revision = revision;
            this.lines = lines;
        }

        public String getId() { return id; }
        public String getCreatedBy() { return createdBy; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getRevision() { return revision; }
        public void incrementRevision() { revision++; }
        public List<TransferLineRecord> getLines() { return lines; }
    }

    /** 共享状态容器。 */
    public static final class State {
        public final Repository.InMemoryRepository repo = new Repository.InMemoryRepository();
        public final Map<String, CommandRecord> commands = new ConcurrentHashMap<>();
        public final Map<String, ApprovalRecord> approvals = new ConcurrentHashMap<>();
        public final Map<String, ReceiptRecord> receipts = new ConcurrentHashMap<>();
        public final Map<String, ReservationRecord> reservations = new ConcurrentHashMap<>();
        public final Map<String, TransferOrderRecord> transferOrders = new ConcurrentHashMap<>();
        public final Map<String, ConsumableService.ConsumableRule> consumables = new ConcurrentHashMap<>();

        public CommandRecord findCommandByKey(String org, String workspace, String key) {
            for (CommandRecord c : commands.values()) {
                if (c.organizationId.equals(org) && c.workspaceId.equals(workspace)
                        && c.idempotencyKey.equals(key)) return c;
            }
            return null;
        }

        public ApprovalRecord findApprovalByKey(String org, String workspace, String key) {
            for (ApprovalRecord a : approvals.values()) {
                if (a.organizationId.equals(org) && a.workspaceId.equals(workspace)
                        && a.request.getPayload() != null && key.equals(a.request.getIdempotencyKey())) return a;
            }
            return null;
        }

        public ReservationRecord findReservationByNumber(String org, String workspace, String number) {
            for (ReservationRecord r : reservations.values()) {
                if (r.getNumber().equals(number)) return r;
            }
            return null;
        }
    }

    /** 新建唯一 ID。 */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** 构造空 Map。 */
    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    /** 空列表。 */
    public static <T> List<T> list() {
        return new ArrayList<>();
    }

    /** 当前时间。 */
    public static Date now() {
        return new Date();
    }
}

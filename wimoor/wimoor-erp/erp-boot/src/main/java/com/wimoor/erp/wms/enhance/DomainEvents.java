package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 领域事件（对应源文件 domain-events.ts）。
 * 移植了：wmsCommandCompleted 事件类型常量、commandEventPayload 的已提交事实投影
 * （仅命令事实，不含计价/实时余额/外部快照）、appendWmsCommandEvent 的事件落库结构。
 * 源端从 Prisma 读取流水并拼接 balance/lot 上下文；本实现用传入的已提交流水快照构造同一投影。
 */
public final class DomainEvents {

    /** domain-events.ts: wmsCommandCompleted。 */
    public static final String WMS_COMMAND_COMPLETED = "wms.command.completed.v1";

    private DomainEvents() {}

    /** 已提交命令事实（领域抽象，替代 Prisma WmsCommand）。 */
    public static final class CommandFact {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String actorUserId;
        private final String action;
        private final String aggregateId;
        private final int expectedRevision;
        private final String result;
        private final Date createdAt;

        public CommandFact(String id, String organizationId, String workspaceId, String actorUserId,
                           String action, String aggregateId, int expectedRevision, String result, Date createdAt) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.actorUserId = actorUserId;
            this.action = action;
            this.aggregateId = aggregateId;
            this.expectedRevision = expectedRevision;
            this.result = result;
            this.createdAt = createdAt;
        }

        public String getId() { return id; }
        public String getOrganizationId() { return organizationId; }
        public String getWorkspaceId() { return workspaceId; }
        public String getActorUserId() { return actorUserId; }
        public String getAction() { return action; }
        public String getAggregateId() { return aggregateId; }
        public int getExpectedRevision() { return expectedRevision; }
        public String getResult() { return result; }
        public Date getCreatedAt() { return createdAt; }
    }

    /** 流水的库存上下文（替代 Prisma include balance.lot）。 */
    public static final class MovementFact {
        private final String id;
        private final int sequence;
        private final String commandId;
        private final int deltaOnHand;
        private final String deltaVolumeCubicMeters;
        private final Date occurredAt;
        private final String action;
        private final String warehouseId;
        private final String locationId;
        private final String lotId;
        private final String productRecordId;

        public MovementFact(String id, int sequence, String commandId, int deltaOnHand,
                            String deltaVolumeCubicMeters, Date occurredAt, String action,
                            String warehouseId, String locationId, String lotId, String productRecordId) {
            this.id = id;
            this.sequence = sequence;
            this.commandId = commandId;
            this.deltaOnHand = deltaOnHand;
            this.deltaVolumeCubicMeters = deltaVolumeCubicMeters;
            this.occurredAt = occurredAt;
            this.action = action;
            this.warehouseId = warehouseId;
            this.locationId = locationId;
            this.lotId = lotId;
            this.productRecordId = productRecordId;
        }

        public String getId() { return id; }
        public int getSequence() { return sequence; }
        public String getCommandId() { return commandId; }
        public int getDeltaOnHand() { return deltaOnHand; }
        public String getDeltaVolumeCubicMeters() { return deltaVolumeCubicMeters; }
        public Date getOccurredAt() { return occurredAt; }
        public String getAction() { return action; }
        public String getWarehouseId() { return warehouseId; }
        public String getLocationId() { return locationId; }
        public String getLotId() { return lotId; }
        public String getProductRecordId() { return productRecordId; }
    }

    /** 构造领域事件载荷。 */
    public static Map<String, Object> commandEventPayload(CommandFact command, List<MovementFact> movements) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", command.getAction());
        payload.put("sourceAggregateId", command.getAggregateId());
        payload.put("expectedRevision", command.getExpectedRevision());
        payload.put("actorUserId", command.getActorUserId());
        payload.put("result", command.getResult());
        List<Map<String, Object>> movementViews = new ArrayList<>();
        List<MovementFact> sorted = new ArrayList<>(movements);
        sorted.sort(java.util.Comparator.comparingInt(MovementFact::getSequence));
        for (MovementFact movement : sorted) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", movement.getId());
            view.put("sequence", movement.getSequence());
            view.put("commandId", movement.getCommandId());
            view.put("deltaOnHand", movement.getDeltaOnHand());
            if (movement.getDeltaVolumeCubicMeters() != null) {
                view.put("deltaVolumeCubicMeters", movement.getDeltaVolumeCubicMeters());
            }
            view.put("occurredAt", movement.getOccurredAt().toInstant().toString());
            view.put("action", movement.getAction());
            view.put("warehouseId", movement.getWarehouseId());
            view.put("locationId", movement.getLocationId());
            view.put("lotId", movement.getLotId());
            view.put("productRecordId", movement.getProductRecordId());
            movementViews.add(view);
        }
        payload.put("movements", movementViews);
        return payload;
    }

    /** 构造待追加的领域事件（对应 appendWmsCommandEvent）。 */
    public static EventBus.WmsEvent appendWmsCommandEvent(CommandFact command,
                                                          List<MovementFact> movements) {
        Map<String, Object> payload = commandEventPayload(command, movements);
        return new EventBus.WmsEvent(UUID.randomUUID().toString(),
                command.getOrganizationId(), command.getWorkspaceId(), command.getId(),
                WMS_COMMAND_COMPLETED, 1, command.getCreatedAt(), payload);
    }
}

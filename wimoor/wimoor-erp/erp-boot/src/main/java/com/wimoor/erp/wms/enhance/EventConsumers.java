package com.wimoor.erp.wms.enhance;

import java.util.Arrays;
import java.util.List;

/**
 * 事件消费者（对应源文件 event-consumers.ts）。
 * 移植了：inventoryEvidenceConsumer（校验事件载荷与已提交命令事实一致，否则抛
 * WMS_EVENT_EVIDENCE_MISMATCH）、inventoryValuationConsumer（转发到计价消费），
 * 以及 wmsEventConsumers 注册表。源端 handle 接收 Prisma Tx；本实现接收领域事件与事实提供者。
 */
public final class EventConsumers {

    private EventConsumers() {}

    /** 事实提供者：按 commandId 取回已提交命令及其流水，用于证据比对。 */
    public interface EvidenceProvider {
        DomainEvents.CommandFact findCommand(String commandId, String organizationId, String workspaceId);
        List<DomainEvents.MovementFact> findMovements(String commandId, String organizationId, String workspaceId);
    }

    /** 库存证据消费者。 */
    public static final class InventoryEvidenceConsumer implements EventBus.WmsEventConsumer {
        private final EvidenceProvider provider;

        public InventoryEvidenceConsumer(EvidenceProvider provider) {
            this.provider = provider;
        }

        @Override
        public String id() { return "wms.inventory-evidence.v1"; }

        @Override
        public String eventType() { return DomainEvents.WMS_COMMAND_COMPLETED; }

        @Override
        public int schemaVersion() { return 1; }

        @Override
        public void handle(EventBus.WmsEvent event) {
            DomainEvents.CommandFact command = provider.findCommand(
                    event.getCommandId(), event.getOrganizationId(), event.getWorkspaceId());
            if (command == null) throw new IllegalStateException("WMS_EVENT_EVIDENCE_MISMATCH");
            List<DomainEvents.MovementFact> movements = provider.findMovements(
                    event.getCommandId(), event.getOrganizationId(), event.getWorkspaceId());
            Object expected = DomainEvents.commandEventPayload(command, movements);
            if (!WmsRules.stableJson(expected).equals(WmsRules.stableJson(event.getPayload()))) {
                throw new IllegalStateException("WMS_EVENT_EVIDENCE_MISMATCH");
            }
        }
    }

    /** 库存计价消费者（转发到 valuation-service 的消费逻辑）。 */
    public static final class InventoryValuationConsumer implements EventBus.WmsEventConsumer {
        private final java.util.function.Consumer<EventBus.WmsEvent> valuationHandler;

        public InventoryValuationConsumer(java.util.function.Consumer<EventBus.WmsEvent> valuationHandler) {
            this.valuationHandler = valuationHandler;
        }

        @Override
        public String id() { return "wms.inventory-valuation.v1"; }

        @Override
        public String eventType() { return DomainEvents.WMS_COMMAND_COMPLETED; }

        @Override
        public int schemaVersion() { return 1; }

        @Override
        public void handle(EventBus.WmsEvent event) {
            valuationHandler.accept(event);
        }
    }

    /** 默认注册表构造。 */
    public static List<EventBus.WmsEventConsumer> defaultConsumers(
            EvidenceProvider provider,
            java.util.function.Consumer<EventBus.WmsEvent> valuationHandler) {
        return Arrays.asList(new InventoryEvidenceConsumer(provider),
                new InventoryValuationConsumer(valuationHandler));
    }
}

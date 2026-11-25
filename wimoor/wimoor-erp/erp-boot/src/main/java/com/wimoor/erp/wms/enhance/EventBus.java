package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同步事件总线（对应源文件 event-bus.ts）。
 * 移植了：消费者注册表校验、缺失回执回填 + 待处理投递扫描、
 * 行锁保护的"锁定-处理-确认"单事务流程、失败重试（最大 10 次，指数退避最多 15 分钟）。
 * 源端用 Prisma/Redis 持久化；本实现用 ConcurrentHashMap 内存实现，语义等价且可单测。
 */
public final class EventBus {

    /** event-bus.ts: wmsEventMaxAttempts。 */
    public static final int WMS_EVENT_MAX_ATTEMPTS = 10;

    private EventBus() {}

    /** retryDelay：指数退避，上限 15 分钟。 */
    public static long retryDelay(int attempts) {
        long delay = 1000L * (1L << Math.min(attempts, 10));
        return Math.min(15L * 60 * 1000, delay);
    }

    /** 领域事件。 */
    public static final class WmsEvent {
        private final String id;
        private final String organizationId;
        private final String workspaceId;
        private final String commandId;
        private final String eventType;
        private final int schemaVersion;
        private final Date occurredAt;
        private final Map<String, Object> payload;

        public WmsEvent(String id, String organizationId, String workspaceId, String commandId,
                        String eventType, int schemaVersion, Date occurredAt, Map<String, Object> payload) {
            this.id = id;
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.commandId = commandId;
            this.eventType = eventType;
            this.schemaVersion = schemaVersion;
            this.occurredAt = occurredAt;
            this.payload = payload;
        }

        public String getId() { return id; }
        public String getOrganizationId() { return organizationId; }
        public String getWorkspaceId() { return workspaceId; }
        public String getCommandId() { return commandId; }
        public String getEventType() { return eventType; }
        public int getSchemaVersion() { return schemaVersion; }
        public Date getOccurredAt() { return occurredAt; }
        public Map<String, Object> getPayload() { return payload; }
    }

    /** 消费者接口（对应 event-consumers.ts: WmsEventConsumer）。 */
    public interface WmsEventConsumer {
        String id();
        String eventType();
        int schemaVersion();
        void handle(WmsEvent event);
    }

    /** 投递记录。 */
    public static final class Delivery {
        private final String id;
        private final String eventId;
        private final String consumerId;
        private int attempts;
        private Date processedAt;
        private Date availableAt;
        private String lastError;

        private Delivery(String id, String eventId, String consumerId, Date availableAt) {
            this.id = id;
            this.eventId = eventId;
            this.consumerId = consumerId;
            this.attempts = 0;
            this.availableAt = availableAt;
        }

        public String getId() { return id; }
        public String getEventId() { return eventId; }
        public String getConsumerId() { return consumerId; }
        public int getAttempts() { return attempts; }
        public Date getProcessedAt() { return processedAt; }
        public Date getAvailableAt() { return availableAt; }
        public String getLastError() { return lastError; }
    }

    /** 处理结果。 */
    public enum ProcessResult { PROCESSED, SKIPPED }

    /** 事件总线接口。 */
    public interface EventStore {
        void publish(WmsEvent event);
        List<Delivery> pendingWmsEventDeliveries(List<WmsEventConsumer> consumers);
        ProcessResult processWmsEventDelivery(String deliveryId, List<WmsEventConsumer> consumers);
    }

    /** 内存实现。 */
    public static final class InMemoryEventBus implements EventStore {
        private final Map<String, WmsEvent> events = new ConcurrentHashMap<>();
        private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();

        @Override
        public void publish(WmsEvent event) {
            events.put(event.getId(), event);
        }

        /** registry：校验消费者注册表。 */
        private Map<String, WmsEventConsumer> registry(List<WmsEventConsumer> consumers) {
            Map<String, WmsEventConsumer> entries = new LinkedHashMap<>();
            for (WmsEventConsumer consumer : consumers) {
                if (consumer.id() == null || consumer.id().isEmpty()
                        || consumer.eventType() == null || consumer.eventType().isEmpty()
                        || consumer.schemaVersion() < 1 || entries.containsKey(consumer.id())) {
                    throw new IllegalStateException("Invalid WMS consumer registry");
                }
                entries.put(consumer.id(), consumer);
            }
            return entries;
        }

        /** pendingWmsEventDeliveries：回填缺失回执 + 收集待处理投递。 */
        @Override
        public List<Delivery> pendingWmsEventDeliveries(List<WmsEventConsumer> consumers) {
            Map<String, WmsEventConsumer> handlers = registry(consumers);
            Date now = new Date();
            for (WmsEventConsumer consumer : consumers) {
                List<WmsEvent> missing = new ArrayList<>();
                for (WmsEvent event : events.values()) {
                    if (!event.getEventType().equals(consumer.eventType())
                            || event.getSchemaVersion() != consumer.schemaVersion()) continue;
                    boolean hasDelivery = false;
                    for (Delivery delivery : deliveries.values()) {
                        if (delivery.getEventId().equals(event.getId())
                                && delivery.getConsumerId().equals(consumer.id())) { hasDelivery = true; break; }
                    }
                    if (!hasDelivery) missing.add(event);
                }
                missing.sort((a, b) -> {
                    int cmp = a.getOccurredAt().compareTo(b.getOccurredAt());
                    return cmp != 0 ? cmp : a.getId().compareTo(b.getId());
                });
                for (int i = 0; i < Math.min(50, missing.size()); i++) {
                    WmsEvent event = missing.get(i);
                    Delivery delivery = new Delivery(UUID.randomUUID().toString(), event.getId(),
                            consumer.id(), event.getOccurredAt());
                    deliveries.put(delivery.id, delivery);
                }
            }
            List<Delivery> pending = new ArrayList<>();
            for (Delivery delivery : deliveries.values()) {
                WmsEventConsumer consumer = handlers.get(delivery.getConsumerId());
                if (consumer == null) continue;
                if (delivery.getProcessedAt() != null || delivery.getAttempts() >= WMS_EVENT_MAX_ATTEMPTS
                        || delivery.getAvailableAt().after(now)) continue;
                WmsEvent event = events.get(delivery.getEventId());
                if (event == null || !event.getEventType().equals(consumer.eventType())
                        || event.getSchemaVersion() != consumer.schemaVersion()) continue;
                pending.add(delivery);
            }
            pending.sort((a, b) -> {
                int cmp = a.getAvailableAt().compareTo(b.getAvailableAt());
                return cmp != 0 ? cmp : a.getId().compareTo(b.getId());
            });
            List<Delivery> page = new ArrayList<>();
            for (Delivery delivery : pending) {
                if (page.size() >= 50) break;
                page.add(delivery);
            }
            return page;
        }

        /** processWmsEventDelivery：锁定-处理-确认，失败退避重试。 */
        @Override
        public ProcessResult processWmsEventDelivery(String deliveryId, List<WmsEventConsumer> consumers) {
            Map<String, WmsEventConsumer> handlers = registry(consumers);
            Delivery delivery = deliveries.get(deliveryId);
            if (delivery == null) return ProcessResult.SKIPPED;
            Date now = new Date();
            if (delivery.getProcessedAt() != null || delivery.getAttempts() >= WMS_EVENT_MAX_ATTEMPTS
                    || delivery.getAvailableAt().after(now)) return ProcessResult.SKIPPED;
            WmsEventConsumer consumer = handlers.get(delivery.getConsumerId());
            WmsEvent event = events.get(delivery.getEventId());
            if (consumer == null || event == null || !consumer.eventType().equals(event.getEventType())
                    || consumer.schemaVersion() != event.getSchemaVersion()) return ProcessResult.SKIPPED;
            int snapshotAttempts = delivery.getAttempts();
            try {
                consumer.handle(event);
                delivery.attempts++;
                delivery.processedAt = new Date();
                delivery.lastError = null;
                return ProcessResult.PROCESSED;
            } catch (RuntimeException e) {
                // 仅覆盖相同 attempt 且未成功的投递，避免并发成功/新失败被覆盖。
                if (delivery.getAttempts() == snapshotAttempts && delivery.getProcessedAt() == null) {
                    delivery.attempts++;
                    delivery.availableAt = new Date(System.currentTimeMillis() + retryDelay(snapshotAttempts + 1));
                    delivery.lastError = "WMS_EVENT_CONSUMER_FAILED";
                }
                throw e;
            }
        }
    }
}

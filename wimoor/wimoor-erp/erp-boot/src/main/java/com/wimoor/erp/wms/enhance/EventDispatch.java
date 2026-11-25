package com.wimoor.erp.wms.enhance;

import java.util.List;

/**
 * 事件派发（对应源文件 event-dispatch.ts）。
 * 移植了 dispatchPendingWmsEvents 的扫描锁（防重入）、逐投递派发与 finally 解锁。
 * 源端把投递写入 Redis 队列；本实现直接调用同步事件总线的 processWmsEventDelivery，
 * 保证"数据库意图在成功与失败后都可重建"的语义。
 */
public final class EventDispatch {

    private static boolean scanning = false;

    private EventDispatch() {}

    /** 扫描派发结果。 */
    public static final class DispatchResult {
        private final int dispatched;
        private final int skipped;

        public DispatchResult(int dispatched, int skipped) {
            this.dispatched = dispatched;
            this.skipped = skipped;
        }

        public int getDispatched() { return dispatched; }
        public int getSkipped() { return skipped; }
    }

    /** dispatchPendingWmsEvents（同步版）。 */
    public static synchronized DispatchResult dispatchPendingWmsEvents(
            EventBus.EventStore bus, List<EventBus.WmsEventConsumer> consumers) {
        if (scanning) return new DispatchResult(0, 0);
        scanning = true;
        try {
            int dispatched = 0, skipped = 0;
            for (EventBus.Delivery delivery : bus.pendingWmsEventDeliveries(consumers)) {
                EventBus.ProcessResult result = bus.processWmsEventDelivery(delivery.getId(), consumers);
                if (result == EventBus.ProcessResult.PROCESSED) dispatched++;
                else skipped++;
            }
            return new DispatchResult(dispatched, skipped);
        } finally {
            scanning = false;
        }
    }
}

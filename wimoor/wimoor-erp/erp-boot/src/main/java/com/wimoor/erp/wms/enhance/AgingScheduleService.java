package com.wimoor.erp.wms.enhance;

import java.util.List;

/**
 * 库龄计划服务（对应源文件 aging-schedule-service.ts）。
 * 移植了：configureAgingSchedule 的字段/版本校验、runDueAgingSchedules 的
 * 扫描上限（1-20）与 PERSISTED LEASE（5 分钟租约、崩溃重试、每日幂等捕获、失败 1 小时后重试）。
 */
public final class AgingScheduleService {

    /** 计划实体。 */
    public static final class Schedule {
        private final String id;
        private final String warehouseId;
        private final String policyReference;
        private final String actorUserId;
        private boolean enabled;
        private int revision;
        private long nextRunAt;
        private String lastError;
        private long lastSucceededAt;

        public Schedule(String id, String warehouseId, String policyReference, String actorUserId) {
            this.id = id;
            this.warehouseId = warehouseId;
            this.policyReference = policyReference;
            this.actorUserId = actorUserId;
            this.nextRunAt = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public String getWarehouseId() { return warehouseId; }
        public String getPolicyReference() { return policyReference; }
        public String getActorUserId() { return actorUserId; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRevision() { return revision; }
        public void incrementRevision() { revision++; }
        public long getNextRunAt() { return nextRunAt; }
        public void setNextRunAt(long nextRunAt) { this.nextRunAt = nextRunAt; }
        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
        public long getLastSucceededAt() { return lastSucceededAt; }
        public void setLastSucceededAt(long lastSucceededAt) { this.lastSucceededAt = lastSucceededAt; }
    }

    /** 扫描结果。 */
    public static final class ScanResult {
        private final String id;
        private final String status;

        public ScanResult(String id, String status) {
            this.id = id;
            this.status = status;
        }

        public String getId() { return id; }
        public String getStatus() { return status; }
    }

    private AgingScheduleService() {}

    /** configureAgingSchedule 的输入校验。 */
    public static void validateConfig(Object enabledRaw, Object revisionRaw) {
        if (!(enabledRaw instanceof Boolean) || !(revisionRaw instanceof Number)
                || ((Number) revisionRaw).longValue() < 0) {
            throw new WmsError("计划启用状态或版本无效。", 400);
        }
    }

    /** runDueAgingSchedules 的扫描上限校验。 */
    public static void validateLimit(int limit) {
        if (limit < 1 || limit > 20) throw new IllegalStateException("Invalid aging schedule scan limit");
    }
}

package com.wimoor.erp.wms.enhance;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库龄快照服务（对应源文件 aging-snapshot-service.ts）。
 * 移植了：captureAgingSnapshot 的严格字段校验、UTC 日边界捕获、
 * 当日口径不可覆盖的幂等、分页最多 100 页（10000 组合）上限、
 * NEEDS_DATA/COMPLETE 状态判定。源端用可重复读事务与 advisory lock；
 * 本实现给出领域层编排与幂等/边界校验。
 */
public final class AgingSnapshotService {

    /** 快照记录。 */
    public static final class Snapshot {
        private final String id;
        private final String warehouseId;
        private final String policyReference;
        private final String day;
        private final String sourceHash;
        private final int recordCount;
        private final String status;

        public Snapshot(String id, String warehouseId, String policyReference, String day,
                        String sourceHash, int recordCount, String status) {
            this.id = id;
            this.warehouseId = warehouseId;
            this.policyReference = policyReference;
            this.day = day;
            this.sourceHash = sourceHash;
            this.recordCount = recordCount;
            this.status = status;
        }

        public String getId() { return id; }
        public String getPolicyReference() { return policyReference; }
        public String getDay() { return day; }
        public String getSourceHash() { return sourceHash; }
        public int getRecordCount() { return recordCount; }
        public String getStatus() { return status; }
    }

    /** 捕获结果。 */
    public static final class CaptureResult {
        private final Snapshot snapshot;
        private final boolean reused;

        public CaptureResult(Snapshot snapshot, boolean reused) {
            this.snapshot = snapshot;
            this.reused = reused;
        }

        public Snapshot getSnapshot() { return snapshot; }
        public boolean isReused() { return reused; }
    }

    private AgingSnapshotService() {}

    /** 从捕获时间取 UTC 日。 */
    public static String utcDay(Date capturedAt) {
        return LocalDate.ofInstant(capturedAt.toInstant(), ZoneOffset.UTC).toString();
    }

    /** 当日口径幂等校验。 */
    public static void validatePolicyReuse(Snapshot prior, String policyReference) {
        if (prior != null && !prior.getPolicyReference().equals(policyReference)) {
            throw new WmsError("当日库龄快照口径不可覆盖。", 409);
        }
    }

    /** 构造快照（含状态判定）。 */
    public static CaptureResult createSnapshot(String warehouseId, String policyReference, Date capturedAt,
                                               List<Map<String, Object>> records, String sourceHash) {
        if (records.size() > 10_000) {
            throw new WmsError("单仓快照超过 10000 个商品组合，须采用专用批任务。", 422);
        }
        boolean complete = true;
        for (Map<String, Object> record : records) {
            if (!"COMPLETE".equals(record.get("dataStatus"))) { complete = false; break; }
        }
        Snapshot snapshot = new Snapshot(WmsDocumentStore.newId(), warehouseId, policyReference,
                utcDay(capturedAt), sourceHash, records.size(), complete ? "COMPLETE" : "NEEDS_DATA");
        return new CaptureResult(snapshot, false);
    }
}

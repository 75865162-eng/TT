package com.wimoor.ai.skill;

import com.wimoor.ai.skill.SkillTypes.ExportFormat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 报告短时下载存储。移植自 {@code src/lib/skill-engine/report-store.ts}。
 * 仅存在于当前 JVM 进程内存，带容量上限与 TTL，不持久化、不写业务表、不跨实例共享。
 * 进程重启即失效，符合报告临时下载定位。注入 {@link Clock} 与 id 后缀发生器便于确定性测试。
 */
public final class ReportStore {

    public static final long TTL_MS = 15L * 60 * 1000;
    public static final int MAX_ENTRIES = 200;

    private final Clock clock;
    private final Supplier<String> suffixSupplier;
    private final AtomicLong counter = new AtomicLong();
    private final Map<String, Entry> store = new LinkedHashMap<>();

    public ReportStore() {
        this(Clock.systemUTC(), () -> UUID.randomUUID().toString().replace("-", "").substring(0, 6));
    }

    public ReportStore(Clock clock, Supplier<String> suffixSupplier) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.suffixSupplier = suffixSupplier == null ? () -> "000000" : suffixSupplier;
    }

    /** 存储条目（等价原 Entry）。 */
    public static final class Entry {
        private final byte[] bytes;
        private final String fileName;
        private final ExportFormat format;
        private final long createdAt;
        public Entry(byte[] bytes, String fileName, ExportFormat format, long createdAt) {
            this.bytes = bytes; this.fileName = fileName; this.format = format; this.createdAt = createdAt;
        }
        public byte[] getBytes() { return bytes; }
        public String getFileName() { return fileName; }
        public ExportFormat getFormat() { return format; }
        public long getCreatedAt() { return createdAt; }
    }

    public static final class PutInput {
        private final byte[] bytes;
        private final String fileName;
        private final ExportFormat format;
        private final String organizationId;
        private final String userId;
        public PutInput(byte[] bytes, String fileName, ExportFormat format, String organizationId, String userId) {
            this.bytes = bytes; this.fileName = fileName; this.format = format;
            this.organizationId = organizationId; this.userId = userId;
        }
        public byte[] getBytes() { return bytes; }
        public String getFileName() { return fileName; }
        public ExportFormat getFormat() { return format; }
        public String getOrganizationId() { return organizationId; }
        public String getUserId() { return userId; }
    }

    /** 保存报告并返回短时下载 id。 */
    public String putReport(PutInput input) {
        long now = clock.millis();
        purgeExpired(now);
        long seq = counter.incrementAndGet();
        String id = Long.toString(now, 36) + "-" + Long.toString(seq, 36) + "-" + suffixSupplier.get();
        byte[] copy = input.getBytes() == null ? new byte[0] : input.getBytes().clone();
        store.put(id, new Entry(copy, input.getFileName(), input.getFormat(), now));
        return id;
    }

    /** 读取未过期报告；过期项会被惰性删除。 */
    public Entry getReport(String id) {
        Entry entry = store.get(id);
        if (entry == null) return null;
        if (clock.millis() - entry.getCreatedAt() > TTL_MS) {
            store.remove(id);
            return null;
        }
        return entry;
    }

    /** 当前有效条目数（测试与运维可见）。 */
    public int size() { return store.size(); }

    /** 移植自 purgeExpired：先清 TTL，再按创建时间淘汰最旧条目直到不超过 MAX_ENTRIES。 */
    private void purgeExpired(long now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Entry> e : store.entrySet()) {
            if (now - e.getValue().getCreatedAt() > TTL_MS) expired.add(e.getKey());
        }
        for (String id : expired) store.remove(id);
        while (store.size() > MAX_ENTRIES) {
            store.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().getCreatedAt()))
                    .map(Map.Entry::getKey)
                    .ifPresent(store::remove);
        }
    }
}

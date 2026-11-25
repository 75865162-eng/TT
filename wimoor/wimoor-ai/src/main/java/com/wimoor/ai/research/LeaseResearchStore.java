package com.wimoor.ai.research;

import java.time.Instant;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 带租约与 CAS 的事务存储（移植自 src/lib/selection-research/lease-store.ts）。
 * 租约在 120 秒内有效；只在最后一次成功保存的状态上释放，失败的决定不会泄漏部分变更。
 */
public final class LeaseResearchStore implements ResearchStore {

    private static final long LEASE_MS = 120_000L;
    private final ResearchRepository repository;
    private final ResearchTypes.Scope scope;
    private final LongSupplier now;

    public LeaseResearchStore(ResearchRepository repository, ResearchTypes.Scope scope) {
        this(repository, scope, System::currentTimeMillis);
    }

    public LeaseResearchStore(ResearchRepository repository, ResearchTypes.Scope scope, LongSupplier now) {
        this.repository = repository;
        this.scope = scope;
        this.now = now;
    }

    @Override
    public void create(ResearchTypes.ResearchRun run) {
        repository.create(run);
    }

    @Override
    public ResearchTypes.ResearchRun read(String id) {
        return repository.read(id, scope).run;
    }

    @Override
    public <T> T transaction(String id, Work<T> work) {
        ResearchRepository.ResearchRow row = repository.read(id, scope);
        if (row.leaseId != null && row.leaseUntil > now.getAsLong()) throw new ResearchStoreException("RESEARCH_BUSY");
        String leaseId = UUID.randomUUID().toString();
        String[] version = new String[]{repository.compareAndSet(id, scope, row.version, row.run,
                leaseId, now.getAsLong() + LEASE_MS)};
        if (version[0] == null) throw new ResearchStoreException("RESEARCH_BUSY");
        long[] until = new long[]{now.getAsLong() + LEASE_MS};
        ResearchTypes.ResearchRun run = ResearchJson.copy(row.run);
        try {
            return work.apply(run, () -> {
                if (now.getAsLong() >= until[0]) throw new ResearchStoreException("RESEARCH_LEASE_EXPIRED");
                run.revision++;
                run.updatedAt = Instant.ofEpochMilli(now.getAsLong()).toString();
                String next = repository.compareAndSet(id, scope, version[0], run,
                        leaseId, now.getAsLong() + LEASE_MS);
                if (next == null) throw new ResearchStoreException("RESEARCH_LEASE_LOST");
                version[0] = next;
                until[0] = now.getAsLong() + LEASE_MS;
            });
        } finally {
            ResearchRepository.ResearchRow latest = repository.read(id, scope);
            if (leaseId.equals(latest.leaseId) && version[0].equals(latest.version))
                repository.compareAndSet(id, scope, version[0], latest.run, null, 0L);
        }
    }
}

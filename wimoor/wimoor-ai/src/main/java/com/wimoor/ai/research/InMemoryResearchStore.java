package com.wimoor.ai.research;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内独占锁存储（对应 file-store.ts 的 .lock 目录语义，但无需设备本地文件系统）。
 * 事务期间对同一 id 串行化；save() 原子递增 revision 并写入深拷贝。
 */
public final class InMemoryResearchStore implements ResearchStore {

    private static final java.util.regex.Pattern RUN_ID = java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,80}$");
    private final Map<String, ResearchTypes.ResearchRun> rows = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private static String folder(String id) {
        if (!RUN_ID.matcher(id).matches()) throw new ResearchError("INVALID_RUN_ID");
        return id;
    }

    @Override
    public void create(ResearchTypes.ResearchRun run) {
        String id = folder(run.id);
        rows.put(id, ResearchJson.copy(run));
    }

    @Override
    public ResearchTypes.ResearchRun read(String id) {
        ResearchTypes.ResearchRun run = rows.get(folder(id));
        if (run == null) throw new ResearchError("RESEARCH_NOT_FOUND");
        return ResearchJson.copy(run);
    }

    public boolean locked(String id) {
        ReentrantLock lock = locks.get(folder(id));
        return lock != null && lock.isLocked();
    }

    @Override
    public <T> T transaction(String id, Work<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(folder(id), k -> new ReentrantLock());
        if (!lock.tryLock()) throw new ResearchStoreException("RUN_LOCKED_USE_EXPLICIT_RECOVERY");
        try {
            ResearchTypes.ResearchRun run = read(id);
            return work.apply(run, () -> {
                run.revision++;
                run.updatedAt = Instant.now().toString();
                rows.put(id, ResearchJson.copy(run));
            });
        } finally {
            lock.unlock();
        }
    }
}

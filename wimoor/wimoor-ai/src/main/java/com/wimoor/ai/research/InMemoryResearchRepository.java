package com.wimoor.ai.research;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 进程内 CAS 仓储（对应 prisma-repository.ts 的持久化边界）。
 * 以 UUID 版本实现 compareAndSet；超过 16MB 的快照直接拒绝，避免无界状态写入。
 */
public final class InMemoryResearchRepository implements ResearchRepository {

    private static final int SNAPSHOT_LIMIT = 16_000_000;
    private static final String NAMESPACE = "selection-research-v1";
    private final Map<String, Stored> rows = new LinkedHashMap<>();

    private static final class Stored {
        ResearchTypes.ResearchRun run;
        String leaseId;
        long leaseUntil;
        String version;
    }

    private static String key(String id, ResearchTypes.Scope scope) {
        return NAMESPACE + "|" + id + "|" + scope.organizationId + "|" + scope.workspaceId + "|" + scope.ownerId;
    }

    @Override
    public synchronized void create(ResearchTypes.ResearchRun run) {
        Stored row = new Stored();
        row.run = ResearchJson.copy(run);
        row.leaseId = null;
        row.leaseUntil = 0;
        row.version = UUID.randomUUID().toString();
        rows.put(key(run.id, run.scope), row);
    }

    @Override
    public synchronized ResearchRow read(String id, ResearchTypes.Scope scope) {
        Stored row = rows.get(key(id, scope));
        if (row == null) throw new ResearchError("RESEARCH_NOT_FOUND");
        ResearchTypes.ResearchRun run = ResearchJson.copy(row.run);
        if (run.version != 1 || !id.equals(run.id)
                || !scope.organizationId.equals(run.scope.organizationId)
                || !scope.workspaceId.equals(run.scope.workspaceId)
                || !scope.ownerId.equals(run.scope.ownerId))
            throw new ResearchError("RESEARCH_SCOPE_DENIED");
        ResearchRow out = new ResearchRow();
        out.run = run;
        out.leaseId = row.leaseId;
        out.leaseUntil = row.leaseUntil;
        out.version = row.version;
        return out;
    }

    @Override
    public synchronized String compareAndSet(String id, ResearchTypes.Scope scope, String version,
                                             ResearchTypes.ResearchRun run, String leaseId, long leaseUntil) {
        try {
            if (ResearchJson.MAPPER.writeValueAsBytes(run).length > SNAPSHOT_LIMIT)
                throw new ResearchError("RESEARCH_SNAPSHOT_LIMIT");
        } catch (ResearchError e) {
            throw e;
        } catch (Exception e) {
            throw new ResearchStoreException("RESEARCH_SNAPSHOT_LIMIT", e);
        }
        String k = key(id, scope);
        Stored current = rows.get(k);
        if (current == null || !current.version.equals(version)) return null;
        Stored next = new Stored();
        next.run = ResearchJson.copy(run);
        next.leaseId = leaseId;
        next.leaseUntil = leaseUntil;
        next.version = UUID.randomUUID().toString();
        rows.put(k, next);
        return next.version;
    }
}

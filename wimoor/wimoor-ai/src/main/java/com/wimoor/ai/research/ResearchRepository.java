package com.wimoor.ai.research;

import java.util.Map;

/**
 * 持久化仓储抽象（对应 lease-store.ts 的 ResearchRepository）。
 * 使用带版本的 CAS 更新租约与状态，不在数据库事务内持有 AI/供应商请求。
 */
public interface ResearchRepository {

    void create(ResearchTypes.ResearchRun run);

    ResearchRow read(String id, ResearchTypes.Scope scope);

    /** 成功返回新版本；版本冲突返回 null。 */
    String compareAndSet(String id, ResearchTypes.Scope scope, String version,
                         ResearchTypes.ResearchRun run, String leaseId, long leaseUntil);

    final class ResearchRow {
        public ResearchTypes.ResearchRun run;
        public String leaseId;
        public long leaseUntil;
        public String version;
    }
}

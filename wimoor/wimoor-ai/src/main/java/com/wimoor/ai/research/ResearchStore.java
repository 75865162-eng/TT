package com.wimoor.ai.research;

/**
 * 事务存储抽象（对应 types.ts 的 Store）。
 * save() 由引擎在"发出任何可能计费的请求之前"显式调用，保证崩溃后不会重放不确定扣费。
 */
public interface ResearchStore {

    void create(ResearchTypes.ResearchRun run);

    ResearchTypes.ResearchRun read(String id);

    <T> T transaction(String id, Work<T> work);

    interface Work<T> {
        T apply(ResearchTypes.ResearchRun run, Save save);
    }

    interface Save {
        void save();
    }
}

package com.wimoor.ai.research;

/**
 * 研究阶段决策代理抽象（对应 types.ts 的 Agent）。
 * only next(context) -> call | complete；不暴露任何审批、预算或工具注册修改能力。
 */
public interface AiAgent {

    ResearchTypes.Action next(ResearchTypes.ResearchContext context);

    final class Unavailable implements AiAgent {
        @Override
        public ResearchTypes.Action next(ResearchTypes.ResearchContext context) {
            throw new ResearchError("EXTERNAL_RESEARCH_ACTION_REQUIRED");
        }
    }
}

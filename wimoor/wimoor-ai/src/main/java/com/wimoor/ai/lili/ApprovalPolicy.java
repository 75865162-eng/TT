package com.wimoor.ai.lili;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 移植自 src/lib/lili/approval-policy.ts。
 * 宿主策略与原始 SOP 一起提供，绝不改写供应商指令或虚构执行器。
 * 用枚举 + 不可变常量表达 as const 的策略对象。
 */
public final class ApprovalPolicy {

    private ApprovalPolicy() {}

    public enum Channel { CODEX_CHAT("codex_chat");
        private final String wire; Channel(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }
    public enum ApprovalUnit { SPECIFIC_TASK_PLAN("specific_task_plan");
        private final String wire; ApprovalUnit(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }
    public enum UnsupportedExecution { REPORT_IN_CHAT_WITHOUT_DISPATCH("report_in_chat_without_dispatch");
        private final String wire; UnsupportedExecution(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public static final int VERSION = 1;
    public static final Channel CHANNEL = Channel.CODEX_CHAT;
    public static final ApprovalUnit APPROVAL_UNIT = ApprovalUnit.SPECIFIC_TASK_PLAN;
    public static final boolean REPEAT_APPROVAL_WITHIN_SCOPE = false;
    public static final boolean BROWSER_REQUIRED = false;
    public static final boolean SCOPE_CHANGES_REQUIRE_CHAT_CONFIRMATION = true;
    public static final boolean BUSINESS_CHOICES_REQUIRE_HUMAN_REPLY = true;
    public static final UnsupportedExecution UNSUPPORTED_EXECUTION = UnsupportedExecution.REPORT_IN_CHAT_WITHOUT_DISPATCH;

    public static final String INSTRUCTIONS_ZH =
        "审批统一在 Codex 对话中完成，无需返回浏览器。仅在用户或当前 Skill 明确要求时发起调用前费用授权；否则直接执行已请求的研究；任何情况下都不因费用未知或未确认暂停，不重复索要批准。已保存的付费研究授权直接沿用。选方向、最终结论或超出原授权范围的新增事项在对话里确认。只使用实际接入的授权执行器；未接入步骤明确说明并保留分析或草稿，不跳转网页，不虚报执行。";

    public static final String INSTRUCTIONS_EN =
        "All Lili approvals happen in Codex chat; no browser is required. Request pre-call fee approval only when explicitly required by the user or active Skill. Otherwise continue requested research without fee approval. Unknown or unconfirmed charges never block tasks, regardless of fee approval policy. Reuse saved paid-research consent. Ask in chat for business choices, final conclusions or scope changes. Use only registered authorized executors. Report unsupported steps in chat and retain analysis or drafts; never claim execution or send the user to a browser.";

    /** 与 TS 中 liliApprovalPolicy 对象的字段一一对应。 */
    public static Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("channel", CHANNEL.wire());
        m.put("approvalUnit", APPROVAL_UNIT.wire());
        m.put("repeatApprovalWithinScope", REPEAT_APPROVAL_WITHIN_SCOPE);
        m.put("browserRequired", BROWSER_REQUIRED);
        m.put("scopeChangesRequireChatConfirmation", SCOPE_CHANGES_REQUIRE_CHAT_CONFIRMATION);
        m.put("businessChoicesRequireHumanReply", BUSINESS_CHOICES_REQUIRE_HUMAN_REPLY);
        m.put("unsupportedExecution", UNSUPPORTED_EXECUTION.wire());
        return m;
    }
}

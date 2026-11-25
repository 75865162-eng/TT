package com.wimoor.ai.research;

import java.util.regex.Pattern;

/** 只有任务说明与当前 Skill 正文可以请求额外费用审批（移植自 fee-policy.ts）。 */
public final class FeePolicy {

    // (付费|费用|扣费|收费)…(先|需|必须)…(审批|批准|确认) 及其反向，或英文 paid/chargeable…require/must/before…approval/confirmation
    private static final Pattern REQUIRED = Pattern.compile(
            "(?:付费|费用|扣费|收费)[^。\\n]{0,45}(?:先|需|必须)[^。\\n]{0,25}(?:审批|批准|确认)" +
            "|(?:先|需|必须)[^。\\n]{0,25}(?:审批|批准|确认)[^。\\n]{0,30}(?:付费|费用|扣费)" +
            "|(?:paid|chargeable)[^.!\\n]{0,70}(?:require|must|before)[^.!\\n]{0,50}(?:approval|confirmation)",
            Pattern.CASE_INSENSITIVE);

    // 否定语境：无需/不需/不得…费用/审批，或 do not/no need/without…fee/paid/approval
    private static final Pattern NEGATED = Pattern.compile(
            "(?:无需|不需|不要|取消|删除|不得).{0,35}(?:费用|付费|审批|批准|确认)" +
            "|(?:do not|no need|without).{0,40}(?:fee|paid|approval|confirmation)",
            Pattern.CASE_INSENSITIVE);

    private FeePolicy() {}

    /** 参数：任务需求、当前 Skill 正文。二者任一子句显式要求费用审批且未被否定时返回 true。 */
    public static boolean feeReviewRequired(String requirement, String activeSkillMarkdown) {
        return requires(requirement) || requires(activeSkillMarkdown);
    }

    private static boolean requires(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String clause : text.split("[。\\n]")) {
            if (NEGATED.matcher(clause).find()) continue;
            if (REQUIRED.matcher(clause).find()) return true;
        }
        return false;
    }
}

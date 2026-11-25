package com.wimoor.ai.research;

import java.util.Map;

/**
 * 主机自有的结构校验提示文案（移植自 src/lib/selection-research/validation-feedback.ts）。
 * 只暴露固定错误码对应的中文提示，绝不回传 provider 或模型的异常正文。
 */
public final class ValidationFeedback {

    public static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("INVALID_EXTERNAL_RESEARCH_ACTION", "研究动作格式无效；仅接受 call（tool、arguments）或 complete（result）。"),
            Map.entry("RESEARCH_ACTION_JSON_INVALID", "研究参数或结果不是合法 JSON；请修正后重新提交，未发送市场请求。"),
            Map.entry("RESEARCH_ACTION_JSON_TOO_LARGE", "JSON 字符串超过输入大小限制；请缩小本次提交范围。"),
            Map.entry("RESEARCH_ACTION_OBJECT_REQUIRED", "研究参数或结果必须为 JSON 对象，不能是数组、空值或其他基本类型。"),
            Map.entry("RESEARCH_TOOL_ARGUMENTS_INVALID", "工具参数不符合当前注册定义；请核对字段类型、必填项和允许值后恢复任务。"),
            Map.entry("INVALID_LANDED_COST_SCENARIO", "到岸成本情景结构无效。请检查币种、国家、税目、日期、数量及唯一基础税项。"),
            Map.entry("INVALID_LANDED_COST_INPUT", "到岸成本必须是非负的同币种单位金额；未知项填写 null。"),
            Map.entry("INVALID_TARIFF_SOURCE", "税目或税率来源不完整；请保留合法来源日期与链接，未知项填写 null。"),
            Map.entry("VERIFIED_TARIFF_SOURCE_REQUIRED", "已核实税率必须有成功证据、来源链接和适用日期。"),
            Map.entry("INVALID_TARIFF_APPLICABILITY", "税率或税目适用日期无效，或晚于情景日期。"),
            Map.entry("INVALID_TARIFF_RATE", "税率结构无效；税率用小数，未知值填写 null。"),
            Map.entry("CURRENT_TARIFF_VERIFICATION_REQUIRED", "主机尚未核实税目或税率的当前适用性，请保留 pending/candidate 或明确压力测试。"),
            Map.entry("LANDED_COST_OVERFLOW", "到岸成本计算超出可表示范围，请核对金额和数量。"),
            Map.entry("COST_BASIS_REQUIRED", "每个已填成本都需要来源性质和说明；实际观察或供应商报价还必须引用成功证据。"),
            Map.entry("INVALID_DECISION_ANALYSIS", "利润与评分结构不完整，请提供 scenarios 和 scorecards。"),
            Map.entry("INVALID_ECONOMICS_SCENARIO", "利润情景结构或币种无效。"),
            Map.entry("INVALID_BATCH_COSTS", "批量必须为正整数；固定成本必须非负；未知项填写 null。"),
            Map.entry("INVALID_SCENARIO_COST", "利润成本必须是非负单位金额，售价须大于零；未知项填写 null。"),
            Map.entry("ECONOMICS_OVERFLOW", "利润计算超出可表示范围，请核对金额和数量。"),
            Map.entry("INVALID_SCORECARD", "评分表需要候选方向及不重复的评分维度。"),
            Map.entry("INVALID_SCORE_DIMENSION", "评分维度需要依据；分数范围为 0–100，无证据时分数为 null。"),
            Map.entry("SCORE_WEIGHTS_MUST_SUM_TO_ONE", "评分权重必须合计为 1，不能自动补分或重分配缺失维度。"),
            Map.entry("INVALID_SCORE_CANDIDATE", "评分方向不在第一阶段的已保存候选清单中。"),
            Map.entry("MISSING_COST_EVIDENCE", "成本或税率引用了不存在或未成功的工具证据。"),
            Map.entry("MISSING_SCORE_EVIDENCE", "评分引用了不存在或未成功的工具证据。")
    );

    private ValidationFeedback() {}

    public static boolean contains(String code) {
        return code != null && MESSAGES.containsKey(code);
    }

    public static String message(String code) {
        return MESSAGES.get(code);
    }
}

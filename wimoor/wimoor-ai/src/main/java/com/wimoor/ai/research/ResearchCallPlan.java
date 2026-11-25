package com.wimoor.ai.research;

import java.util.List;

/** 单站点、选定一个细分市场、研究 3–5 个竞品的规划调用估算（非供应商报价、非硬上限）。 */
public final class ResearchCallPlan {

    public static final class Plan {
        private final String skill;
        private final int min;
        private final int max;
        private final String basis;

        public Plan(String skill, int min, int max, String basis) {
            this.skill = skill;
            this.min = min;
            this.max = max;
            this.basis = basis;
        }
        public String getSkill() { return skill; }
        public int getMin() { return min; }
        public int getMax() { return max; }
        public String getBasis() { return basis; }
    }

    public static final List<Plan> PLANS = List.of(
            new Plan("amazon-niche-finder", 8, 10, "挖词、验证需求、筛选3–5个细分市场"),
            new Plan("amazon-product-research", 6, 10, "研究一个选定市场及头部竞品"),
            new Plan("amazon-review-analyzer", 4, 8, "获取3–5个竞品差评，必要时补页"),
            new Plan("tariff-calculator-amazon", 0, 0, "通常复用已有市场数据；税率证据和报价另行核实"),
            new Plan("amazon-product-selection", 0, 2, "评分、利润及压力测试优先复用已有数据")
    );

    /** 五阶段规划调用合计区间：min=18, max=30（与源端预算文案一致）。 */
    public static int totalMin() { return PLANS.stream().mapToInt(Plan::getMin).sum(); }
    public static int totalMax() { return PLANS.stream().mapToInt(Plan::getMax).sum(); }

    public static Plan forSkill(String skill) {
        return PLANS.stream().filter(p -> p.getSkill().equals(skill)).findFirst().orElse(null);
    }
}

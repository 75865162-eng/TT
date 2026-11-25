package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预算预览与实际用量汇总（移植自 src/lib/selection-research/budget.ts）。
 * 只模拟支出上限；请求次数绝不推断供应商积分，未知价格保持 null。
 */
public final class ResearchBudget {

    public static final class StageBudget {
        public int stage;
        public String skill;
        public String skillSha256;
        public Map<String, Integer> estimatedCalls;
        public Object credits;
        public Object money;
        public List<Map<String, Object>> tools = new ArrayList<>();
    }

    public static final class Preview {
        public String mode;
        public boolean simulated;
        public String message;
        public Map<String, Object> creditRule;
        public Map<String, Object> planning;
        public List<StageBudget> stages = new ArrayList<>();
        public Map<String, Object> pendingCall;
        public Map<String, Object> providerLimits = new LinkedHashMap<>();
        public Map<String, Object> paidCallAuthorization;
        public List<String> unknownCosts;
    }

    public static final class ProviderUsage {
        public int calls;
        public int unknownBillingCalls;
        public int confirmedCredits;
        public int unknownCreditCalls;
    }

    public static final class CreditUsage {
        public String unit = "research_credits";
        public Map<String, Object> rule;
        public BigDecimal confirmed;
        public BigDecimal estimated;
        public int pendingCalls;
        public BigDecimal total;
    }

    private ResearchBudget() {}

    public static Preview preview(ResearchTypes.ResearchRun run) {
        boolean simulated = "mock_unlimited".equals(run.budgetMode);
        boolean unlimited = simulated || "uncapped".equals(run.budgetMode);
        Preview out = new Preview();
        out.mode = run.budgetMode == null ? "reviewed" : run.budgetMode;
        out.simulated = simulated;
        out.message = simulated
                ? "预算估算暂用无限额度 mock；真实市场数据仍需授权。积分、金额未知，不代表免费或供应商额度无限。"
                : "规划调用约18–30次，仅为单站点、一个细分市场的估算；研究服务费用统一折算研究积分（1积分=¥0.01），未知价格待核实。任务默认不限次数，账户余额和权限仍独立校验。";
        out.creditRule = Map.of("version", 1, "cnyPerCredit", 0.01, "precision", 3);
        out.planning = Map.of("version", 1, "source", "host_workflow_estimate",
                "assumptions", "一个站点、最终研究一个细分市场、3–5个竞品；不是供应商报价或硬上限",
                "calls", Map.of("min", ResearchCallPlan.totalMin(), "max", ResearchCallPlan.totalMax()));
        List<ResearchTypes.SkillSnapshot> skills = run.skills == null ? List.of() : run.skills;
        for (int i = 0; i < skills.size(); i++) {
            ResearchTypes.SkillSnapshot skill = skills.get(i);
            int stage = i + 1;
            StageBudget s = new StageBudget();
            s.stage = stage;
            s.skill = skill.name;
            s.skillSha256 = skill.sha256;
            ResearchCallPlan.Plan plan = simulated ? null : ResearchCallPlan.forSkill(skill.name);
            if (plan != null) s.estimatedCalls = Map.of("min", plan.getMin(), "max", plan.getMax());
            s.credits = null;
            s.money = null;
            if (run.registry != null && run.policies != null) {
                for (ResearchTypes.ToolDefinition tool : run.registry) {
                    ResearchTypes.ToolPolicy policy = run.policies.get(tool.name);
                    if (policy == null || !policy.stages.contains(stage)) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", tool.name);
                    entry.put("provider", policy.provider);
                    entry.put("paid", policy.paid);
                    entry.put("callLimit", unlimited ? null : policy.maxCalls);
                    entry.put("unit", policy.unit);
                    entry.put("pricingSource", policy.pricingSource);
                    s.tools.add(entry);
                }
            }
            out.stages.add(s);
        }
        if (run.pendingAction != null) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("tool", run.pendingAction.tool);
            pending.put("arguments", run.pendingAction.arguments);
            pending.put("estimatedCalls", 1);
            pending.put("estimatedCredits", null);
            pending.put("estimatedCost", null);
            pending.put("costStatus", "pending_verification");
            pending.put("provider", run.policies == null || run.policies.get(run.pendingAction.tool) == null
                    ? null : run.policies.get(run.pendingAction.tool).provider);
            pending.put("approved", Boolean.TRUE.equals(run.pendingActionApproved));
            pending.put("message", "本次拟执行 1 次工具调用；研究积分待核实，不表示免费。");
            out.pendingCall = pending;
        }
        if (run.limits != null) {
            for (Map.Entry<String, Integer> e : run.limits.entrySet())
                out.providerLimits.put(e.getKey(), unlimited ? null : e.getValue());
        }
        if (run.paidCallAuthorization != null) {
            ResearchTypes.PaidCallAuthorization a = run.paidCallAuthorization;
            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("budgetMode", a.budgetMode == null ? "reviewed" : a.budgetMode);
            auth.put("id", a.id);
            auth.put("approvedAt", a.approvedAt);
            auth.put("approvedByUserId", a.approvedByUserId);
            auth.put("approvedActionDigest", a.approvedActionDigest);
            auth.put("providerLimits", a.providerLimits);
            auth.put("toolLimits", a.toolLimits);
            out.paidCallAuthorization = auth;
        }
        out.unknownCosts = List.of("实际市场请求次数由研究过程确定", "供应商实际积分与金额", "AI 模型费用");
        return out;
    }

    /** 按 provider 汇总真实付费调用；NOT_SENT 的调用不计费。 */
    public static Map<String, ProviderUsage> researchUsage(ResearchTypes.ResearchRun run) {
        Map<String, ProviderUsage> providers = new LinkedHashMap<>();
        for (ResearchTypes.CallRecord call : run.calls) {
            if (!call.paid || "NOT_SENT".equals(call.dispatch)) continue;
            ProviderUsage entry = providers.computeIfAbsent(call.provider, k -> new ProviderUsage());
            entry.calls++;
            if ("UNKNOWN".equals(call.billing)) entry.unknownBillingCalls++;
            if ("CONFIRMED".equals(call.billing) && "credits".equals(call.billingUnit))
                entry.confirmedCredits += call.knownUnits == null ? 0 : call.knownUnits;
            else entry.unknownCreditCalls++;
        }
        return providers;
    }

    /** 积分汇总：只有 confirmed 且无待核实项时 total 才成立；否则 total 为 null。 */
    public static CreditUsage researchCreditUsage(ResearchTypes.ResearchRun run) {
        long confirmed = 0, estimated = 0;
        int pendingCalls = 0;
        for (ResearchTypes.CallRecord call : run.calls) {
            if (!call.paid || "NOT_SENT".equals(call.dispatch)) continue;
            if (call.researchCharge == null) { pendingCalls++; continue; }
            double credits = ResearchCredits.chargeCredits(call.researchCharge);
            long milli = Math.round(credits * 1000d);
            if ("confirmed".equals(ChargeAccess.status(call.researchCharge))) confirmed += milli;
            else { estimated += milli; pendingCalls++; }
            if (Math.abs(confirmed) > 9007199254740991L || Math.abs(estimated) > 9007199254740991L)
                throw new ResearchError("INVALID_RESEARCH_CHARGE");
        }
        CreditUsage out = new CreditUsage();
        out.rule = Map.of("version", 1, "cnyPerCredit", 0.01, "precision", 3);
        out.confirmed = BigDecimal.valueOf(confirmed, 3);
        out.estimated = BigDecimal.valueOf(estimated, 3);
        out.pendingCalls = pendingCalls;
        out.total = pendingCalls == 0 ? out.confirmed : null;
        return out;
    }
}

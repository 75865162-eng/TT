package com.wimoor.ai.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 请求输入校验与就绪检查（移植自 src/lib/selection-research/preflight.ts）。
 * 只做元数据/配置检查，不证明业务或模型调用成功，也不授予执行权限。
 */
public final class Preflight {

    private static final Set<String> BUDGET_MODES = Set.of("reviewed", "mock_unlimited", "uncapped");

    public static final class ResearchInput {
        public String budgetMode;
        public String mode;
        public String requirement;
        public String marketplace;
        public String asin;
        public String aiConfigSource;
        public String erpCredentialId;
        public Integer maxMarketCalls;
    }

    public static final class ProviderStatus {
        public String provider;
        public String status;
        public String reason;

        public ProviderStatus() {}
        public ProviderStatus(String provider, String status, String reason) {
            this.provider = provider;
            this.status = status;
            this.reason = reason;
        }
    }

    public static final class RuntimeTool {
        public String provider;
        public String availability;

        public RuntimeTool() {}
        public RuntimeTool(String provider, String availability) {
            this.provider = provider;
            this.availability = availability;
        }
    }

    public static final class Check {
        public String id;
        public String status; // ready | blocked
        public String message;

        public Check() {}
        public Check(String id, String status, String message) {
            this.id = id;
            this.status = status;
            this.message = message;
        }
    }

    public static final class Readiness {
        public boolean ready;
        public List<Check> checks = new ArrayList<>();

        public Readiness(boolean ready, List<Check> checks) {
            this.ready = ready;
            this.checks = checks;
        }
    }

    public static final class ReadinessInput {
        public List<ProviderStatus> providers = new ArrayList<>();
        public List<RuntimeTool> tools = new ArrayList<>();
        public String ai;
        public List<ResearchTypes.SkillSnapshot> skills;
        public Map<String, Object> request;
    }

    private Preflight() {}

    private static Object get(Map<String, Object> body, String key) {
        return body == null ? null : body.get(key);
    }

    /** 缺失或拼错的 live 模式绝不静默创建样例任务。 */
    public static ResearchInput parseResearchInput(Map<String, Object> body) {
        Object modeObj = get(body, "mode");
        if (!"live".equals(modeObj) && !"fixture".equals(modeObj)) throw new ResearchError("EXPLICIT_RESEARCH_MODE_REQUIRED");
        Object req = get(body, "requirement"), market = get(body, "marketplace");
        if (!(req instanceof String) || ((String) req).strip().isEmpty() || ((String) req).length() > 20_000
                || !(market instanceof String) || ((String) market).strip().isEmpty() || ((String) market).strip().length() > 16)
            throw new ResearchError("INVALID_RESEARCH_REQUEST");
        Object asinObj = get(body, "asin");
        if (asinObj != null && !(asinObj instanceof String)) throw new ResearchError("INVALID_ASIN");
        String asin = asinObj == null ? null : ((String) asinObj).strip().toUpperCase(Locale.ROOT);
        if (asin != null && !asin.matches("[A-Z0-9]{10}")) throw new ResearchError("INVALID_ASIN");
        Object credential = get(body, "erpCredentialId");
        if (credential != null && (!(credential instanceof String) || ((String) credential).length() > 100))
            throw new ResearchError("INVALID_ERP_CREDENTIAL_ID");
        Object budgetObj = get(body, "budgetMode");
        if (budgetObj != null && !BUDGET_MODES.contains(String.valueOf(budgetObj))) throw new ResearchError("INVALID_RESEARCH_REQUEST");
        String budgetMode = budgetObj == null ? null : String.valueOf(budgetObj);
        if ("live".equals(modeObj) && "mock_unlimited".equals(budgetMode)) throw new ResearchError("LIVE_BUDGET_REVIEW_REQUIRED");
        Object maxObj = get(body, "maxMarketCalls");
        boolean hasMax = maxObj != null;
        if ("reviewed".equals(budgetMode) && !hasMax || "uncapped".equals(budgetMode) && hasMax
                || hasMax && (!(maxObj instanceof Number) || !isSafeInteger((Number) maxObj)
                    || ((Number) maxObj).intValue() < 1))
            throw new ResearchError("MISSING_MARKET_CALL_BUDGET");
        ResearchInput out = new ResearchInput();
        out.budgetMode = budgetMode != null ? budgetMode
                : (maxObj == null ? "uncapped" : "reviewed");
        out.mode = (String) modeObj;
        out.requirement = ((String) req).strip();
        out.marketplace = ((String) market).strip().toUpperCase(Locale.ROOT);
        out.asin = asin == null || asin.isEmpty() ? null : asin;
        out.aiConfigSource = AiSettings.parseResearchAiSource(get(body, "aiConfigSource"));
        out.erpCredentialId = credential instanceof String && !((String) credential).strip().isEmpty()
                ? ((String) credential).strip() : null;
        out.maxMarketCalls = hasMax ? ((Number) maxObj).intValue() : null;
        ResearchSchema.rejectCredentials(out);
        return out;
    }

    private static boolean isSafeInteger(Number n) {
        if (n instanceof Byte || n instanceof Short || n instanceof Integer) return true;
        if (n instanceof Long) return Math.abs((Long) n) <= 9007199254740991L;
        if (n instanceof java.math.BigInteger)
            return ((java.math.BigInteger) n).abs().compareTo(java.math.BigInteger.valueOf(9007199254740991L)) <= 0;
        if (n instanceof Double) return n.doubleValue() == Math.rint(n.doubleValue())
                && Math.abs(n.doubleValue()) <= 9007199254740991L;
        return false;
    }

    public static Readiness researchReadiness(ReadinessInput input) {
        List<Check> checks = new ArrayList<>();
        for (String id : List.of("erp", "sellersprite", "xiyou")) {
            ProviderStatus source = null;
            if (input.providers != null)
                for (ProviderStatus p : input.providers) if (id.equals(p.provider)) { source = p; break; }
            int callable = 0;
            if (input.tools != null)
                for (RuntimeTool t : input.tools)
                    if (id.equals(t.provider) && "available".equals(t.availability)) callable++;
            boolean ready = source != null && "connected".equals(source.status) && callable > 0;
            String message = ready
                    ? "在线目录已读取，" + callable + " 个审核过的只读工具；业务数据尚未调用。"
                    : source != null && source.reason != null ? source.reason : "来源未连接或没有可用的只读工具。";
            checks.add(new Check(id, ready ? "ready" : "blocked", message));
        }
        String ai = input.ai;
        String aiStatus = "configured".equals(ai) ? "ready" : "blocked";
        String aiMessage = "configured".equals(ai) ? "已有模型配置；尚未发起模型请求验证。"
                : "runtime_model_permission_required".equals(ai)
                    ? "选用服务端模型需要当前账号已有的系统设置编辑权限。"
                    : "所选模型未配置或配置不可用；可在 ERP 设置中配置工作区模型，或显式选用已有服务端模型。";
        checks.add(new Check("ai", aiStatus, aiMessage));
        boolean skillsReady = input.skills != null && input.skills.size() == ResearchTypes.SKILLS.size();
        if (skillsReady) {
            for (int i = 0; i < input.skills.size(); i++) {
                ResearchTypes.SkillSnapshot s = input.skills.get(i);
                if (!ResearchTypes.SKILLS.get(i).equals(s.name) || s.markdown == null || s.markdown.isEmpty()
                        || s.sha256 == null || s.sha256.isEmpty()) { skillsReady = false; break; }
            }
        }
        checks.add(new Check("skills", skillsReady ? "ready" : "blocked",
                skillsReady ? "五个现有 Skill 原文及哈希已加载。" : "现有研究 Skill 加载失败或不完整；未替换为样本。"));
        try {
            Map<String, Object> copy = new LinkedHashMap<>(input.request == null ? Map.of() : input.request);
            copy.put("mode", "live");
            parseResearchInput(copy);
            checks.add(new Check("input", "ready", "研究输入与所选预算模式有效；费用审批仅在用户或当前 Skill 明确要求时触发；第1和第5阶段等待业务确认。"));
        } catch (ResearchError e) {
            checks.add(new Check("input", "blocked", "请填写研究要求及站点，调用上限可留空；填写时须为正整数，并检查 ASIN 和凭证 ID 格式。"));
        }
        return new Readiness(checks.stream().allMatch(c -> "ready".equals(c.status)), checks);
    }
}

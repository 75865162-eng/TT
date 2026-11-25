package com.wimoor.ai.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端装配与错误映射（移植自 src/lib/selection-research/server.ts）。
 * 权限、工作区、集成设置等由宿主适配器提供；本类实现模式选择、存储引擎回退、工具目录发现与错误码白名单。
 */
public final class ResearchServer {

    public static final String NAMESPACE = "selection-research-v1";

    public static final class Permission {
        public boolean ok;
        public String userId;
        public String organizationId;

        public Permission(boolean ok, String userId, String organizationId) {
            this.ok = ok;
            this.userId = userId;
            this.organizationId = organizationId;
        }
    }

    /** 宿主权限与工作区访问适配器。 */
    public interface AccessPolicy {
        Permission permission(String moduleId, String action);
        boolean workspaceAllowed(Permission p, String workspaceId);
    }

    public static final class ServerDeps {
        public ResearchRepository repository;
        public ResearchTransport liveTransport;
        public AiAgent liveAgent;
        public ResearchEngine.LiveAuthorizer liveAuthorizer;
        public PaidCallConsent.Service consents;
        public ResearchEngine.MarketQuota marketQuota;
    }

    public static final class AccessResult {
        public boolean ok;
        public ResearchTypes.Scope scope;
        public ResearchEngine engine;
        public PaidCallConsent.Consent paidCallConsent;
        public String mode;
        public String error;
    }

    private static final Set<String> KNOWN_ERRORS = Set.of(
            "RESEARCH_NOT_FOUND", "RESEARCH_SCOPE_DENIED", "RESEARCH_BUSY", "STALE_OR_MISSING_GATE",
            "STALE_RESEARCH_DECISION", "INVALID_CANDIDATE", "DECISION_DOES_NOT_MATCH_GATE",
            "INVALID_RESEARCH_DECISION", "INVALID_RESEARCH_AUTHORIZATION", "REPORT_REQUIRES_BOTH_HUMAN_GATES",
            "RESEARCH_MODE_MISMATCH", "BUDGET_MUST_INCREASE", "BUDGET_INCREASE_NOT_ALLOWED_AFTER_RESEARCH_AUTHORIZATION",
            "TERMINAL_RUN", "EXPLICIT_RECOVERY_REQUIRED", "RECOVERY_NOT_APPLICABLE", "SELLERSPRITE_MCP_NOT_CONFIGURED",
            "SELLERSPRITE_MCP_TOOL_NOT_FOUND", "SELLERSPRITE_MCP_REGISTRY_INVALID", "NO_SUPPORTED_LIVE_TOOLS",
            "AI_MODEL_NOT_CONFIGURED", "PROVIDER_NOT_CONFIGURED", "MISSING_MARKET_CALL_BUDGET",
            "APPROVED_SCHEMA_CHANGED", "PENDING_TOOL_CALL_MISMATCH", "INVALID_ASIN", "REQUIRED_PROVIDER_NOT_CONNECTED",
            "RESEARCH_REPORT_METADATA_FAILED", "RESEARCH_REPORT_UNAVAILABLE", "RESEARCH_REPORT_INTEGRITY_FAILED",
            "EXPLICIT_RESEARCH_MODE_REQUIRED", "INVALID_RESEARCH_REQUEST", "INVALID_ERP_CREDENTIAL_ID",
            "EXTERNAL_RESEARCH_ACTION_REQUIRED", "INVALID_EXTERNAL_RESEARCH_ACTION", "STALE_EXTERNAL_RESEARCH_ACTION",
            "EXTERNAL_ACTION_CANNOT_REPLACE_APPROVAL", "INVALID_RESEARCH_CHARGE", "LIVE_BUDGET_REVIEW_REQUIRED",
            "RESEARCH_STAGE_REPORT_UNAVAILABLE", "RESEARCH_EVIDENCE_NOT_FOUND", "RESEARCH_RUNTIME_AI_PERMISSION_REQUIRED",
            "INVALID_RESEARCH_AI_SOURCE", "RESEARCH_ACTION_JSON_INVALID", "RESEARCH_ACTION_JSON_TOO_LARGE",
            "RESEARCH_ACTION_OBJECT_REQUIRED");
    private static final Set<String> INVALID_INPUT = Set.of(
            "EXPLICIT_RESEARCH_MODE_REQUIRED", "INVALID_RESEARCH_REQUEST", "INVALID_ERP_CREDENTIAL_ID", "INVALID_ASIN",
            "MISSING_MARKET_CALL_BUDGET", "INVALID_RESEARCH_AI_SOURCE", "INVALID_EXTERNAL_RESEARCH_ACTION",
            "RESEARCH_ACTION_JSON_INVALID", "RESEARCH_ACTION_JSON_TOO_LARGE", "RESEARCH_ACTION_OBJECT_REQUIRED");

    private ResearchServer() {}

    /** 发现可用只读工具并生成审阅策略；缺失的必需工具单独列出。 */
    public static final class Discovery {
        public List<ResearchTypes.ToolDefinition> registry = new ArrayList<>();
        public Map<String, ResearchTypes.ToolPolicy> policies = new LinkedHashMap<>();
        public List<String> missing = new ArrayList<>();
    }

    public static Discovery discoverResearchTools(List<ResearchTypes.ToolDefinition> registered) {
        Discovery out = new Discovery();
        Set<String> supported = new java.util.HashSet<>();
        for (ResearchTypes.ToolDefinition tool : registered) {
            String provider = providerOf(tool);
            ResearchTypes.ToolPolicy policy;
            if (Set.of("erp", "skills", "xiyou").contains(provider)) {
                boolean paid = "xiyou".equals(provider);
                int units = paid ? 1 : 0;
                String source = paid ? "Provider charge unknown; review exact request before dispatch."
                        : "Existing ERP/Skill read adapter; no external market request.";
                policy = new ResearchTypes.ToolPolicy(provider, paid, units, "calls", source, 15,
                        List.of(1, 2, 3, 4, 5));
            } else {
                policy = SellerTransport.sellerSpritePolicy(tool);
            }
            String canonical = ResearchEngine.canonicalResearchTool(tool.name);
            if (policy == null || canonical == null || Boolean.FALSE.equals(tool.readOnlyHint)) continue;
            out.registry.add(tool);
            out.policies.put(tool.name, policy);
            supported.add(canonical);
        }
        for (String name : ResearchEngine.READ_TOOLS) if (!supported.contains(name)) out.missing.add(name);
        return out;
    }

    private static String providerOf(ResearchTypes.ToolDefinition tool) {
        return tool.provider;
    }

    /** 把内部错误码映射为稳定的主机响应（不暴露异常正文）。 */
    public static Map<String, Object> researchError(Throwable error) {
        String code = error instanceof ResearchError ? ((ResearchError) error).code() : "RESEARCH_UNAVAILABLE_OR_INVALID_REQUEST";
        String stable = KNOWN_ERRORS.contains(code) ? code : "RESEARCH_UNAVAILABLE_OR_INVALID_REQUEST";
        int status = INVALID_INPUT.contains(stable) ? 400
                : Set.of("RESEARCH_NOT_FOUND", "RESEARCH_EVIDENCE_NOT_FOUND").contains(stable) ? 404
                : Set.of("RESEARCH_SCOPE_DENIED", "RESEARCH_RUNTIME_AI_PERMISSION_REQUIRED").contains(stable) ? 403
                : KNOWN_ERRORS.contains(stable) ? 409 : 500;
        return Map.of("error", stable, "status", status);
    }

    /** 可见性检查：SellerSprite/AI 配置来源与模型权限，均不证明业务调用成功。 */
    public static final class Availability {
        public ResearchTypes.Scope scope;
        public String sellerSprite;
        public String ai;
        public Map<String, String> aiModel;
        public String xiyou = "check_runtime_discovery";
        public String erp = "check_runtime_discovery";
    }

    public static Availability getResearchAvailability(ResearchTypes.Scope scope, boolean sellerConfigured,
                                                       boolean sellerSettingError, String aiState,
                                                       String provider, String model) {
        Availability out = new Availability();
        out.scope = scope;
        out.sellerSprite = sellerSettingError ? "provider_configuration_unavailable"
                : sellerConfigured ? "configured_runtime_registry_not_yet_checked" : "missing_existing_provider_configuration";
        out.ai = "permission_required".equals(aiState) ? "runtime_model_permission_required"
                : "unavailable".equals(aiState) ? "model_configuration_unavailable"
                : "configured".equals(aiState) ? "configured" : "missing_existing_model_configuration";
        if ("configured".equals(out.ai)) out.aiModel = Map.of("provider", provider == null ? "" : provider,
                "model", model == null ? "" : model);
        return out;
    }

    /** 已存在的运行按 mode 装配；暂停/历史/导出一律使用存储引擎，不打开外部连接。 */
    public static AccessResult researchAccess(AccessPolicy policy, Map<String, Object> body, ServerDeps deps) {
        AccessResult out = new AccessResult();
        Permission permission = policy.permission("agents", String.valueOf(body.getOrDefault("operation", "view")));
        if (permission == null || !permission.ok) { out.error = "RESEARCH_SCOPE_DENIED"; return out; }
        Object workspace = body.get("workspaceId");
        if (workspace == null || !policy.workspaceAllowed(permission, String.valueOf(workspace))) {
            out.error = "RESEARCH_SCOPE_DENIED";
            return out;
        }
        ResearchTypes.Scope scope = new ResearchTypes.Scope(permission.organizationId, String.valueOf(workspace), permission.userId);
        out.scope = scope;
        out.mode = "live".equals(body.get("mode")) ? "live" : "fixture";

        if (body.get("runId") instanceof String) {
            ResearchTypes.ResearchRun existing;
            try {
                existing = deps.repository.read((String) body.get("runId"), scope).run;
            } catch (ResearchError e) {
                if ("RESEARCH_NOT_FOUND".equals(e.code())) { out.error = e.code(); return out; }
                throw e;
            }
            if (existing.pausedAt != null || !"step".equals(body.get("operation"))) {
                out.mode = existing.mode;
                out.engine = ResearchHttp.storedResearchEngine(
                        new LeaseResearchStore(deps.repository, scope), scope, existing.mode);
                if ("live".equals(existing.mode) && deps.consents != null)
                    out.paidCallConsent = deps.consents.read(scope);
                out.ok = true;
                return out;
            }
            out.mode = existing.mode;
            body = new LinkedHashMap<>(body);
            body.put("erpCredentialId", existing.erpCredentialId);
            body.put("aiConfigSource", existing.aiConfigSource);
            body.put("budgetMode", existing.budgetMode);
            body.put("executionMode", existing.executionMode);
        }
        if ("fixture".equals(out.mode)) {
            Fixture.Components fixture = Fixture.researchFixture();
            out.engine = new ResearchEngine(new LeaseResearchStore(deps.repository, scope), scope,
                    fixture.transport, fixture.agent, new ResearchEngine.Options("fixture"));
            out.ok = true;
            return out;
        }
        if ("mock_unlimited".equals(body.get("budgetMode"))) throw new ResearchError("LIVE_BUDGET_REVIEW_REQUIRED");
        ResearchEngine.Options options = new ResearchEngine.Options("live",
                deps.consents == null ? null : deps.consents.read(scope), deps.marketQuota, deps.liveAuthorizer, 30_000L);
        out.engine = new ResearchEngine(new LeaseResearchStore(deps.repository, scope), scope,
                deps.liveTransport, deps.liveAgent, options);
        out.ok = true;
        return out;
    }
}

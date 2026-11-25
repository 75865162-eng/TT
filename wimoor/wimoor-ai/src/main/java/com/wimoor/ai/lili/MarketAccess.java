package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.wimoor.ai.lili.Ports.McpTransport;
import com.wimoor.ai.lili.Ports.ToolNotDispatchedException;

/** 移植自 src/lib/lili/market-access.ts：市场来源的实时传输封装与访问摘要。 */
public final class MarketAccess {

    private MarketAccess() {}

    public enum ProviderStatus {
        CONFIGURED_NOT_PROBED("configured_not_probed"),
        DISABLED_OR_MISSING("disabled_or_missing"),
        CONFIGURATION_UNAVAILABLE("configuration_unavailable");
        private final String wire;
        ProviderStatus(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public static final class MarketProviderAccess {
        public final String provider;
        public final boolean configured;
        public final ProviderStatus status;
        public MarketProviderAccess(String provider, boolean configured, ProviderStatus status) {
            this.provider = provider; this.configured = configured; this.status = status;
        }
        public Map<String, Object> toMap(boolean allowed) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", provider); m.put("configured", configured); m.put("status", status.wire()); m.put("allowed", allowed);
            return m;
        }
    }

    /** 每次发现/调用前刷新配置；保存的审批不能保留已撤销的访问。 */
    public static McpTransport liveMarketTransport(Supplier<McpTransport> resolve) {
        return new McpTransport() {
            private McpTransport current() {
                try {
                    McpTransport t = resolve.get();
                    if (t == null) throw new ToolNotDispatchedException("RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN");
                    return t;
                } catch (ToolNotDispatchedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ToolNotDispatchedException("RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN");
                }
            }
            @Override public List<Map<String, Object>> listTools() throws Exception { return current().listTools(); }
            @Override public Object callTool(String name, Map<String, Object> args, Map<String, Object> schema) throws Exception {
                return current().callTool(name, args, schema);
            }
        };
    }

    /** marketAccessSummary：direct_market_access 恒 false，费用审批不在没有用户/Skill 要求时触发。 */
    public static Map<String, Object> marketAccessSummary(boolean authenticated, boolean canResearch,
                                                          List<MarketProviderAccess> providers) {
        boolean researchAccess = canResearch && providers.stream().anyMatch(p -> p.configured);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MarketProviderAccess p : providers) rows.add(p.toMap(canResearch && p.configured));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authenticated", authenticated);
        m.put("direct_market_access", false);
        m.put("paid_tools", new ArrayList<>());
        m.put("estimated_cost", null);
        m.put("cost_status", "unknown");
        m.put("approval_required", false);
        m.put("research_access", researchAccess);
        m.put("providers", rows);
        m.put("reason", !canResearch ? "RESEARCH_PERMISSION_REQUIRED" : "FEE_APPROVAL_ONLY_WHEN_USER_OR_SKILL_REQUIRES");
        m.put("registry_checked", false);
        return m;
    }
}

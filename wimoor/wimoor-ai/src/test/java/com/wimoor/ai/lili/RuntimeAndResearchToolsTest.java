package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wimoor.ai.lili.MarketAccess.MarketProviderAccess;
import com.wimoor.ai.lili.MarketAccess.ProviderStatus;
import com.wimoor.ai.lili.Ports.McpTransport;
import com.wimoor.ai.lili.Ports.PermissionAction;
import com.wimoor.ai.lili.Ports.ToolNotDispatchedException;
import com.wimoor.ai.lili.ResearchTools.ResearchRequest;
import com.wimoor.ai.lili.RuntimeTools.Availability;
import com.wimoor.ai.lili.RuntimeTools.Permission;
import com.wimoor.ai.lili.RuntimeTools.ProviderId;
import com.wimoor.ai.lili.RuntimeTools.RuntimeProvider;
import com.wimoor.ai.lili.RuntimeTools.RuntimeToolError;
import com.wimoor.ai.lili.RuntimeTools.RuntimeTool;
import com.wimoor.ai.lili.SyncChatTools.SyncChatDependencies;
import com.wimoor.ai.lili.SyncChatTools.SyncChatPreview;

/** 工具注册、参数校验、路由判定与积分预览的确定性测试。 */
class RuntimeAndResearchToolsTest {

    private static Map<String, Object> toolDef(String name, boolean readOnly) {
        return Ports.jsonObject("name", name, "description", name,
            "inputSchema", Ports.jsonObject("type", "object", "properties", new LinkedHashMap<>()),
            "annotations", Ports.annotations(readOnly));
    }

    private static RuntimeTools withErpProvider() {
        McpTransport transport = new McpTransport() {
            @Override public List<Map<String, Object>> listTools() {
                return List.of(toolDef("ads_get_data_health", true), toolDef("ads_prepare_changes", false));
            }
            @Override public Object callTool(String name, Map<String, Object> args, Map<String, Object> schema) {
                return Ports.jsonObject("ok", true, "tool", name);
            }
        };
        return RuntimeTools.createRuntimeTools(List.of(new RuntimeProvider(ProviderId.ERP, transport, null,
            t -> Boolean.TRUE.equals(JsonSupport.obj(t.get("annotations")).get("readOnlyHint")))));
    }

    @Test
    void skillToolsAreRegisteredWithoutProviders() {
        RuntimeTools tools = RuntimeTools.createRuntimeTools(new ArrayList<>());
        Set<String> names = new java.util.HashSet<>();
        tools.discover().tools.forEach(t -> names.add(t.name));
        assertTrue(names.containsAll(List.of("meta_search_skills", "meta_load_skill", "meta_list_workflows", "lili_ping")));
        Map<String, Object> ping = JsonSupport.obj(tools.call("lili_ping", Map.of()));
        assertEquals(true, ping.get("ok"));
        assertEquals("lili-mcp", ping.get("server"));
        assertEquals(SkillMeta.LILI_VERSION, ping.get("version"));
        assertThrows(RuntimeToolError.class, () -> tools.call("unknown__tool", Map.of()));
    }

    @Test
    void providerRegistryDerivesPermissionAndAvailability() {
        RuntimeTools tools = withErpProvider();
        RuntimeTool read = null, write = null;
        for (RuntimeTool t : tools.discover().tools) {
            if ("erp__ads_get_data_health".equals(t.name)) read = t;
            if ("erp__ads_prepare_changes".equals(t.name)) write = t;
        }
        assertNotNull(read);
        assertEquals(Permission.READ, read.permission);
        assertEquals(Availability.AVAILABLE, read.availability);
        assertEquals("ads_get_data_health", read.remoteName);
        assertNotNull(write);
        assertEquals(Permission.APPROVAL_REQUIRED, write.permission);
        assertEquals(Availability.BLOCKED, write.availability);
        assertEquals("connected", tools.discover().providers.get(0).status);
        assertEquals(2, tools.discover().providers.get(0).toolCount);
        assertThrows(RuntimeToolError.class, () -> tools.call("erp__ads_prepare_changes", Map.of()));
        assertEquals(true, JsonSupport.obj(tools.call("erp__ads_get_data_health", Map.of())).get("ok"));
    }

    @Test
    void argumentsAndCredentialsAreCheckedBeforeDispatch() {
        RuntimeTools tools = withErpProvider();
        assertThrows(RuntimeToolError.class, () -> tools.call("lili_ping", Map.of("extra", "x")));
        assertThrows(ToolNotDispatchedException.class,
            () -> tools.call("erp__ads_get_data_health", Map.of("apiKey", "leak")));
        assertThrows(ToolNotDispatchedException.class,
            () -> tools.call("erp__ads_get_data_health", Map.of("count", 1), Map.of("type", "string")));
    }

    @Test
    void researchGrantsAndMarketSummaryUseExplicitRules() {
        assertTrue(ResearchGrants.isResearchTool("research_confirm"));
        assertFalse(ResearchGrants.isResearchTool("ads_get_data_health"));
        assertEquals(PermissionAction.APPROVE, ResearchGrants.RESEARCH_TOOL_ACTIONS.get("research_confirm"));
        assertEquals("operations", ResearchGrants.liliToolModule("workflow_sync_review"));
        assertEquals("agents", ResearchGrants.liliToolModule("research_start"));
        Map<String, Object> summary = MarketAccess.marketAccessSummary(true, true, List.of(
            new MarketProviderAccess("xiyou", true, ProviderStatus.CONFIGURED_NOT_PROBED),
            new MarketProviderAccess("sellersprite", false, ProviderStatus.DISABLED_OR_MISSING)));
        assertEquals(true, summary.get("research_access"));
        assertEquals(false, summary.get("approval_required"));
        assertEquals(false, summary.get("direct_market_access"));
        assertEquals("FEE_APPROVAL_ONLY_WHEN_USER_OR_SKILL_REQUIRES", summary.get("reason"));
        assertEquals(false, MarketAccess.marketAccessSummary(true, false, List.of()).get("research_access"));
    }

    private static SyncChatPreview preview(String operation, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "req-1");
        row.put("operation", operation);
        row.put("status", status);
        row.put("changeDigest", "a".repeat(64));
        row.put("baseRevision", 0);
        row.put("expiresAt", 10_000L);
        if ("control-task".equals(operation)) {
            row.put("afterSummary", Ports.jsonObject("control", "retry", "id", "task-1"));
            row.put("targetId", "task-1");
            row.put("domains", List.of("orders"));
            row.put("sourceIds", List.of("src-1"));
            row.put("marketplaceIds", List.of("ATVPDKIKX0DER"));
        }
        return SyncChatPreview.from(row);
    }

    @Test
    void syncChatRequestSupportIsDeterministic() {
        assertTrue(SyncChatTools.supportedSyncChatRequest(preview("run", "REQUESTED")));
        assertTrue(SyncChatTools.supportedSyncChatRequest(preview("control-task", "REQUESTED")));
        assertFalse(SyncChatTools.supportedSyncChatRequest(preview("config-change", "REQUESTED")));
        SyncChatPreview bad = preview("control-task", "REQUESTED");
        bad.raw.put("domains", List.of("unsupported_domain"));
        assertFalse(SyncChatTools.supportedSyncChatRequest(bad));
    }

    @Test
    void syncSourcesMustBeInBothScopes() {
        SyncChatTools.Source source = new SyncChatTools.Source("src-1", "seller-1", "US");
        List<SyncChatTools.AccessScope> cred = List.of(new SyncChatTools.AccessScope("seller-1", List.of("US")));
        assertTrue(SyncChatTools.syncSourcesWithinScope(List.of(source), List.of("src-1"), cred, cred));
        assertFalse(SyncChatTools.syncSourcesWithinScope(List.of(source), List.of("other"), cred, cred));
        assertFalse(SyncChatTools.syncSourcesWithinScope(List.of(source), List.of("src-1"), cred,
            List.of(new SyncChatTools.AccessScope("seller-2", List.of()))));
    }

    @Test
    void syncChatReviewAndConfirmFlow() throws Exception {
        final SyncChatPreview[] row = {preview("run", "REQUESTED")};
        SyncChatDependencies deps = new SyncChatDependencies() {
            @Override public SyncChatPreview inspect() { return row[0]; }
            @Override public SyncChatPreview approve(String quote) {
                row[0] = preview("run", "APPROVED");
                return row[0];
            }
            @Override public Object apply() { return Ports.jsonObject("applied", true); }
        };
        Map<String, Object> review = SyncChatTools.executeSyncChat("workflow_sync_review",
            Map.of("workspaceId", "ws-1", "requestId", "req-1"), deps, 1000);
        assertEquals(true, review.get("ok"));
        assertEquals("CHAT_CONFIRM", JsonSupport.obj(review.get("nextAction")).get("kind"));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("workspaceId", "ws-1"); args.put("requestId", "req-1");
        args.put("expectedDigest", "a".repeat(64)); args.put("expectedRevision", 0);
        args.put("userConfirmation", "我确认批准该同步申请");
        Map<String, Object> confirmed = SyncChatTools.executeSyncChat("workflow_sync_confirm", args, deps, 1000);
        assertEquals(true, confirmed.get("ok"));
        assertEquals("CONTINUE", JsonSupport.obj(confirmed.get("nextAction")).get("kind"));
        assertEquals(true, JsonSupport.obj(confirmed.get("result")).get("applied"));
    }

    @Test
    void publicResearchPrepareParsesConstraintsWithoutPersistence() {
        Map<String, Object> out = PublicResearchTools.callPublicResearchTool("research_prepare",
            Map.of("requirement", "研究美国市场的 B0ABCDEF12 竞品", "marketplace", "US"));
        assertEquals(true, out.get("ok"));
        assertEquals(0, out.get("stage"));
        assertEquals(false, out.get("persisted"));
        assertEquals(5, ((List<?>) out.get("skills")).size());
        assertEquals("B0ABCDEF12", JsonSupport.obj(out.get("brief")).get("asin"));
        assertEquals("AUTHENTICATE_FOR_DATA", JsonSupport.obj(out.get("nextAction")).get("kind"));
        assertThrows(IllegalStateException.class,
            () -> PublicResearchTools.callPublicResearchTool("research_prepare", Map.of("requirement", "  ")));
        Map<String, Object> conflict = PublicResearchTools.callPublicResearchTool("research_prepare",
            Map.of("requirement", "分析日本市场", "marketplace", "US"));
        assertEquals(true, JsonSupport.obj(conflict.get("marketplaceResolution")).get("conflict"));
    }

    @Test
    void blockedResearchActionsMapToSafeNextSteps() {
        assertEquals("AUTHENTICATE_FOR_DATA", ResearchTools.blockedResearchAction(401, null).get("kind"));
        assertEquals("CHECK_DATA_PERMISSION", ResearchTools.blockedResearchAction(403, null).get("kind"));
        assertEquals("CONFIGURE_MODEL", ResearchTools.blockedResearchAction(500, "AI_MODEL_NOT_CONFIGURED").get("kind"));
        assertEquals("CONNECT_MARKET_SOURCE", ResearchTools.blockedResearchAction(500, "NO_SUPPORTED_LIVE_TOOLS").get("kind"));
        assertEquals("REVIEW_FAILURE", ResearchTools.blockedResearchAction(500, "UNKNOWN").get("kind"));
        ResearchRequest req = new ResearchRequest("GET", "/api/selection-research/run-1", null);
        assertEquals("GET", req.method);
    }
}

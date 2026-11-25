package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 审批策略、标签、路径常量与连接地址的确定性测试。 */
class PolicyAndRoutingTest {

    @Test
    void approvalPolicyFieldsMatchShippedConstants() {
        Map<String, Object> p = ApprovalPolicy.toMap();
        assertEquals(1, p.get("version"));
        assertEquals("codex_chat", p.get("channel"));
        assertEquals("specific_task_plan", p.get("approvalUnit"));
        assertEquals(false, p.get("repeatApprovalWithinScope"));
        assertEquals(false, p.get("browserRequired"));
        assertEquals(true, p.get("scopeChangesRequireChatConfirmation"));
        assertEquals(true, p.get("businessChoicesRequireHumanReply"));
        assertEquals("report_in_chat_without_dispatch", p.get("unsupportedExecution"));
    }

    @Test
    void approvalInstructionsAreLocalized() {
        assertTrue(ApprovalPolicy.INSTRUCTIONS_ZH.contains("审批统一在 Codex 对话中完成"));
        assertTrue(ApprovalPolicy.INSTRUCTIONS_EN.contains("All Lili approvals happen in Codex chat"));
        assertNotEquals(ApprovalPolicy.INSTRUCTIONS_ZH, ApprovalPolicy.INSTRUCTIONS_EN);
    }

    @Test
    void labelsAndTxSelectLocale() {
        assertEquals("报表与分析", Labels.tagLabel(Tx.Locale.ZH, "reports"));
        assertEquals("Reports and analysis", Labels.tagLabel(Tx.Locale.EN, "reports"));
        assertEquals("unknown-id", Labels.tagLabel(Tx.Locale.EN, "unknown-id"));
        assertEquals("中文", Tx.tx(Tx.Locale.ZH, "中文", "English"));
        assertEquals("English", Tx.tx(Tx.Locale.EN, "中文", "English"));
    }

    @Test
    void basePathHandlesApiAndPageRoutes() {
        assertEquals("/api/lili/mcp", BasePath.withBasePath("/api/mcp"));
        assertEquals("/api/lili", BasePath.withBasePath("/api"));
        assertEquals("/lili/skills", BasePath.withBasePath("skills"));
        assertEquals("/lili/skills", BasePath.withBasePath("/lili/skills"));
        assertEquals("/lili", BasePath.BASE_PATH);
    }

    @Test
    void navigationFiltersHiddenPages() {
        List<String> paths = new java.util.ArrayList<>();
        Routes.LILI_NAVIGATION.forEach(p -> paths.add(p.path));
        assertTrue(paths.contains("skills"));
        assertFalse(paths.contains("team"));
        assertFalse(paths.contains("settings"));
        assertFalse(paths.contains("help"));
    }

    @Test
    void resolveLiliPageHandlesKnownAndRejectedPaths() {
        assertEquals("start", Routes.resolveLiliPage(List.of()).kind);
        assertEquals("login", Routes.resolveLiliPage(List.of("login")).kind);
        Routes.LiliPage p = Routes.resolveLiliPage(List.of("v2", "lab", "skills", "abc"));
        assertNotNull(p);
        assertEquals("skills", p.kind);
        assertEquals("abc", p.slug);
        assertEquals("agents", p.moduleId);
        assertEquals("accounts", Routes.resolveLiliPage(List.of("v2", "team")).moduleId);
        assertEquals("settings", Routes.resolveLiliPage(List.of("v2", "tool-config")).moduleId);
        assertNull(Routes.resolveLiliPage(List.of("v2", "settings", "abc")));
        assertNull(Routes.resolveLiliPage(List.of("v1", "skills")));
    }

    @Test
    void clientConnectionAcceptsHttpsAndLoopbackHttp() {
        ClientConnection.Connection https = ClientConnection.liliClientConnection("https://erp.example.com");
        assertEquals("https://erp.example.com/api/lili/mcp", https.endpoint);
        assertTrue(https.toml.contains("url = \"https://erp.example.com/api/lili/mcp\""));
        assertEquals("codex mcp login lili", https.loginCommand);
        assertTrue(https.verifyPrompt.contains("lili_ping"));
        assertEquals("http://localhost:3000/api/lili/mcp",
            ClientConnection.liliClientConnection("http://localhost:3000").endpoint);
    }

    @Test
    void clientConnectionRejectsInsecureAndComplexOrigins() {
        assertThrows(SyncInputException.class, () -> ClientConnection.liliClientConnection("http://erp.example.com"));
        assertThrows(SyncInputException.class, () -> ClientConnection.liliClientConnection("https://u:p@erp.example.com"));
        assertThrows(SyncInputException.class, () -> ClientConnection.liliClientConnection("https://erp.example.com/path"));
        assertThrows(SyncInputException.class, () -> ClientConnection.liliClientConnection("https://erp.example.com/?a=1"));
    }
}

package com.wimoor.ai.chat;

import com.wimoor.ai.chat.Billing.InMemoryPointsLedger;
import com.wimoor.ai.chat.ChatTypes.ChatAnswer;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ChatTypes.ToolDescriptor;
import com.wimoor.ai.chat.ChatTypes.ToolResult;
import com.wimoor.ai.chat.ConversationRepository.Conversation;
import com.wimoor.ai.chat.ConversationRepository.PreparedMessage;
import com.wimoor.ai.chat.ConversationRepository.Run;
import com.wimoor.ai.chat.DataSources.ChatDataProvider;
import com.wimoor.ai.chat.Orchestrator.OrchestrateInput;
import com.wimoor.ai.chat.Runtime.BuildInput;
import com.wimoor.ai.chat.Runtime.ToolTransport;
import com.wimoor.ai.skill.AiModelSettings;
import com.wimoor.ai.skill.Config;
import com.wimoor.ai.skill.LlmClient.InMemory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 orchestrator.ts：动作解析、结果绑定、多轮编排、积分 meter、取消与错误归类。 */
class OrchestratorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
    private final ChatScope scope = new ChatScope("org", "ws", "user");
    private final AiModelSettings settings = new AiModelSettings("sk-secret", "gpt-test", "openai", "chat_completions", "http://llm");

    private ToolDescriptor tool(String name, String provider) {
        ToolDescriptor t = new ToolDescriptor();
        t.setName(name); t.setProvider(provider); t.setReadOnlyHint(true);
        t.setPermission("read"); t.setAvailability("available");
        t.setDispatch(args -> new ToolResult(linked("rowCount", 2, "source", provider), provider, 2));
        return t;
    }

    private Runtime.ChatRuntime runtime(String wire, String toolName) {
        ToolTransport transport = new ToolTransport() {
            public String id() { return wire; }
            public List<ToolDescriptor> listTools() { return Collections.singletonList(tool(toolName, wire)); }
            public Object callTool(String name, Map<String, Object> args) {
                return new ToolResult(linked("rowCount", 2, "source", wire), wire, 2);
            }
        };
        return Runtime.buildChatRuntime(new BuildInput().scope(scope)
                .enabledProviders(List.of(ChatDataProvider.from(wire)))
                .transportFactories(mapOf(wire, s -> transport))
                .workspaceGuard(s -> true));
    }

    private static Map<String, Runtime.TransportFactory> mapOf(String key, Runtime.TransportFactory factory) {
        Map<String, Runtime.TransportFactory> map = new LinkedHashMap<>();
        map.put(key, factory);
        return map;
    }

    private static Map<String, Object> linked(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private Run runningRun(InMemoryConversationRepository repo) {
        Conversation c = repo.createConversation(scope, "会话");
        PreparedMessage pm = repo.prepareUserMessage(scope, c.getId(), "问题", "ck", "ik", null, null);
        return repo.createRun(scope, c.getId(), pm.getMessageId(), pm.getRequestKey(), null, null);
    }

    @Test
    void parsesToolAndAnswerActions() {
        Orchestrator.Action tool = Orchestrator.parseAction(
                "前缀 {\"action\":\"tool\",\"tool\":\"skill_list\",\"arguments\":{\"slug\":\"x\"},\"reason\":\"找技能\"} 后缀");
        assertNotNull(tool);
        assertEquals("tool", tool.getAction());
        assertEquals("skill_list", tool.getTool());
        assertEquals("x", tool.getArguments().get("slug"));
        assertEquals("找技能", tool.getReason());

        Orchestrator.Action answer = Orchestrator.parseAction(
                "{\"action\":\"answer\",\"text\":\"答案\",\"coverage\":[\"US\"],\"skipped\":[\"写入\"]}");
        assertNotNull(answer);
        assertEquals("答案", answer.getAnswer());
        assertEquals(List.of("US"), answer.getCoverage());
        assertEquals(List.of("写入"), answer.getSkipped());

        assertNull(Orchestrator.parseAction("no json"));
        assertNull(Orchestrator.parseAction("{\"action\":\"unknown\"}"));
    }

    @Test
    void boundResultExtractsSourceAndTruncates() {
        Orchestrator.BoundResult bounded = Orchestrator.boundResult(
                new ToolResult(linked("rowCount", 5, "source", "erp"), null, 7));
        assertEquals(Integer.valueOf(5), bounded.getRowCount());
        assertEquals("erp", bounded.getSource());

        Orchestrator.BoundResult longResult = Orchestrator.boundResult(new String(new char[25000]).replace('\0', 'x'));
        assertTrue(longResult.getText().length() > 24000);
        assertTrue(longResult.getText().contains("结果已截断"));
    }

    @Test
    void multiTurnLoopMetersPaidToolAndReturnsEvidence() {
        InMemoryConversationRepository repo = new InMemoryConversationRepository(FIXED, () -> "id");
        Run run = runningRun(repo);
        InMemoryPointsLedger ledger = new InMemoryPointsLedger(new BigDecimal("10"), false);
        String toolCall = "{\"action\":\"tool\",\"tool\":\"sellersprite__product_search\",\"arguments\":{\"asin\":\"B0X\"},\"reason\":\"查产品\"}";
        String answer = "{\"action\":\"answer\",\"text\":\"最终答案\",\"coverage\":[\"US站\"],\"skipped\":[\"未写入\"]}";

        ChatAnswer result = Orchestrator.orchestrateAnswer(new OrchestrateInput()
                .scope(scope).question("问题").settings(settings).runId(run.getId())
                .runtime(runtime("sellersprite", "sellersprite__product_search"))
                .llmClient(new InMemory(toolCall, answer)).repository(repo).ledger(ledger)
                .catalog(new Config.InMemorySkillCatalog()).clock(FIXED));

        assertEquals("最终答案", result.getText());
        assertEquals(run.getId(), result.getRunId());
        assertEquals("gpt-test", result.getModel());
        assertEquals(List.of("US站"), result.getCoverage());
        assertEquals(1, result.getTools().size());
        assertEquals("sellersprite", result.getTools().get(0).getProvider());
        assertEquals("product_search", result.getTools().get(0).getTool());
        assertEquals(Integer.valueOf(2), result.getTools().get(0).getRowCount());
        assertEquals("sellersprite", result.getTools().get(0).getResultSource());
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("8.0")));
        assertEquals(1, repo.debugState().get("toolCalls"));
    }

    @Test
    void freeProviderDoesNotChargePoints() {
        InMemoryConversationRepository repo = new InMemoryConversationRepository(FIXED, () -> "id");
        Run run = runningRun(repo);
        InMemoryPointsLedger ledger = new InMemoryPointsLedger(new BigDecimal("10"), false);
        String toolCall = "{\"action\":\"tool\",\"tool\":\"erp__list_orders\",\"arguments\":{},\"reason\":\"查订单\"}";
        String answer = "{\"action\":\"answer\",\"text\":\"免费数据\",\"coverage\":[],\"skipped\":[]}";
        Orchestrator.orchestrateAnswer(new OrchestrateInput()
                .scope(scope).question("问题").settings(settings).runId(run.getId())
                .runtime(runtime("amazon", "erp__list_orders"))
                .llmClient(new InMemory(toolCall, answer)).repository(repo).ledger(ledger)
                .clock(FIXED));
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("10")));
        assertTrue(ledger.entries().isEmpty());
    }

    @Test
    void cancelledRunStopsBeforeModelCall() {
        InMemoryConversationRepository repo = new InMemoryConversationRepository(FIXED, () -> "id");
        Run run = runningRun(repo);
        repo.cancelRun(scope, run.getId());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                Orchestrator.orchestrateAnswer(new OrchestrateInput()
                        .scope(scope).question("问题").settings(settings).runId(run.getId())
                        .runtime(runtime("amazon", "erp__list_orders"))
                        .llmClient(new InMemory("{\"action\":\"answer\",\"text\":\"x\"}"))
                        .repository(repo).clock(FIXED)));
        assertEquals("CANCELLED", ex.getMessage());
    }

    @Test
    void describesErrorsWithoutLeakingSecrets() {
        assertEquals("已取消本次分析。", Orchestrator.describeOrchestratorError(new RuntimeException("CANCELLED")).get("message"));
        assertEquals("模型调用失败或返回异常，请检查模型配置后重试。",
                Orchestrator.describeOrchestratorError(new RuntimeException("AI_HTTP_500")).get("message"));
        assertEquals("查询或工具调用失败，请稍后重试。",
                Orchestrator.describeOrchestratorError(new RuntimeException("TOOL_NOT_ALLOWED")).get("message"));
        assertEquals("本轮未能得出结论，请换一种问法或缩小数据范围。",
                Orchestrator.describeOrchestratorError(new RuntimeException("NO_FINAL_ANSWER")).get("message"));
        assertEquals("工作区已变化", Orchestrator.describeOrchestratorError(new RuntimeException("工作区已变化")).get("message"));
    }

    @Test
    void missingApiKeyAndUnsafeResponseAreRejected() {
        AiModelSettings noKey = new AiModelSettings("", "m", "p", "chat_completions", "http://llm");
        assertEquals("AI_NOT_CONFIGURED", assertThrows(IllegalStateException.class,
                () -> Orchestrator.callModel(noKey, "s", "u", new InMemory("{}"))).getMessage());
        assertEquals("UNSAFE_OR_EMPTY_AI_RESPONSE", assertThrows(IllegalStateException.class,
                () -> Orchestrator.callModel(settings, "s", "u", new InMemory("含 sk-secret 密钥"))).getMessage());
    }
}

package com.wimoor.ai.chat;

import com.wimoor.ai.chat.ChatTypes.ChatProviderStatus;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ChatTypes.DiscoveryResult;
import com.wimoor.ai.chat.ChatTypes.ProviderStatusKind;
import com.wimoor.ai.chat.ChatTypes.ToolDescriptor;
import com.wimoor.ai.chat.ChatTypes.ToolResult;
import com.wimoor.ai.chat.DataSources.ChatDataProvider;
import com.wimoor.ai.chat.Runtime.BuildInput;
import com.wimoor.ai.chat.Runtime.ChatRuntime;
import com.wimoor.ai.chat.Runtime.SessionException;
import com.wimoor.ai.chat.Runtime.ToolTransport;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 runtime.ts：provider 装配、只读策略、会话/工作区复验、describeTool 前缀映射。 */
class RuntimeTest {

    private final ChatScope scope = new ChatScope("org", "ws", "user");

    private ToolDescriptor tool(String name, String provider, boolean readOnly) {
        ToolDescriptor t = new ToolDescriptor();
        t.setName(name); t.setProvider(provider); t.setReadOnlyHint(readOnly);
        t.setDescription("desc"); t.setInputSchema(new LinkedHashMap<>());
        return t;
    }

    private ToolTransport transport(String id, List<ToolDescriptor> tools) {
        return new ToolTransport() {
            public String id() { return id; }
            public List<ToolDescriptor> listTools() { return tools; }
            public Object callTool(String name, Map<String, Object> args) {
                return new ToolResult(Collections.singletonMap("rows", Collections.emptyList()), id, 0);
            }
        };
    }

    @Test
    void assemblesOnlyEnabledProviders() {
        BuildInput input = new BuildInput().scope(scope)
                .enabledProviders(Arrays.asList(ChatDataProvider.AMAZON, ChatDataProvider.SELLERSPRITE))
                .transportFactories(mapOf("amazon", s -> transport("erp", List.of(tool("erp__list_orders", "amazon", true))),
                        "sellersprite", s -> transport("sellersprite",
                                List.of(tool("sellersprite__product_search", "sellersprite", true)))))
                .workspaceGuard(s -> true);
        ChatRuntime runtime = Runtime.buildChatRuntime(input);
        DiscoveryResult discovered = runtime.discover();
        assertEquals(2, discovered.getProviders().size());
        assertTrue(discovered.getProviders().stream().allMatch(p -> p.getStatus() == ProviderStatusKind.CONNECTED));
        assertTrue(discovered.getTools().stream().anyMatch(t -> "erp__list_orders".equals(t.getName())));
        assertTrue(discovered.getTools().stream().noneMatch(t -> "sellfox".equals(t.getProvider())));
        ToolDescriptor amazonTool = discovered.getTools().get(0);
        assertEquals("read", amazonTool.getPermission());
        assertEquals("available", amazonTool.getAvailability());
    }

    @Test
    void unconfiguredOrFailedTransportIsExposedAsUnavailable() {
        ChatRuntime runtime = Runtime.buildChatRuntime(new BuildInput().scope(scope)
                .enabledProviders(Arrays.asList(ChatDataProvider.SELLFOX, ChatDataProvider.XIYOU))
                .transportFactories(mapOf("xiyou", s -> { throw new IllegalStateException("no token"); }))
                .workspaceGuard(s -> true));
        List<ChatProviderStatus> providers = runtime.discover().getProviders();
        assertEquals(2, providers.size());
        assertEquals("SELLFOX_ERP_SESSION_UNAVAILABLE", providers.get(0).getReason());
        assertEquals("XIYOU_MCP_UNAVAILABLE", providers.get(1).getReason());
        assertTrue(providers.stream().allMatch(p -> p.getStatus() == ProviderStatusKind.UNAVAILABLE));
    }

    @Test
    void sellerSpriteAndXiYouReadOnlyPoliciesBlockWrites() {
        assertTrue(Runtime.sellerSpritePolicy(tool("sellersprite__product_search", "sellersprite", true)));
        assertFalse(Runtime.sellerSpritePolicy(tool("sellersprite__write_campaign", "sellersprite", false)));
        assertFalse(Runtime.sellerSpritePolicy(tool("sellersprite__unknown_tool", "sellersprite", true)));
        assertTrue(Runtime.xiyouReadPolicy(tool("xiyou__keyword_rank", "xiyou", true)));
        assertFalse(Runtime.xiyouReadPolicy(tool("xiyou__create_order", "xiyou", false)));

        ChatRuntime runtime = Runtime.buildChatRuntime(new BuildInput().scope(scope)
                .enabledProviders(Arrays.asList(ChatDataProvider.SELLERSPRITE, ChatDataProvider.XIYOU))
                .transportFactories(mapOf("sellersprite", s -> transport("sellersprite", Arrays.asList(
                        tool("sellersprite__product_search", "sellersprite", true),
                        tool("sellersprite__write_campaign", "sellersprite", false))),
                        "xiyou", s -> transport("xiyou", List.of(tool("xiyou__keyword_rank", "xiyou", true)))))
                .workspaceGuard(s -> true));
        List<ToolDescriptor> tools = runtime.discover().getTools();
        assertEquals("available", tools.get(0).getAvailability());
        assertEquals("blocked", tools.get(1).getAvailability());
        assertThrows(Runtime.ToolAccessException.class,
                () -> runtime.call("sellersprite__write_campaign", new LinkedHashMap<>()));
    }

    @Test
    void sessionAndWorkspaceChangesStopTheRuntime() {
        AtomicReference<Boolean> sessionValid = new AtomicReference<>(false);
        SessionException ex = assertThrows(SessionException.class, () -> Runtime.buildChatRuntime(
                new BuildInput().scope(scope)
                        .sessionGuard(() -> { if (!sessionValid.get()) throw new SessionException("登录已变化", 401); })
                        .workspaceGuard(s -> true)));
        assertEquals(401, ex.getStatus());

        SessionException workspace = assertThrows(SessionException.class, () -> Runtime.buildChatRuntime(
                new BuildInput().scope(scope).workspaceGuard(s -> false)));
        assertTrue(workspace.getMessage().contains("工作区"));

        sessionValid.set(true);
        AtomicInteger checks = new AtomicInteger();
        ChatRuntime runtime = Runtime.buildChatRuntime(new BuildInput().scope(scope)
                .enabledProviders(List.of(ChatDataProvider.AMAZON))
                .transportFactories(mapOf("amazon", s -> transport("erp", List.of(tool("erp__list_orders", "amazon", true)))))
                .sessionGuard(checks::incrementAndGet)
                .workspaceGuard(s -> true));
        int before = checks.get();
        runtime.call("erp__list_orders", new LinkedHashMap<>());
        assertTrue(checks.get() - before >= 2, "调用前后都应复验会话");
    }

    @Test
    void describeToolMapsInternalNamesToProviders() {
        assertEquals("amazon", Runtime.describeTool("erp__list_orders").getProvider());
        assertEquals("list_orders", Runtime.describeTool("erp__list_orders").getTool());
        assertEquals("sellfox", Runtime.describeTool("sellfox__list_stores").getProvider());
        assertEquals("sellersprite", Runtime.describeTool("sellersprite__product_search").getProvider());
        assertEquals("xiyou", Runtime.describeTool("xiyou__keyword_rank").getProvider());
        assertEquals("skills", Runtime.describeTool("skill_run").getProvider());
        assertEquals("product_search", Runtime.stripPrefix("sellersprite__product_search"));
    }

    private static Map<String, Runtime.TransportFactory> mapOf(String key, Runtime.TransportFactory f) {
        Map<String, Runtime.TransportFactory> map = new LinkedHashMap<>();
        map.put(key, f);
        return map;
    }

    private static Map<String, Runtime.TransportFactory> mapOf(String k1, Runtime.TransportFactory f1,
                                                               String k2, Runtime.TransportFactory f2) {
        Map<String, Runtime.TransportFactory> map = mapOf(k1, f1);
        map.put(k2, f2);
        return map;
    }
}

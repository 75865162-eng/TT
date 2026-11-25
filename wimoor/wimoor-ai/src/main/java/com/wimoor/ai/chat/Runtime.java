package com.wimoor.ai.chat;

import com.wimoor.ai.chat.ChatTypes.ChatProviderStatus;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ChatTypes.DiscoveryResult;
import com.wimoor.ai.chat.ChatTypes.ProviderStatusKind;
import com.wimoor.ai.chat.ChatTypes.ToolDescriptor;
import com.wimoor.ai.chat.DataSources.ChatDataProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聊天只读运行时装配。移植自 {@code src/lib/ai-chat/runtime.ts}。
 * 关键规则：
 * - 仅装配用户在数据源配置中显式启用、且凭证可用的 provider；未启用则不出现在列表中。
 * - Amazon 使用账号下 ACTIVE MCP 凭证身份（erp，permission=read）；
 *   Sellfox 使用 ERP 会话身份并过滤 READ_ONLY 工具；
 *   卖家精灵/西柚复用 Lili 已审核只读策略（sellerSpritePolicy / xiyouResearchReadTools），
 *   不暴露任何写/有状态 research_* 工具。
 * - 每次工具发现与调用前后都做会话 + 工作区复验，切换即抛错。
 */
public final class Runtime {

    private Runtime() {}

    /** 工作区/会话访问校验边界（对应 assertWorkspaceAccess）。 */
    public interface WorkspaceAccessGuard {
        boolean hasAccess(ChatScope scope);
    }

    /** 传输层边界：ERP 侧由各 provider 的 MCP transport 实现。 */
    public interface ToolTransport {
        /** 内部 runtimeName：erp / sellfox / sellersprite / xiyou。 */
        String id();
        List<ToolDescriptor> listTools();
        Object callTool(String name, Map<String, Object> args);
        /** 底层工具是否只读；Runtime 还会按 provider 策略再次判定。 */
        default boolean isReadOnly(ToolDescriptor tool) { return Boolean.FALSE.equals(tool.getReadOnlyHint()) ? false : true; }
    }

    /** transport 工厂：只有显式启用且有可用凭证时才会创建。 */
    public interface TransportFactory {
        ToolTransport create(ChatScope scope) throws Exception;
    }

    /** 会话/工作区变化异常（等价 SyncInputError 401）。 */
    public static final class SessionException extends RuntimeException {
        private final int status;
        public SessionException(String message, int status) { super(message); this.status = status; }
        public int getStatus() { return status; }
    }

    /** 工具不可访问/非只读异常。 */
    public static final class ToolAccessException extends RuntimeException {
        private final String code;
        public ToolAccessException(String code) { super(code); this.code = code; }
        public String getCode() { return code; }
    }

    /** 展示用 provider/tool 引用（describeTool 返回值）。 */
    public static final class ToolRef {
        private final String provider;
        private final String tool;
        public ToolRef(String provider, String tool) { this.provider = provider; this.tool = tool; }
        public String getProvider() { return provider; }
        public String getTool() { return tool; }
    }

    /** 聊天运行时对外契约。 */
    public interface ChatRuntime {
        DiscoveryResult discover();
        List<ChatProviderStatus> providers();
        void assertSession();
        Object call(String name, Map<String, Object> args);
        ToolRef describeTool(String name);
    }

    /** 工具调用边界。 */
    public interface RuntimeTools {
        DiscoveryResult discover();
        Object call(String name, Map<String, Object> args);
    }

    /** 卖家精灵已审核只读工具白名单（9 个，与面板估计值一致）。 */
    public static final Set<String> SELLERSPRITE_READ_TOOLS = Set.of(
            "product_search", "product_detail", "keyword_research", "keyword_rank",
            "market_research", "competitor_analysis", "review_analysis", "traffic_analysis", "sales_trend");

    /** 构建输入：外部依赖全部以接口注入，装配规则在本类真实实现。 */
    public static final class BuildInput {
        ChatScope scope;
        Runnable sessionGuard;
        WorkspaceAccessGuard workspaceGuard;
        List<ChatDataProvider> enabledProviders = new ArrayList<>();
        Map<String, TransportFactory> transportFactories = new LinkedHashMap<>();
        List<ToolDescriptor> extraTools = new ArrayList<>();
        long discoveryTimeoutMs = 20_000L;

        public BuildInput scope(ChatScope scope) { this.scope = scope; return this; }
        public BuildInput sessionGuard(Runnable guard) { this.sessionGuard = guard; return this; }
        public BuildInput workspaceGuard(WorkspaceAccessGuard guard) { this.workspaceGuard = guard; return this; }
        public BuildInput enabledProviders(List<ChatDataProvider> providers) {
            this.enabledProviders = providers == null ? new ArrayList<>() : new ArrayList<>(providers);
            return this;
        }
        public BuildInput transportFactories(Map<String, TransportFactory> factories) {
            this.transportFactories = factories == null ? new LinkedHashMap<>() : new LinkedHashMap<>(factories);
            return this;
        }
        public BuildInput extraTools(List<ToolDescriptor> tools) {
            this.extraTools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
            return this;
        }
        public BuildInput discoveryTimeoutMs(long ms) { this.discoveryTimeoutMs = ms; return this; }
    }

    /** 构建只读运行时：按启用集合装配 provider，失败时暴露 unavailable 原因而不隐藏其他 provider。 */
    public static ChatRuntime buildChatRuntime(BuildInput input) {
        return new Impl(input);
    }

    /** 供编排器读取 provider 状态（首次会触发发现）。 */
    public static List<ChatProviderStatus> chatProviderStatus(ChatRuntime runtime) {
        return runtime.discover().getProviders();
    }

    /** 按内部 runtimeName 前缀翻译展示用 provider/tool。 */
    public static ToolRef describeTool(String name) {
        String n = name == null ? "" : name;
        if (n.startsWith("erp__")) return new ToolRef("amazon", n.substring("erp__".length()));
        if (n.startsWith("sellfox__")) return new ToolRef("sellfox", n.substring("sellfox__".length()));
        if (n.startsWith("sellersprite__")) return new ToolRef("sellersprite", n.substring("sellersprite__".length()));
        if (n.startsWith("xiyou__")) return new ToolRef("xiyou", n.substring("xiyou__".length()));
        return new ToolRef("skills", n);
    }

    /** 去掉 runtimeName 前缀得到原始工具名。 */
    public static String stripPrefix(String name) {
        if (name == null) return "";
        int idx = name.indexOf("__");
        return idx >= 0 ? name.substring(idx + 2) : name;
    }

    /** 卖家精灵只读策略：readOnlyHint 不为 false 且在已审核白名单内。 */
    public static boolean sellerSpritePolicy(ToolDescriptor tool) {
        if (tool == null || Boolean.FALSE.equals(tool.getReadOnlyHint())) return false;
        return SELLERSPRITE_READ_TOOLS.contains(stripPrefix(tool.getName()));
    }

    /** 西柚只读策略：readOnlyHint 不为 false 且在已审核白名单内。 */
    public static boolean xiyouReadPolicy(ToolDescriptor tool) {
        if (tool == null || Boolean.FALSE.equals(tool.getReadOnlyHint())) return false;
        return DataSources.XIYOU_READ_TOOLS.contains(stripPrefix(tool.getName()));
    }

    /** 运行时实现。 */
    private static final class Impl implements ChatRuntime {
        private final BuildInput input;
        private final Map<String, ToolTransport> transports = new LinkedHashMap<>();
        private final List<ChatProviderStatus> initialProviders = new ArrayList<>();
        private final Map<String, ToolTransport> toolTransportByName = new LinkedHashMap<>();
        private final Map<String, ToolDescriptor> availableToolsByName = new LinkedHashMap<>();
        private DiscoveryResult cached;

        Impl(BuildInput input) {
            this.input = input;
            assertSession();
            Set<ChatDataProvider> enabled = new LinkedHashSet<>(input.enabledProviders);
            // 按固定顺序装配：amazon → sellfox → sellersprite → xiyou（与 runtime.ts 一致）。
            for (ChatDataProvider p : ChatDataProvider.values()) {
                if (!enabled.contains(p)) continue;
                String wire = p.wire();
                TransportFactory factory = input.transportFactories.get(wire);
                if (factory == null) {
                    initialProviders.add(unavailable(wire, missingReason(wire)));
                    continue;
                }
                try {
                    ToolTransport raw = factory.create(input.scope);
                    if (raw == null) throw new IllegalStateException("NULL_TRANSPORT");
                    transports.put(wire, new SessionCheckingTransport(raw, this::assertSession));
                    initialProviders.add(new ChatProviderStatus(wire, ProviderStatusKind.CONNECTED, 0, null));
                } catch (Exception ex) {
                    initialProviders.add(unavailable(wire, failureReason(wire)));
                }
            }
        }

        private static String missingReason(String wire) {
            switch (wire) {
                case "amazon": return "AMAZON_MCP_CREDENTIAL_UNAVAILABLE";
                case "sellfox": return "SELLFOX_ERP_SESSION_UNAVAILABLE";
                case "sellersprite": return "SELLERSPRITE_MCP_NOT_CONFIGURED";
                case "xiyou": return "XIYOU_MCP_NOT_CONFIGURED";
                default: return "UNAVAILABLE";
            }
        }

        private static String failureReason(String wire) {
            if ("sellersprite".equals(wire)) return "SELLERSPRITE_MCP_UNAVAILABLE";
            if ("xiyou".equals(wire)) return "XIYOU_MCP_UNAVAILABLE";
            return missingReason(wire);
        }

        private static ChatProviderStatus unavailable(String provider, String reason) {
            return new ChatProviderStatus(provider, ProviderStatusKind.UNAVAILABLE, 0, reason);
        }

        @Override
        public void assertSession() {
            if (input.sessionGuard != null) input.sessionGuard.run();
            if (input.workspaceGuard != null && !input.workspaceGuard.hasAccess(input.scope)) {
                throw new SessionException("工作区或登录会话已变化，本次分析已停止。", 401);
            }
        }

        @Override
        public synchronized DiscoveryResult discover() {
            if (cached != null) return cached;
            List<ToolDescriptor> tools = new ArrayList<>();
            List<ChatProviderStatus> providers = new ArrayList<>();
            for (Map.Entry<String, ToolTransport> entry : transports.entrySet()) {
                String wire = entry.getKey();
                ToolTransport transport = entry.getValue();
                int availableCount = 0;
                try {
                    List<ToolDescriptor> rawTools = transport.listTools();
                    if (rawTools == null) rawTools = Collections.emptyList();
                    for (ToolDescriptor raw : rawTools) {
                        ToolDescriptor tool = copyTool(raw);
                        tool.setProvider(wire);
                        boolean readOnly = readOnlyFor(wire, tool);
                        tool.setReadOnlyHint(readOnly);
                        tool.setPermission("amazon".equals(wire) ? "read" : "approval_required");
                        if (!readOnly) {
                            tool.setAvailability("blocked");
                        } else {
                            availableCount++;
                            availableToolsByName.put(tool.getName(), tool);
                            toolTransportByName.put(tool.getName(), transport);
                        }
                        tools.add(tool);
                    }
                    providers.add(new ChatProviderStatus(wire, ProviderStatusKind.CONNECTED, availableCount, null));
                } catch (RuntimeException ex) {
                    providers.add(unavailable(wire, failureReason(wire)));
                }
            }
            // 未成功装配的 provider 保持 originally unavailable 状态。
            for (ChatProviderStatus initial : initialProviders) {
                boolean present = false;
                for (ChatProviderStatus p : providers) if (p.getProvider().equals(initial.getProvider())) { present = true; break; }
                if (!present) providers.add(initial);
            }
            // 只读 Skill 工具（三件套）与匿名 public research 工具由上层经 extraTools 注入。
            for (ToolDescriptor extra : input.extraTools) {
                ToolDescriptor tool = copyTool(extra);
                if (tool.getProvider() == null || tool.getProvider().isEmpty()) tool.setProvider("skills");
                if (tool.getPermission() == null) tool.setPermission("read");
                if (tool.getAvailability() == null) tool.setAvailability("available");
                tools.add(tool);
                if ("available".equals(tool.getAvailability())) availableToolsByName.put(tool.getName(), tool);
            }
            cached = new DiscoveryResult(tools, providers);
            return cached;
        }

        /** 集中只读策略：sellersprite/xiyou 走已审核白名单，其余走 transport 底层判定。 */
        private boolean readOnlyFor(String wire, ToolDescriptor tool) {
            if ("sellersprite".equals(wire)) return sellerSpritePolicy(tool);
            if ("xiyou".equals(wire)) return xiyouReadPolicy(tool);
            return Boolean.FALSE.equals(tool.getReadOnlyHint()) ? false : true;
        }

        @Override
        public Object call(String name, Map<String, Object> args) {
            assertSession();
            discover();
            ToolDescriptor tool = availableToolsByName.get(name);
            if (tool == null) throw new ToolAccessException("TOOL_NOT_ALLOWED");
            Object result;
            if (tool.getDispatch() != null) {
                result = tool.getDispatch().apply(args == null ? new LinkedHashMap<>() : args);
            } else {
                ToolTransport transport = toolTransportByName.get(name);
                if (transport == null) throw new ToolAccessException("TOOL_NOT_ALLOWED");
                result = transport.callTool(name, args == null ? new LinkedHashMap<>() : args);
            }
            assertSession();
            return result;
        }

        @Override
        public List<ChatProviderStatus> providers() {
            return cached == null ? Collections.unmodifiableList(initialProviders) : cached.getProviders();
        }

        @Override
        public ToolRef describeTool(String name) { return Runtime.describeTool(name); }

        private static ToolDescriptor copyTool(ToolDescriptor src) {
            ToolDescriptor d = new ToolDescriptor();
            d.setName(src.getName()); d.setProvider(src.getProvider()); d.setDescription(src.getDescription());
            d.setInputSchema(src.getInputSchema()); d.setAvailability(src.getAvailability());
            d.setPermission(src.getPermission()); d.setReadOnlyHint(src.getReadOnlyHint());
            d.setDispatch(src.getDispatch());
            return d;
        }
    }

    /** 在 listTools/callTool 前后复验会话与工作区。 */
    private static final class SessionCheckingTransport implements ToolTransport {
        private final ToolTransport delegate;
        private final Runnable assertSession;
        SessionCheckingTransport(ToolTransport delegate, Runnable assertSession) {
            this.delegate = delegate; this.assertSession = assertSession;
        }
        @Override public String id() { return delegate.id(); }
        @Override public List<ToolDescriptor> listTools() {
            assertSession.run();
            List<ToolDescriptor> tools = delegate.listTools();
            assertSession.run();
            return tools;
        }
        @Override public Object callTool(String name, Map<String, Object> args) {
            assertSession.run();
            Object result = delegate.callTool(name, args);
            assertSession.run();
            return result;
        }
        @Override public boolean isReadOnly(ToolDescriptor tool) { return delegate.isReadOnly(tool); }
    }
}

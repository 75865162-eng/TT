package com.wimoor.ai.lili;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import com.wimoor.ai.lili.Ports.McpTransport;
import com.wimoor.ai.lili.Ports.ToolExecutor;
import com.wimoor.ai.lili.Ports.ToolNotDispatchedException;

/**
 * 移植自 src/lib/lili/runtime-tools.ts：请求作用域的工具注册中心。
 * 只暴露实际适配器的定义；shipped 业务目录不会伪装成可执行工具。
 * 注册项校验、权限/可用性推导、Schema 比对、凭证拦截与分发前的二次发现均为真实逻辑。
 */
public final class RuntimeTools {

    public enum ProviderId {
        ERP("erp"), SELLFOX("sellfox"), SELLERSRITE("sellersprite"), XIYOU("xiyou");
        private final String wire;
        ProviderId(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public enum Permission { READ("read"), APPROVAL_REQUIRED("approval_required");
        private final String wire; Permission(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public enum Availability { AVAILABLE("available"), BLOCKED("blocked");
        private final String wire; Availability(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** RuntimeTool = ToolDefinition & { provider, remoteName, permission, availability }。 */
    public static final class RuntimeTool {
        public final String name, description, provider, remoteName;
        public final Map<String, Object> inputSchema, annotations;
        public final Permission permission;
        public final Availability availability;
        RuntimeTool(String name, String description, Map<String, Object> inputSchema, Map<String, Object> annotations,
                    String provider, String remoteName, Permission permission, Availability availability) {
            this.name = name; this.description = description; this.inputSchema = inputSchema; this.annotations = annotations;
            this.provider = provider; this.remoteName = remoteName; this.permission = permission; this.availability = availability;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = Ports.jsonObject("name", name, "description", description,
                "inputSchema", JsonSupport.copy(inputSchema), "provider", provider, "remoteName", remoteName,
                "permission", permission.wire(), "availability", availability.wire());
            if (annotations != null) m.put("annotations", JsonSupport.copy(annotations));
            return m;
        }
    }

    public static final class ProviderStatus {
        public final ProviderId provider;
        public final String status, reason;
        public final int toolCount;
        ProviderStatus(ProviderId provider, String status, int toolCount, String reason) {
            this.provider = provider; this.status = status; this.toolCount = toolCount; this.reason = reason;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = Ports.jsonObject("provider", provider.wire(), "status", status, "toolCount", toolCount);
            if (reason != null) m.put("reason", reason);
            return m;
        }
    }

    public static final class RuntimeProvider {
        public final ProviderId id;
        public final McpTransport transport;
        public final String reason;
        public final Predicate<Map<String, Object>> isReadOnly;
        public RuntimeProvider(ProviderId id, McpTransport transport, String reason, Predicate<Map<String, Object>> isReadOnly) {
            this.id = id; this.transport = transport; this.reason = reason; this.isReadOnly = isReadOnly;
        }
    }

    /** ExtraSkillTool：调用方显式选择加入的只读 Skill 工具及其分发器。 */
    public static final class ExtraSkillTool {
        public final Ports.ToolDefinition definition;
        public final ToolExecutor dispatch;
        public ExtraSkillTool(Ports.ToolDefinition definition, ToolExecutor dispatch) {
            this.definition = definition; this.dispatch = dispatch;
        }
    }

    public static class RuntimeToolError extends ToolNotDispatchedException {
        public RuntimeToolError(String code) { super(code); }
    }

    public static final Set<String> XIYOU_RESEARCH_READ_TOOLS = Set.of(
        "get_asin_info", "get_asin_traffic", "get_asin_keywords", "get_asin_traffic_trends",
        "get_asin_keyword_traffic_trends", "get_asin_bsr_trends", "get_asin_variations");

    private static final Set<String> SKILL_NAMES;
    static {
        Set<String> names = new LinkedHashSet<>();
        names.add("meta_search_skills"); names.add("meta_load_skill"); names.add("meta_list_workflows");
        SkillMeta.SKILL_META_TOOLS.forEach(t -> names.add(t.name));
        SKILL_NAMES = Set.copyOf(names);
    }

    public static String runtimeName(ProviderId provider, String name) { return provider.wire() + "__" + name; }

    public static final class Options {
        public final long discoveryTimeoutMs;
        public final List<ExtraSkillTool> extraSkillTools;
        public Options(long discoveryTimeoutMs, List<ExtraSkillTool> extraSkillTools) {
            this.discoveryTimeoutMs = discoveryTimeoutMs; this.extraSkillTools = extraSkillTools;
        }
        public Options() { this(30_000L, new ArrayList<>()); }
    }

    public static final class DiscoveryResult {
        public final List<RuntimeTool> tools;
        public final List<ProviderStatus> providers;
        public final String discoveredAt;
        DiscoveryResult(List<RuntimeTool> tools, List<ProviderStatus> providers) {
            this.tools = tools; this.providers = providers; this.discoveredAt = Instant.now().toString();
        }
        public Map<String, Object> toMap() {
            List<Map<String, Object>> t = new ArrayList<>();
            tools.forEach(x -> t.add(x.toMap()));
            List<Map<String, Object>> p = new ArrayList<>();
            providers.forEach(x -> p.add(x.toMap()));
            return Ports.jsonObject("tools", t, "providers", p, "discoveredAt", discoveredAt);
        }
    }

    private final List<RuntimeProvider> providers;
    private final Options options;

    private RuntimeTools(List<RuntimeProvider> providers, Options options) {
        this.providers = providers; this.options = options;
    }

    public static RuntimeTools createRuntimeTools(List<RuntimeProvider> providers) {
        return createRuntimeTools(providers, new Options());
    }

    public static RuntimeTools createRuntimeTools(List<RuntimeProvider> providers, Options options) {
        return new RuntimeTools(providers == null ? new ArrayList<>() : providers, options == null ? new Options() : options);
    }

    /** 带超时的 registry 拉取，对应 Promise.race + setTimeout(REGISTRY_TIMEOUT)。 */
    private List<Map<String, Object>> registryFor(RuntimeProvider provider) throws Exception {
        CompletableFuture<List<Map<String, Object>>> future = CompletableFuture.supplyAsync(() -> {
            try { return provider.transport.listTools(); }
            catch (Exception e) { throw new IllegalStateException("REGISTRY_LIST_FAILED", e); }
        });
        try { return future.get(options.discoveryTimeoutMs, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { future.cancel(true); throw new IllegalStateException("REGISTRY_TIMEOUT", e); }
        catch (ExecutionException e) { throw new IllegalStateException("REGISTRY_LIST_FAILED", e.getCause()); }
    }

    public DiscoveryResult discover(String selected) {
        List<RuntimeTool> tools = new ArrayList<>();
        for (Catalog.CatalogTool tool : Catalog.listTools()) {
            if (!SKILL_NAMES.contains(tool.name)) continue;
            String description = "meta_list_workflows".equals(tool.name)
                ? "List shipped workflow templates only. Does not list saved workflows or schedules."
                : tool.description;
            tools.add(new RuntimeTool(tool.name, description, JsonSupport.copy(tool.inputSchema.raw),
                Ports.annotations(true), "skills", tool.name, Permission.READ, Availability.AVAILABLE));
        }
        for (Ports.ToolDefinition tool : SkillMeta.SKILL_META_TOOLS)
            tools.add(new RuntimeTool(tool.name, tool.description, JsonSupport.copy(tool.inputSchema),
                tool.annotations, "skills", tool.name, Permission.READ, Availability.AVAILABLE));
        for (ExtraSkillTool extra : options.extraSkillTools) {
            if (extra.definition.annotations != null && Boolean.FALSE.equals(extra.definition.annotations.get("readOnlyHint"))) continue;
            if (SKILL_NAMES.contains(extra.definition.name)) continue;
            tools.add(new RuntimeTool(extra.definition.name, extra.definition.description,
                JsonSupport.copy(extra.definition.inputSchema), extra.definition.annotations,
                "skills", extra.definition.name, Permission.READ, Availability.AVAILABLE));
        }
        List<ProviderStatus> statuses = new ArrayList<>();
        for (RuntimeProvider provider : providers) {
            if (selected != null && !selected.equals(provider.id.wire())) continue;
            if (provider.transport == null) {
                statuses.add(new ProviderStatus(provider.id, "not_connected", 0,
                    provider.reason == null ? "PROVIDER_NOT_CONFIGURED" : provider.reason));
                continue;
            }
            try {
                List<Map<String, Object>> registry = registryFor(provider);
                SchemaValidator.rejectCredentials(registry);
                Set<String> unique = new HashSet<>();
                if (registry.size() > 1000) throw new IllegalStateException("INVALID_REGISTRY");
                for (Map<String, Object> tool : registry) unique.add(JsonSupport.str(tool.get("name")));
                if (unique.size() != registry.size() || unique.contains(null)) throw new IllegalStateException("INVALID_REGISTRY");
                List<RuntimeTool> entries = new ArrayList<>();
                for (Map<String, Object> tool : registry) {
                    String name = JsonSupport.str(tool.get("name"));
                    Map<String, Object> schema = JsonSupport.obj(tool.get("inputSchema"));
                    if (name == null || !name.matches("[A-Za-z0-9_.-]{1,100}") || schema == null || !"object".equals(schema.get("type")))
                        throw new IllegalStateException("INVALID_REGISTRY");
                    boolean readOnly = provider.isReadOnly != null && provider.isReadOnly.test(tool);
                    Permission permission = readOnly && provider.id == ProviderId.ERP ? Permission.READ : Permission.APPROVAL_REQUIRED;
                    Availability availability = readOnly ? Availability.AVAILABLE : Availability.BLOCKED;
                    entries.add(new RuntimeTool(runtimeName(provider.id, name), JsonSupport.str(tool.get("description")),
                        JsonSupport.copy(schema), JsonSupport.obj(tool.get("annotations")),
                        provider.id.wire(), name, permission, availability));
                }
                statuses.add(new ProviderStatus(provider.id, "connected", entries.size(), null));
                tools.addAll(entries);
            } catch (Exception e) {
                statuses.add(new ProviderStatus(provider.id, "unavailable", 0, "REGISTRY_UNAVAILABLE_OR_ACCESS_DENIED"));
            }
        }
        return new DiscoveryResult(tools, statuses);
    }

    public DiscoveryResult discover() { return discover(null); }

    /** call(name, args)：按 provider 前缀路由，分发前重新发现并校验 Schema/凭证。 */
    public Object call(String name, Map<String, Object> args) { return call(name, args, null); }

    public Object call(String name, Map<String, Object> args, Map<String, Object> expectedSchema) {
        boolean isExtra = options.extraSkillTools.stream().anyMatch(e -> e.definition.name.equals(name));
        String selected = SKILL_NAMES.contains(name) || isExtra ? "skills"
            : providers.stream().filter(p -> name != null && name.startsWith(p.id.wire() + "__"))
                .map(p -> p.id.wire()).findFirst().orElse(null);
        if (selected == null) throw new RuntimeToolError("RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN");
        RuntimeTool tool = null;
        for (RuntimeTool t : discover(selected).tools) if (t.name.equals(name)) { tool = t; break; }
        if (tool == null || tool.availability != Availability.AVAILABLE)
            throw new RuntimeToolError("RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN");
        if (expectedSchema != null && !JsonSupport.stableStringify(tool.inputSchema).equals(JsonSupport.stableStringify(expectedSchema)))
            throw new RuntimeToolError("APPROVED_SCHEMA_CHANGED");
        SchemaValidator.rejectCredentials(args);
        try { SchemaValidator.validate(tool.inputSchema, args); }
        catch (RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            throw new RuntimeToolError(message.matches("SCHEMA_.*UNSUPPORTED") ? "UNSUPPORTED_TOOL_SCHEMA" : "INVALID_TOOL_ARGUMENTS");
        }
        Object result;
        if ("skills".equals(tool.provider)) {
            boolean meta = SkillMeta.SKILL_META_TOOLS.stream().anyMatch(t -> t.name.equals(name));
            if (meta) return SkillMeta.callSkillMeta(name, args, false);
            ExtraSkillTool extra = options.extraSkillTools.stream().filter(e -> e.definition.name.equals(name)).findFirst().orElse(null);
            if (extra != null) {
                try { return extra.dispatch.execute(name, args); }
                catch (Exception e) { throw new RuntimeToolError("EXTRA_SKILL_TOOL_FAILED"); }
            }
            ToolExecution.ToolResult response = ToolExecution.runCatalogTool(name, args);
            if (response.status != 200 || !Boolean.TRUE.equals(response.body.get("ok")))
                throw new RuntimeToolError("INVALID_TOOL_ARGUMENTS");
            result = response.body;
        } else {
            final RuntimeTool chosen = tool;
            RuntimeProvider provider = providers.stream().filter(p -> p.id.wire().equals(chosen.provider)).findFirst().orElse(null);
            if (provider == null || provider.transport == null) throw new RuntimeToolError("RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN");
            try { result = provider.transport.callTool(tool.remoteName, args, tool.inputSchema); }
            catch (Exception e) {
                if (e instanceof ToolNotDispatchedException) throw (ToolNotDispatchedException) e;
                throw new RuntimeToolError("RUNTIME_TOOL_UNAVAILABLE_OR_FORBIDDEN");
            }
        }
        SchemaValidator.rejectCredentials(result);
        return result;
    }

    /** Transport 视图：listTools 只返回 available，callTool 复用 call。 */
    public McpTransport transport() {
        return new McpTransport() {
            @Override public List<Map<String, Object>> listTools() {
                List<Map<String, Object>> out = new ArrayList<>();
                for (RuntimeTool t : discover().tools) if (t.availability == Availability.AVAILABLE) out.add(t.toMap());
                return out;
            }
            @Override public Object callTool(String name, Map<String, Object> args, Map<String, Object> schema) {
                return call(name, args, schema);
            }
        };
    }
}

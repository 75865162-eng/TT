package com.wimoor.ai.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AI 聊天最小公共合同。移植自 {@code src/lib/ai-chat/types.ts}。
 * 所有结构化字段都在服务端生成，不信任浏览器：身份三元组、工具证据、provider 状态、
 * 技能引用、最终回答的 coverage/skipped 均由此处 DTO 承载。
 */
public final class ChatTypes {

    private ChatTypes() {}

    /** 等价 CHAT_LIMITS，全部为服务端硬上限。 */
    public static final int MAX_MESSAGE_CHARS = 4000;
    public static final int MAX_TURNS = 6;
    public static final int MAX_TOOL_CALLS = 6;
    public static final int MAX_MODEL_CALLS = 8;
    public static final int MAX_TOOL_RESULT_CHARS = 24000;
    public static final int MAX_CONTEXT_CHARS = 120000;
    public static final int REQUEST_TIMEOUT_MS = 180_000;
    public static final int MAX_CONVERSATIONS = 200;
    public static final int MAX_MESSAGES_PER_CONVERSATION = 400;

    /** 身份三元组：唯一可信来源是服务端会话闭包，模型参数永不参与。 */
    public static final class ChatScope {
        private final String organizationId;
        private final String workspaceId;
        private final String userId;
        public ChatScope(String organizationId, String workspaceId, String userId) {
            this.organizationId = organizationId; this.workspaceId = workspaceId; this.userId = userId;
        }
        public String getOrganizationId() { return organizationId; }
        public String getWorkspaceId() { return workspaceId; }
        public String getUserId() { return userId; }
        public String key() { return organizationId + "\u0000" + workspaceId + "\u0000" + userId; }
        @Override public String toString() { return organizationId + ":" + workspaceId + ":" + userId; }
    }

    public enum EvidenceStatus {
        OK("ok"), ERROR("error");
        private final String wire;
        EvidenceStatus(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** 工具证据（ChatToolEvidence）：每次工具调用落一条，含运行序号与耗时。 */
    public static final class ChatToolEvidence {
        private int seq;
        private String provider;
        private String tool;
        private String argsSummary;
        private String resultSource;
        private String resultHash;
        private Integer rowCount;
        private EvidenceStatus status = EvidenceStatus.OK;
        private String errorCode;
        private long durationMs;
        private String scopeLabel;
        public int getSeq() { return seq; }
        public void setSeq(int seq) { this.seq = seq; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        public String getArgsSummary() { return argsSummary; }
        public void setArgsSummary(String argsSummary) { this.argsSummary = argsSummary; }
        public String getResultSource() { return resultSource; }
        public void setResultSource(String resultSource) { this.resultSource = resultSource; }
        public String getResultHash() { return resultHash; }
        public void setResultHash(String resultHash) { this.resultHash = resultHash; }
        public Integer getRowCount() { return rowCount; }
        public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
        public EvidenceStatus getStatus() { return status; }
        public void setStatus(EvidenceStatus status) { this.status = status; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public String getScopeLabel() { return scopeLabel; }
        public void setScopeLabel(String scopeLabel) { this.scopeLabel = scopeLabel; }
    }

    public enum SkillAvailability {
        AVAILABLE("available"), MISSING_BODY("missing_body"), FORBIDDEN("forbidden"), NOT_FOUND("not_found");
        private final String wire;
        SkillAvailability(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public static final class ChatSkillRef {
        private String slug;
        private String hash;
        private String name;
        private String summary;
        private SkillAvailability availability = SkillAvailability.AVAILABLE;
        public ChatSkillRef() {}
        public ChatSkillRef(String slug, String hash, String name, String summary, SkillAvailability availability) {
            this.slug = slug; this.hash = hash; this.name = name; this.summary = summary; this.availability = availability;
        }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getHash() { return hash; }
        public void setHash(String hash) { this.hash = hash; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public SkillAvailability getAvailability() { return availability; }
        public void setAvailability(SkillAvailability availability) { this.availability = availability; }
    }

    public enum ProviderStatusKind {
        CONNECTED("connected"), NOT_CONNECTED("not_connected"), UNAVAILABLE("unavailable");
        private final String wire;
        ProviderStatusKind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public static final class ChatProviderStatus {
        private String provider;
        private ProviderStatusKind status;
        private int toolCount;
        private String reason;
        public ChatProviderStatus() {}
        public ChatProviderStatus(String provider, ProviderStatusKind status, int toolCount, String reason) {
            this.provider = provider; this.status = status; this.toolCount = toolCount; this.reason = reason;
        }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public ProviderStatusKind getStatus() { return status; }
        public void setStatus(ProviderStatusKind status) { this.status = status; }
        public int getToolCount() { return toolCount; }
        public void setToolCount(int toolCount) { this.toolCount = toolCount; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** 最终回答（ChatAnswer）：coverage 与 skipped 明确列出缺口与被拒动作。 */
    public static final class ChatAnswer {
        private String text = "";
        private ChatSkillRef skill;
        private List<ChatToolEvidence> tools = new ArrayList<>();
        private List<ChatProviderStatus> providers = new ArrayList<>();
        private List<String> coverage = new ArrayList<>();
        private List<String> skipped = new ArrayList<>();
        private String model = "";
        private String runId = "";
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public ChatSkillRef getSkill() { return skill; }
        public void setSkill(ChatSkillRef skill) { this.skill = skill; }
        public List<ChatToolEvidence> getTools() { return tools; }
        public void setTools(List<ChatToolEvidence> tools) { this.tools = tools; }
        public List<ChatProviderStatus> getProviders() { return providers; }
        public void setProviders(List<ChatProviderStatus> providers) { this.providers = providers; }
        public List<String> getCoverage() { return coverage; }
        public void setCoverage(List<String> coverage) { this.coverage = coverage; }
        public List<String> getSkipped() { return skipped; }
        public void setSkipped(List<String> skipped) { this.skipped = skipped; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
    }

    /** 发送消息输入（SendMessageInput）：身份字段仍在请求体，但服务端必须以会话复验覆盖。 */
    public static final class SendMessageInput {
        private final ChatScope scope;
        private String conversationId;
        private String content;
        private String clientKey;
        private String skillSlug;
        private String idempotencyKey;
        public SendMessageInput(ChatScope scope) { this.scope = scope; }
        public ChatScope getScope() { return scope; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getClientKey() { return clientKey; }
        public void setClientKey(String clientKey) { this.clientKey = clientKey; }
        public String getSkillSlug() { return skillSlug; }
        public void setSkillSlug(String skillSlug) { this.skillSlug = skillSlug; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    }

    /** 运行时工具描述符；dispatch 由 scope 闭包绑定身份，等价 ExtraSkillTool/ToolDefinition。 */
    public static final class ToolDescriptor {
        private String name;
        private String provider = "skills";
        private String description = "";
        private Map<String, Object> inputSchema;
        private String availability = "available";
        private String permission = "read";
        private Boolean readOnlyHint = Boolean.TRUE;
        private Function<Map<String, Object>, Object> dispatch;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getInputSchema() { return inputSchema; }
        public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
        public String getAvailability() { return availability; }
        public void setAvailability(String availability) { this.availability = availability; }
        public String getPermission() { return permission; }
        public void setPermission(String permission) { this.permission = permission; }
        public Boolean getReadOnlyHint() { return readOnlyHint; }
        public void setReadOnlyHint(Boolean readOnlyHint) { this.readOnlyHint = readOnlyHint; }
        public Function<Map<String, Object>, Object> getDispatch() { return dispatch; }
        public void setDispatch(Function<Map<String, Object>, Object> dispatch) { this.dispatch = dispatch; }
    }

    /** 工具调用结果：value 为原始数据，source/rowCount 供 boundResult 提取。 */
    public static final class ToolResult {
        private final Object value;
        private final String source;
        private final Integer rowCount;
        public ToolResult(Object value, String source, Integer rowCount) {
            this.value = value; this.source = source; this.rowCount = rowCount;
        }
        public Object getValue() { return value; }
        public String getSource() { return source; }
        public Integer getRowCount() { return rowCount; }
    }

    /** 工具发现结果：tools + provider 状态（编排器据此生成可用工具清单）。 */
    public static final class DiscoveryResult {
        private final List<ToolDescriptor> tools;
        private final List<ChatProviderStatus> providers;
        public DiscoveryResult(List<ToolDescriptor> tools, List<ChatProviderStatus> providers) {
            this.tools = tools; this.providers = providers;
        }
        public List<ToolDescriptor> getTools() { return tools; }
        public List<ChatProviderStatus> getProviders() { return providers; }
    }
}

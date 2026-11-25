package com.wimoor.ai.chat;

import com.wimoor.ai.chat.ChatTypes.ChatAnswer;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ChatTypes.ChatSkillRef;
import com.wimoor.ai.chat.ChatTypes.ChatToolEvidence;
import com.wimoor.ai.chat.ChatTypes.EvidenceStatus;
import com.wimoor.ai.chat.ChatTypes.SkillAvailability;
import com.wimoor.ai.chat.ConversationRepository.CancelResult;
import com.wimoor.ai.chat.ConversationRepository.ChatAccessError;
import com.wimoor.ai.chat.ConversationRepository.Conversation;
import com.wimoor.ai.chat.ConversationRepository.ConversationDetail;
import com.wimoor.ai.chat.ConversationRepository.DeleteResult;
import com.wimoor.ai.chat.ConversationRepository.FinishResult;
import com.wimoor.ai.chat.ConversationRepository.Message;
import com.wimoor.ai.chat.ConversationRepository.PreparedMessage;
import com.wimoor.ai.chat.ConversationRepository.Run;
import com.wimoor.ai.chat.ConversationRepository.RunStatus;
import com.wimoor.ai.chat.ConversationRepository.ToolCallRecord;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 对话仓储的内存实现（对应 repository.ts 的 prisma 访问层）。
 * 无任何空方法：完整实现三元组隔离、会话 CRUD、revision 乐观锁、clientKey/requestKey 幂等、
 * run 状态机、工具证据记录、回答装载、孤儿消息清理。注入 Clock 与 id 发生器保证测试确定性。
 */
public final class InMemoryConversationRepository implements ConversationRepository {

    private final Clock clock;
    private final Supplier<String> idSupplier;
    private final Map<String, StoredConversation> conversations = new LinkedHashMap<>();
    private final Map<String, StoredMessage> messages = new LinkedHashMap<>();
    private final Map<String, StoredRun> runs = new LinkedHashMap<>();
    private final Map<String, String> messageByClientKey = new LinkedHashMap<>();
    private final Map<String, String> runByRequestKey = new LinkedHashMap<>();

    public InMemoryConversationRepository() {
        this(Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }
    public InMemoryConversationRepository(Clock clock, Supplier<String> idSupplier) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idSupplier = idSupplier == null ? () -> UUID.randomUUID().toString() : idSupplier;
    }

    private static final class StoredConversation {
        String id; String scopeKey; String title; String status = "ACTIVE";
        int revision; Instant createdAt; Instant updatedAt;
    }
    private static final class StoredMessage {
        String id; String scopeKey; String conversationId; String role; String content;
        String clientKey; String skillSlug; String skillHash; String status; Instant createdAt;
    }
    private static final class StoredRun {
        String id; String scopeKey; String conversationId; String messageId; String requestKey;
        String status = "RUNNING"; String skillSlug; String skillHash; String model;
        String answerPreview; String errorCode; Instant createdAt; Instant finishedAt;
        List<StoredToolCall> toolCalls = new ArrayList<>();
    }
    private static final class StoredToolCall {
        int seq; String provider; String tool; String argsSummary; String resultSource;
        String resultHash; Integer rowCount; String status; String errorCode; long durationMs;
    }

    private Instant now() { return clock.instant(); }
    private String newId() { return idSupplier.get(); }

    private StoredConversation ownedConversation(ChatScope scope, String conversationId) {
        if (conversationId == null || conversationId.isEmpty() || conversationId.length() > 200) {
            throw new ChatAccessError("会话无效。", 400);
        }
        StoredConversation c = conversations.get(conversationId);
        if (c == null || !c.scopeKey.equals(scope.key()) || !"ACTIVE".equals(c.status)) {
            throw new ChatAccessError("会话不存在或无权访问。", 404);
        }
        return c;
    }

    private StoredRun ownedRun(ChatScope scope, String runId) {
        StoredRun r = runs.get(runId == null ? "" : runId);
        if (r == null || !r.scopeKey.equals(scope.key())) throw new ChatAccessError("运行记录不存在。", 404);
        return r;
    }

    private static Conversation toDto(StoredConversation c) {
        return new Conversation(c.id, c.title, c.status, c.revision, c.updatedAt, c.createdAt);
    }

    @Override
    public List<Conversation> listConversations(ChatScope scope) {
        List<StoredConversation> rows = new ArrayList<>();
        for (StoredConversation c : conversations.values()) {
            if (c.scopeKey.equals(scope.key()) && "ACTIVE".equals(c.status)) rows.add(c);
        }
        rows.sort(Comparator.comparing((StoredConversation c) -> c.updatedAt).reversed());
        if (rows.size() > ChatTypes.MAX_CONVERSATIONS) rows = rows.subList(0, ChatTypes.MAX_CONVERSATIONS);
        List<Conversation> out = new ArrayList<>();
        for (StoredConversation c : rows) out.add(toDto(c));
        return out;
    }

    @Override
    public Conversation createConversation(ChatScope scope, String title) {
        String clean = title == null ? "" : title;
        if (clean.length() > 120) clean = clean.substring(0, 120);
        if (clean.isEmpty()) clean = "新对话";
        StoredConversation c = new StoredConversation();
        c.id = newId(); c.scopeKey = scope.key(); c.title = clean;
        c.createdAt = now(); c.updatedAt = c.createdAt; c.revision = 0;
        conversations.put(c.id, c);
        return toDto(c);
    }

    @Override
    public ConversationDetail getConversation(ChatScope scope, String conversationId) {
        StoredConversation c = ownedConversation(scope, conversationId);
        List<Message> msgs = new ArrayList<>();
        List<StoredMessage> rows = new ArrayList<>();
        for (StoredMessage m : messages.values()) {
            if (m.scopeKey.equals(scope.key()) && m.conversationId.equals(conversationId)
                    && !"DELETED".equals(m.status)) rows.add(m);
        }
        rows.sort(Comparator.comparing(m -> m.createdAt));
        if (rows.size() > ChatTypes.MAX_MESSAGES_PER_CONVERSATION) {
            rows = rows.subList(0, ChatTypes.MAX_MESSAGES_PER_CONVERSATION);
        }
        for (StoredMessage m : rows) {
            msgs.add(new Message(m.id, m.conversationId, m.role, m.content, m.clientKey,
                    m.skillSlug, m.skillHash, m.status, m.createdAt));
        }
        return new ConversationDetail(toDto(c), msgs);
    }

    @Override
    public Conversation renameConversation(ChatScope scope, String conversationId, String title, int expectedRevision) {
        StoredConversation c = ownedConversation(scope, conversationId);
        if (c.revision != expectedRevision) throw new ChatAccessError("会话已变化，请刷新后重试。", 409);
        String clean = title == null ? "" : title.trim();
        if (clean.length() > 120) clean = clean.substring(0, 120);
        if (clean.isEmpty()) throw new ChatAccessError("标题不能为空。", 400);
        c.title = clean; c.revision = expectedRevision + 1; c.updatedAt = now();
        return toDto(c);
    }

    @Override
    public DeleteResult deleteConversation(ChatScope scope, String conversationId) {
        ownedConversation(scope, conversationId);
        StoredConversation c = conversations.get(conversationId);
        c.status = "DELETED"; c.updatedAt = now();
        return new DeleteResult(conversationId, true);
    }

    @Override
    public PreparedMessage prepareUserMessage(ChatScope scope, String conversationId, String content,
                                              String clientKey, String idempotencyKey,
                                              String skillSlug, String skillHash) {
        ownedConversation(scope, conversationId);
        String requestKey = ConversationRepository.requestKey(scope, conversationId, idempotencyKey);
        String clientMapKey = conversationId + "\u0000" + clientKey;
        String existingMsgId = messageByClientKey.get(clientMapKey);
        if (existingMsgId != null) {
            StoredRun latest = null;
            for (StoredRun r : runs.values()) {
                if (r.scopeKey.equals(scope.key()) && r.messageId.equals(existingMsgId)
                        && (latest == null || r.createdAt.isAfter(latest.createdAt))) latest = r;
            }
            return new PreparedMessage(existingMsgId, requestKey, true,
                    latest == null ? null : loadAnswer(scope, latest.id));
        }
        String existingRunId = runByRequestKey.get(requestKey);
        if (existingRunId != null) {
            return new PreparedMessage("", requestKey, true, loadAnswer(scope, existingRunId));
        }
        StoredMessage m = new StoredMessage();
        m.id = newId(); m.scopeKey = scope.key(); m.conversationId = conversationId;
        m.role = "user"; m.content = content; m.clientKey = clientKey;
        m.skillSlug = skillSlug; m.skillHash = skillHash; m.status = "PENDING"; m.createdAt = now();
        messages.put(m.id, m);
        messageByClientKey.put(clientMapKey, m.id);
        return new PreparedMessage(m.id, requestKey, false, null);
    }

    @Override
    public Run createRun(ChatScope scope, String conversationId, String messageId, String requestKey,
                         String skillSlug, String skillHash) {
        StoredRun r = new StoredRun();
        r.id = newId(); r.scopeKey = scope.key(); r.conversationId = conversationId;
        r.messageId = messageId; r.requestKey = requestKey; r.skillSlug = skillSlug; r.skillHash = skillHash;
        r.createdAt = now();
        runs.put(r.id, r);
        runByRequestKey.putIfAbsent(requestKey, r.id);
        return new Run(r.id, r.status);
    }

    @Override
    public void markMessageComplete(ChatScope scope, String messageId) {
        StoredMessage m = messages.get(messageId == null ? "" : messageId);
        if (m != null && m.scopeKey.equals(scope.key())) m.status = "COMPLETE";
    }

    @Override
    public void finishRun(ChatScope scope, String runId, FinishResult result) {
        StoredRun r = ownedRun(scope, runId);
        r.status = result.getStatus();
        r.model = result.getModel();
        if (result.getSkillSlug() != null) r.skillSlug = result.getSkillSlug();
        if (result.getSkillHash() != null) r.skillHash = result.getSkillHash();
        r.answerPreview = result.getAnswerPreview();
        r.errorCode = result.getErrorCode();
        r.finishedAt = now();
    }

    @Override
    public void recordToolCall(ChatScope scope, ToolCallRecord data) {
        StoredRun r = ownedRun(scope, data.getRunId());
        StoredToolCall tc = new StoredToolCall();
        tc.seq = data.getSeq(); tc.provider = data.getProvider(); tc.tool = data.getTool();
        tc.argsSummary = data.getArgsSummary(); tc.resultSource = data.getResultSource();
        tc.resultHash = data.getResultHash(); tc.rowCount = data.getRowCount();
        tc.status = data.getStatus(); tc.errorCode = data.getErrorCode(); tc.durationMs = data.getDurationMs();
        r.toolCalls.add(tc);
    }

    @Override
    public RunStatus getRunStatus(ChatScope scope, String runId) {
        StoredRun r = runs.get(runId == null ? "" : runId);
        if (r == null || !r.scopeKey.equals(scope.key())) return null;
        return new RunStatus(r.id, r.status, r.errorCode, r.answerPreview, r.finishedAt);
    }

    @Override
    public CancelResult cancelRun(ChatScope scope, String runId) {
        StoredRun r = runs.get(runId == null ? "" : runId);
        if (r == null || !r.scopeKey.equals(scope.key())) return CancelResult.NOT_FOUND;
        if ("RUNNING".equals(r.status)) {
            r.status = "CANCELLED"; r.errorCode = "CANCELLED"; r.finishedAt = now();
            return CancelResult.CANCELLED;
        }
        return CancelResult.ALREADY_FINISHED;
    }

    @Override
    public ChatAnswer loadAnswer(ChatScope scope, String runId) {
        StoredRun r = runs.get(runId == null ? "" : runId);
        if (r == null || !r.scopeKey.equals(scope.key())) return null;
        String assistantKey = ConversationRepository.assistantClientKey(r.requestKey);
        StoredMessage assistant = null;
        for (StoredMessage m : messages.values()) {
            if (m.scopeKey.equals(scope.key()) && m.conversationId.equals(r.conversationId)
                    && assistantKey.equals(m.clientKey)) { assistant = m; break; }
        }
        String answerText = "COMPLETE".equals(r.status)
                ? (assistant != null ? assistant.content : (r.answerPreview == null ? "" : r.answerPreview)) : "";

        ChatAnswer answer = new ChatAnswer();
        answer.setText(answerText);
        answer.setModel(r.model == null ? "" : r.model);
        answer.setRunId(r.id);
        if (r.skillSlug != null && !r.skillSlug.isEmpty()) {
            answer.setSkill(new ChatSkillRef(r.skillSlug, r.skillHash == null ? "" : r.skillHash,
                    r.skillSlug, "", SkillAvailability.AVAILABLE));
        }
        List<ChatToolEvidence> evidences = new ArrayList<>();
        List<StoredToolCall> calls = new ArrayList<>(r.toolCalls);
        calls.sort(Comparator.comparingInt(tc -> tc.seq));
        for (StoredToolCall tc : calls) {
            ChatToolEvidence ev = new ChatToolEvidence();
            ev.setSeq(tc.seq); ev.setProvider(tc.provider); ev.setTool(tc.tool);
            ev.setArgsSummary(tc.argsSummary); ev.setResultSource(tc.resultSource);
            ev.setResultHash(tc.resultHash); ev.setRowCount(tc.rowCount);
            ev.setStatus("error".equals(tc.status) ? EvidenceStatus.ERROR : EvidenceStatus.OK);
            ev.setErrorCode(tc.errorCode); ev.setDurationMs(tc.durationMs);
            evidences.add(ev);
        }
        answer.setTools(evidences);
        return answer;
    }

    @Override
    public String saveAssistantMessage(ChatScope scope, String conversationId, String runId,
                                       String requestKey, ChatAnswer answer) {
        String clientKey = ConversationRepository.assistantClientKey(requestKey);
        String mapKey = conversationId + "\u0000" + clientKey;
        String existing = messageByClientKey.get(mapKey);
        if (existing != null) return existing;
        StoredMessage m = new StoredMessage();
        m.id = newId(); m.scopeKey = scope.key(); m.conversationId = conversationId;
        m.role = "assistant"; m.content = answer.getText(); m.clientKey = clientKey;
        m.skillSlug = answer.getSkill() == null ? null : answer.getSkill().getSlug();
        m.skillHash = answer.getSkill() == null ? null : answer.getSkill().getHash();
        m.status = "COMPLETE"; m.createdAt = now();
        messages.put(m.id, m);
        messageByClientKey.put(mapKey, m.id);
        StoredRun r = runs.get(runId == null ? "" : runId);
        if (r != null && r.scopeKey.equals(scope.key())) {
            String preview = answer.getText() == null ? "" : answer.getText();
            r.answerPreview = preview.length() > 500 ? preview.substring(0, 500) : preview;
        }
        return m.id;
    }

    @Override
    public void markOrphanPendingFailed(ChatScope scope, String messageId) {
        StoredMessage m = messages.get(messageId == null ? "" : messageId);
        if (m != null && m.scopeKey.equals(scope.key()) && "PENDING".equals(m.status)) m.status = "FAILED";
    }

    @Override
    public Map<String, Object> debugState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("conversations", conversations.size());
        state.put("messages", messages.size());
        state.put("runs", runs.size());
        state.put("toolCalls", runs.values().stream().mapToInt(r -> r.toolCalls.size()).sum());
        return state;
    }
}

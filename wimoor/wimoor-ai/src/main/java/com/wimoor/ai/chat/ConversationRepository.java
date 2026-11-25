package com.wimoor.ai.chat;

import com.wimoor.ai.chat.ChatTypes.ChatAnswer;
import com.wimoor.ai.chat.ChatTypes.ChatScope;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 对话持久化边界。移植自 {@code src/lib/ai-chat/repository.ts}。
 * 原实现全部经 prisma 访问真实 DB；Java 侧以接口抽象，内存实现保留原代码的全部业务规则：
 * 所有查询强制带组织/工作区/用户三元组，杜绝跨租户读取；revision 乐观锁；clientKey 幂等；
 * requestKey 去重；run 状态机；工具证据落库；孤儿消息清理。
 */
public interface ConversationRepository {

    /** 访问被拒/不存在；status 映射原 ChatAccessError 的 HTTP 语义。 */
    final class ChatAccessError extends RuntimeException {
        private final int status;
        public ChatAccessError(String message, int status) { super(message); this.status = status; }
        public int getStatus() { return status; }
    }

    final class Conversation {
        private final String id; private final String title; private final String status;
        private final int revision; private final Instant updatedAt; private final Instant createdAt;
        public Conversation(String id, String title, String status, int revision, Instant updatedAt, Instant createdAt) {
            this.id = id; this.title = title; this.status = status; this.revision = revision;
            this.updatedAt = updatedAt; this.createdAt = createdAt;
        }
        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getStatus() { return status; }
        public int getRevision() { return revision; }
        public Instant getUpdatedAt() { return updatedAt; }
        public Instant getCreatedAt() { return createdAt; }
    }

    final class Message {
        private final String id; private final String conversationId; private final String role;
        private final String content; private final String clientKey; private final String skillSlug;
        private final String skillHash; private final String status; private final Instant createdAt;
        public Message(String id, String conversationId, String role, String content, String clientKey,
                       String skillSlug, String skillHash, String status, Instant createdAt) {
            this.id = id; this.conversationId = conversationId; this.role = role; this.content = content;
            this.clientKey = clientKey; this.skillSlug = skillSlug; this.skillHash = skillHash;
            this.status = status; this.createdAt = createdAt;
        }
        public String getId() { return id; }
        public String getConversationId() { return conversationId; }
        public String getRole() { return role; }
        public String getContent() { return content; }
        public String getClientKey() { return clientKey; }
        public String getSkillSlug() { return skillSlug; }
        public String getSkillHash() { return skillHash; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
    }

    final class ConversationDetail {
        private final Conversation conversation;
        private final List<Message> messages;
        public ConversationDetail(Conversation conversation, List<Message> messages) {
            this.conversation = conversation; this.messages = messages;
        }
        public Conversation getConversation() { return conversation; }
        public List<Message> getMessages() { return messages; }
    }

    final class DeleteResult {
        private final String id; private final boolean deleted;
        public DeleteResult(String id, boolean deleted) { this.id = id; this.deleted = deleted; }
        public String getId() { return id; }
        public boolean isDeleted() { return deleted; }
    }

    final class PreparedMessage {
        private final String messageId;
        private final String requestKey;
        private final boolean duplicate;
        private final ChatAnswer existing;
        public PreparedMessage(String messageId, String requestKey, boolean duplicate, ChatAnswer existing) {
            this.messageId = messageId; this.requestKey = requestKey; this.duplicate = duplicate; this.existing = existing;
        }
        public String getMessageId() { return messageId; }
        public String getRequestKey() { return requestKey; }
        public boolean isDuplicate() { return duplicate; }
        public ChatAnswer getExisting() { return existing; }
    }

    final class Run {
        private final String id; private final String status;
        public Run(String id, String status) { this.id = id; this.status = status; }
        public String getId() { return id; }
        public String getStatus() { return status; }
    }

    final class FinishResult {
        private final String status; private final String model; private final String skillSlug;
        private final String skillHash; private final String answerPreview; private final String errorCode;
        public FinishResult(String status, String model, String skillSlug, String skillHash,
                            String answerPreview, String errorCode) {
            this.status = status; this.model = model; this.skillSlug = skillSlug;
            this.skillHash = skillHash; this.answerPreview = answerPreview; this.errorCode = errorCode;
        }
        public String getStatus() { return status; }
        public String getModel() { return model; }
        public String getSkillSlug() { return skillSlug; }
        public String getSkillHash() { return skillHash; }
        public String getAnswerPreview() { return answerPreview; }
        public String getErrorCode() { return errorCode; }
    }

    final class ToolCallRecord {
        private final String runId; private final int seq; private final String provider; private final String tool;
        private final String argsSummary; private final String resultSource; private final String resultHash;
        private final Integer rowCount; private final String status; private final String errorCode;
        private final long durationMs;
        public ToolCallRecord(String runId, int seq, String provider, String tool, String argsSummary,
                              String resultSource, String resultHash, Integer rowCount, String status,
                              String errorCode, long durationMs) {
            this.runId = runId; this.seq = seq; this.provider = provider; this.tool = tool;
            this.argsSummary = argsSummary; this.resultSource = resultSource; this.resultHash = resultHash;
            this.rowCount = rowCount; this.status = status; this.errorCode = errorCode; this.durationMs = durationMs;
        }
        public String getRunId() { return runId; }
        public int getSeq() { return seq; }
        public String getProvider() { return provider; }
        public String getTool() { return tool; }
        public String getArgsSummary() { return argsSummary; }
        public String getResultSource() { return resultSource; }
        public String getResultHash() { return resultHash; }
        public Integer getRowCount() { return rowCount; }
        public String getStatus() { return status; }
        public String getErrorCode() { return errorCode; }
        public long getDurationMs() { return durationMs; }
    }

    final class RunStatus {
        private final String id; private final String status; private final String errorCode;
        private final String answerPreview; private final Instant finishedAt;
        public RunStatus(String id, String status, String errorCode, String answerPreview, Instant finishedAt) {
            this.id = id; this.status = status; this.errorCode = errorCode;
            this.answerPreview = answerPreview; this.finishedAt = finishedAt;
        }
        public String getId() { return id; }
        public String getStatus() { return status; }
        public String getErrorCode() { return errorCode; }
        public String getAnswerPreview() { return answerPreview; }
        public Instant getFinishedAt() { return finishedAt; }
        public boolean isCancelled() { return "CANCELLED".equals(status); }
    }

    enum CancelResult { CANCELLED("cancelled"), ALREADY_FINISHED("already_finished"), NOT_FOUND("not_found");
        private final String wire;
        CancelResult(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    List<Conversation> listConversations(ChatScope scope);

    Conversation createConversation(ChatScope scope, String title);

    ConversationDetail getConversation(ChatScope scope, String conversationId);

    /** 乐观锁重命名：expectedRevision 必须等于当前 revision。 */
    Conversation renameConversation(ChatScope scope, String conversationId, String title, int expectedRevision);

    DeleteResult deleteConversation(ChatScope scope, String conversationId);

    /** 幂等准备：同一 conversationId + clientKey 或 requestKey 重复提交不重复调用模型/工具。 */
    PreparedMessage prepareUserMessage(ChatScope scope, String conversationId, String content, String clientKey,
                                       String idempotencyKey, String skillSlug, String skillHash);

    Run createRun(ChatScope scope, String conversationId, String messageId, String requestKey,
                  String skillSlug, String skillHash);

    void markMessageComplete(ChatScope scope, String messageId);

    void finishRun(ChatScope scope, String runId, FinishResult result);

    void recordToolCall(ChatScope scope, ToolCallRecord data);

    RunStatus getRunStatus(ChatScope scope, String runId);

    /** 仅 RUNNING 可转 CANCELLED；与完成并发时以条件更新结果为准。 */
    CancelResult cancelRun(ChatScope scope, String runId);

    ChatAnswer loadAnswer(ChatScope scope, String runId);

    /** 幂等保存助手回答；run 的 messageId 始终指向用户消息，不可改写。 */
    String saveAssistantMessage(ChatScope scope, String conversationId, String runId,
                                String requestKey, ChatAnswer answer);

    /** 用户消息已建但 run 未成功落库时标记失败，避免永久 PENDING。 */
    void markOrphanPendingFailed(ChatScope scope, String messageId);

    /** assistant clientKey 派生（原 assistantClientKey）。 */
    static String assistantClientKey(String requestKey) { return "assistant:" + requestKey; }

    /** requestKey 派生（原 prepareUserMessage 内拼接）。 */
    static String requestKey(ChatScope scope, String conversationId, String idempotencyKey) {
        return scope.getOrganizationId() + ":" + scope.getWorkspaceId() + ":" + scope.getUserId()
                + ":" + conversationId + ":" + idempotencyKey;
    }

    /** 便于工具层读取所有工具证据（原 loadAnswer 的 toolCalls 升序）。 */
    Map<String, Object> debugState();
}

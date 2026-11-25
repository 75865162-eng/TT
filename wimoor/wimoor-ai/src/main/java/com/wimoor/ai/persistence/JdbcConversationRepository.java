package com.wimoor.ai.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wimoor.ai.chat.ChatTypes.ChatAnswer;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ConversationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真实 MySQL 实现的对话仓储。业务规则（租户三元组隔离、revision 乐观锁、clientKey/requestKey
 * 幂等、run 状态机、工具证据落库、孤儿消息清理）与内存实现逐条对齐，只是把 LinkedHashMap
 * 换成 JdbcTemplate + 唯一键 / 条件更新。
 *
 * 由 {@code @ConditionalOnProperty(ai.persistence=jdbc)} 装配；默认走内存实现。
 */
public final class JdbcConversationRepository implements ConversationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private long now() { return System.currentTimeMillis(); }
    private static Instant ts(long ms) { return Instant.ofEpochMilli(ms); }

    private static final RowMapper<Conversation> CONV = (rs, n) -> new Conversation(
            rs.getString("id"), rs.getString("title"), rs.getString("status"),
            rs.getInt("revision"), ts(rs.getLong("updated_at")), ts(rs.getLong("created_at")));

    private static final RowMapper<Message> MSG = (rs, n) -> new Message(
            rs.getString("id"), rs.getString("conversation_id"), rs.getString("role"),
            rs.getString("content"), rs.getString("client_key"), rs.getString("skill_slug"),
            rs.getString("skill_hash"), rs.getString("status"), ts(rs.getLong("created_at")));

    @Override
    public List<Conversation> listConversations(ChatScope scope) {
        return jdbc.query("SELECT * FROM ai_conversation WHERE org_id=? AND workspace_id=? AND user_id=? ORDER BY updated_at DESC",
                CONV, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
    }

    @Override
    public Conversation createConversation(ChatScope scope, String title) {
        String id = id(); long t = now();
        jdbc.update("INSERT INTO ai_conversation(id,org_id,workspace_id,user_id,title,status,revision,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',0,?,?)",
                id, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId(),
                title == null ? "" : title, t, t);
        return new Conversation(id, title, "ACTIVE", 0, ts(t), ts(t));
    }

    @Override
    public ConversationDetail getConversation(ChatScope scope, String conversationId) {
        List<Conversation> cs = jdbc.query("SELECT * FROM ai_conversation WHERE id=? AND org_id=? AND workspace_id=? AND user_id=?",
                CONV, conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        if (cs.isEmpty()) throw new ChatAccessError("CONVERSATION_NOT_FOUND", 404);
        List<Message> ms = jdbc.query("SELECT * FROM ai_message WHERE conversation_id=? AND org_id=? AND workspace_id=? AND user_id=? ORDER BY created_at",
                MSG, conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        return new ConversationDetail(cs.get(0), ms);
    }

    @Override
    public Conversation renameConversation(ChatScope scope, String conversationId, String title, int expectedRevision) {
        int n = jdbc.update("UPDATE ai_conversation SET title=?, revision=revision+1, updated_at=? WHERE id=? AND org_id=? AND workspace_id=? AND user_id=? AND revision=?",
                title, now(), conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId(), expectedRevision);
        if (n == 0) throw new ChatAccessError("REVISION_CONFLICT_OR_NOT_FOUND", 409);
        return getConversation(scope, conversationId).getConversation();
    }

    @Override
    public DeleteResult deleteConversation(ChatScope scope, String conversationId) {
        int n = jdbc.update("DELETE FROM ai_conversation WHERE id=? AND org_id=? AND workspace_id=? AND user_id=?",
                conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        if (n > 0) {
            jdbc.update("DELETE FROM ai_message WHERE conversation_id=? AND org_id=? AND workspace_id=? AND user_id=?",
                    conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
            jdbc.update("DELETE FROM ai_run WHERE conversation_id=? AND org_id=? AND workspace_id=? AND user_id=?",
                    conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        }
        return new DeleteResult(conversationId, n > 0);
    }

    @Override
    public PreparedMessage prepareUserMessage(ChatScope scope, String conversationId, String content,
                                              String clientKey, String idempotencyKey, String skillSlug, String skillHash) {
        // 会话存在性校验（租户隔离）
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation WHERE id=? AND org_id=? AND workspace_id=? AND user_id=?",
                Integer.class, conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        if (exists == null || exists == 0) throw new ChatAccessError("CONVERSATION_NOT_FOUND", 404);

        String rk = ConversationRepository.requestKey(scope, conversationId, idempotencyKey);
        // requestKey 幂等：已有则查对应 run 的 answer
        List<String> priorRun = jdbc.queryForList("SELECT id FROM ai_run WHERE request_key=?", String.class, rk);
        if (!priorRun.isEmpty()) {
            ChatAnswer ans = loadAnswer(scope, priorRun.get(0));
            return new PreparedMessage("", rk, true, ans);
        }
        if (clientKey != null && !clientKey.isEmpty()) {
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE org_id=? AND workspace_id=? AND user_id=? AND client_key=?",
                    Integer.class, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId(), clientKey);
            if (dup != null && dup > 0) return new PreparedMessage("", rk, true, null);
        }
        String mid = id();
        jdbc.update("INSERT INTO ai_message(id,conversation_id,org_id,workspace_id,user_id,role,content,client_key,request_key,skill_slug,skill_hash,status,created_at) VALUES(?,?,?,?,?,'user',?,?,?,?,?,'PENDING',?)",
                mid, conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId(),
                content, clientKey, rk, skillSlug, skillHash, now());
        return new PreparedMessage(mid, rk, false, null);
    }

    @Override
    public Run createRun(ChatScope scope, String conversationId, String messageId, String requestKey,
                         String skillSlug, String skillHash) {
        String id = id();
        try {
            jdbc.update("INSERT INTO ai_run(id,conversation_id,org_id,workspace_id,user_id,message_id,request_key,status,skill_slug,skill_hash,created_at) VALUES(?,?,?,?,?,?,?,'RUNNING',?,?,?)",
                    id, conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId(),
                    messageId, requestKey, skillSlug, skillHash, now());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // requestKey 唯一冲突 = 重放，返回既有 run
            List<String> existing = jdbc.queryForList("SELECT id FROM ai_run WHERE request_key=?", String.class, requestKey);
            return new Run(existing.get(0), "RUNNING");
        }
        return new Run(id, "RUNNING");
    }

    @Override
    public void markMessageComplete(ChatScope scope, String messageId) {
        jdbc.update("UPDATE ai_message SET status='COMPLETE' WHERE id=? AND org_id=? AND workspace_id=? AND user_id=?",
                messageId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
    }

    @Override
    public void finishRun(ChatScope scope, String runId, FinishResult result) {
        jdbc.update("UPDATE ai_run SET status=?,model=?,skill_slug=?,skill_hash=?,answer_preview=?,error_code=?,finished_at=? WHERE id=? AND org_id=? AND workspace_id=? AND user_id=? AND status='RUNNING'",
                result.getStatus(), result.getModel(), result.getSkillSlug(), result.getSkillHash(),
                result.getAnswerPreview(), result.getErrorCode(), now(), runId,
                scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
    }

    @Override
    public void recordToolCall(ChatScope scope, ToolCallRecord d) {
        jdbc.update("INSERT INTO ai_tool_call(id,run_id,org_id,seq,provider,tool,args_summary,result_source,result_hash,row_count,status,error_code,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status)",
                id(), d.getRunId(), scope.getOrganizationId(), d.getSeq(), d.getProvider(), d.getTool(),
                d.getArgsSummary(), d.getResultSource(), d.getResultHash(), d.getRowCount(),
                d.getStatus(), d.getErrorCode(), d.getDurationMs());
    }

    @Override
    public RunStatus getRunStatus(ChatScope scope, String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status,error_code,answer_preview,finished_at FROM ai_run WHERE id=? AND org_id=? AND workspace_id=? AND user_id=?",
                runId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        Object fa = r.get("finished_at");
        return new RunStatus(runId, String.valueOf(r.get("status")), (String) r.get("error_code"),
                (String) r.get("answer_preview"), fa == null ? null : ts(((Number) fa).longValue()));
    }

    @Override
    public CancelResult cancelRun(ChatScope scope, String runId) {
        int n = jdbc.update("UPDATE ai_run SET status='CANCELLED',finished_at=? WHERE id=? AND org_id=? AND workspace_id=? AND user_id=? AND status='RUNNING'",
                now(), runId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        if (n > 0) return CancelResult.CANCELLED;
        Integer running = jdbc.queryForObject("SELECT COUNT(*) FROM ai_run WHERE id=? AND org_id=? AND workspace_id=? AND user_id=? AND status='RUNNING'",
                Integer.class, runId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        return (running == null || running == 0) ? CancelResult.ALREADY_FINISHED : CancelResult.NOT_FOUND;
    }

    @Override
    public ChatAnswer loadAnswer(ChatScope scope, String runId) {
        List<String> js = jdbc.queryForList("SELECT answer_json FROM ai_run WHERE id=? AND org_id=? AND workspace_id=? AND user_id=?",
                String.class, runId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
        if (js.isEmpty() || js.get(0) == null) return null;
        try { return mapper.readValue(js.get(0), ChatAnswer.class); }
        catch (Exception e) { throw new IllegalStateException("ANSWER_DESERIALIZE_FAILED", e); }
    }

    @Override
    public String saveAssistantMessage(ChatScope scope, String conversationId, String runId,
                                       String requestKey, ChatAnswer answer) {
        String mid = id();
        try {
            String json = mapper.writeValueAsString(answer);
            jdbc.update("INSERT INTO ai_message(id,conversation_id,org_id,workspace_id,user_id,role,content,client_key,request_key,status,created_at) VALUES(?,?,?,?,?,'assistant',?,?,?,'COMPLETE',?)",
                    mid, conversationId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId(),
                    answer.getText(), ConversationRepository.assistantClientKey(requestKey), requestKey, now());
            jdbc.update("UPDATE ai_run SET answer_json=? WHERE id=? AND request_key=?", json, runId, requestKey);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // assistant 幂等：返回既有 assistant 消息 id
            List<String> ids = jdbc.queryForList("SELECT id FROM ai_message WHERE org_id=? AND workspace_id=? AND user_id=? AND client_key=?",
                    String.class, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId(), ConversationRepository.assistantClientKey(requestKey));
            return ids.isEmpty() ? mid : ids.get(0);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new IllegalStateException("ANSWER_SERIALIZE_FAILED", e);
        }
        return mid;
    }

    @Override
    public void markOrphanPendingFailed(ChatScope scope, String messageId) {
        jdbc.update("UPDATE ai_message SET status='FAILED' WHERE id=? AND org_id=? AND workspace_id=? AND user_id=? AND status='PENDING'",
                messageId, scope.getOrganizationId(), scope.getWorkspaceId(), scope.getUserId());
    }

    @Override
    public Map<String, Object> debugState() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversations", jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation", Integer.class));
        m.put("messages", jdbc.queryForObject("SELECT COUNT(*) FROM ai_message", Integer.class));
        m.put("runs", jdbc.queryForObject("SELECT COUNT(*) FROM ai_run", Integer.class));
        m.put("toolCalls", jdbc.queryForObject("SELECT COUNT(*) FROM ai_tool_call", Integer.class));
        return m;
    }
}

package com.wimoor.ai.persistence;

import com.wimoor.ai.chat.Billing;
import com.wimoor.ai.chat.ChatTypes.ChatAnswer;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ConversationRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 MySQL 集成测试：验证 JdbcConversationRepository / JdbcPointsLedger 的生产级语义。
 * 通过系统属性提供连接（-Dai.it.url / -Dai.it.user / -Dai.it.password）；
 * 无数据库时整类跳过，不影响默认 200 测试全绿。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JdbcPersistenceIT {

    private JdbcTemplate jdbc;
    private static boolean available;

    @BeforeAll
    void setUp() {
        String url = System.getProperty("ai.it.url", "jdbc:mysql://127.0.0.1:13307/db_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
        String user = System.getProperty("ai.it.user", "root");
        String pass = System.getProperty("ai.it.password", "test123456");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, pass);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(ds);
        try (Connection c = ds.getConnection()) {
            available = true;
            jdbc.execute("CREATE TABLE IF NOT EXISTS ai_conversation (id VARCHAR(64) PRIMARY KEY, org_id VARCHAR(64), workspace_id VARCHAR(64), user_id VARCHAR(64), title VARCHAR(512), status VARCHAR(32), revision INT, created_at BIGINT, updated_at BIGINT)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS ai_message (id VARCHAR(64) PRIMARY KEY, conversation_id VARCHAR(64), org_id VARCHAR(64), workspace_id VARCHAR(64), user_id VARCHAR(64), role VARCHAR(16), content TEXT, client_key VARCHAR(128), request_key VARCHAR(160), skill_slug VARCHAR(128), skill_hash VARCHAR(128), status VARCHAR(32), created_at BIGINT, UNIQUE KEY uq_ck (org_id,workspace_id,user_id,client_key))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS ai_run (id VARCHAR(64) PRIMARY KEY, conversation_id VARCHAR(64), org_id VARCHAR(64), workspace_id VARCHAR(64), user_id VARCHAR(64), message_id VARCHAR(64), request_key VARCHAR(160), status VARCHAR(32), model VARCHAR(128), skill_slug VARCHAR(128), skill_hash VARCHAR(128), answer_preview VARCHAR(512), error_code VARCHAR(64), answer_json TEXT, finished_at BIGINT, created_at BIGINT, UNIQUE KEY uq_rk (request_key))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS ai_tool_call (id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64), org_id VARCHAR(64), seq INT, provider VARCHAR(64), tool VARCHAR(128), args_summary VARCHAR(512), result_source VARCHAR(64), result_hash VARCHAR(128), row_count INT, status VARCHAR(32), error_code VARCHAR(64), duration_ms BIGINT, UNIQUE KEY uq_rs (run_id,seq))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS ai_points_balance (org_id VARCHAR(64), user_id VARCHAR(64), balance DECIMAL(20,6), enforce TINYINT, updated_at BIGINT, PRIMARY KEY(org_id,user_id))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS ai_points_ledger (request_key VARCHAR(200) PRIMARY KEY, org_id VARCHAR(64), user_id VARCHAR(64), provider VARCHAR(64), points DECIMAL(20,6), charged TINYINT, blocked TINYINT, released TINYINT, created_at BIGINT)");
        } catch (Exception e) {
            available = false;
            System.err.println("[JdbcPersistenceIT] DB 不可用，跳过: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void assumeDb() { Assumptions.assumeTrue(available, "MySQL 测试库不可用，跳过集成测试"); }

    private void clean() {
        jdbc.execute("DELETE FROM ai_tool_call");
        jdbc.execute("DELETE FROM ai_run");
        jdbc.execute("DELETE FROM ai_message");
        jdbc.execute("DELETE FROM ai_conversation");
        jdbc.execute("DELETE FROM ai_points_ledger");
        jdbc.execute("DELETE FROM ai_points_balance");
    }

    @Test
    void conversationCrudAndTenantIsolation() {
        assumeDb(); clean();
        JdbcConversationRepository repo = new JdbcConversationRepository(jdbc);
        ChatScope s1 = new ChatScope("org1", "ws1", "u1");
        ChatScope s2 = new ChatScope("org1", "ws1", "u2");

        ConversationRepository.Conversation c = repo.createConversation(s1, "测试会话");
        assertNotNull(c.getId());
        assertEquals("测试会话", c.getTitle());
        assertEquals(1, repo.listConversations(s1).size());
        assertEquals(0, repo.listConversations(s2).size(), "不同用户不得看到他人会话");

        // revision 乐观锁
        ConversationRepository.Conversation renamed = repo.renameConversation(s1, c.getId(), "改名", 0);
        assertEquals(1, renamed.getRevision());
        assertThrows(ConversationRepository.ChatAccessError.class,
                () -> repo.renameConversation(s1, c.getId(), "冲突", 0), "旧 revision 必须冲突");

        // 跨租户访问被拒
        assertThrows(ConversationRepository.ChatAccessError.class,
                () -> repo.getConversation(s2, c.getId()), "跨租户读取必须拒绝");

        // 删除
        assertTrue(repo.deleteConversation(s1, c.getId()).isDeleted());
        assertEquals(0, repo.listConversations(s1).size());
    }

    @Test
    void messageIdempotencyAndRunStateMachine() {
        assumeDb(); clean();
        JdbcConversationRepository repo = new JdbcConversationRepository(jdbc);
        ChatScope s = new ChatScope("org1", "ws1", "u1");
        ConversationRepository.Conversation c = repo.createConversation(s, "幂等测试");

        ConversationRepository.PreparedMessage p1 = repo.prepareUserMessage(s, c.getId(), "你好", "ck-1", "idem-1", null, null);
        assertFalse(p1.isDuplicate());
        ConversationRepository.PreparedMessage p2 = repo.prepareUserMessage(s, c.getId(), "你好", "ck-1", "idem-1", null, null);
        assertTrue(p2.isDuplicate(), "相同 clientKey/requestKey 必须幂等");

        ConversationRepository.Run run = repo.createRun(s, c.getId(), p1.getMessageId(), p1.getRequestKey(), null, null);
        assertEquals("RUNNING", run.getStatus());
        assertNotNull(repo.getRunStatus(s, run.getId()));

        // 取消：RUNNING → CANCELLED
        assertEquals(ConversationRepository.CancelResult.CANCELLED, repo.cancelRun(s, run.getId()));
        assertEquals(ConversationRepository.CancelResult.ALREADY_FINISHED, repo.cancelRun(s, run.getId()), "已完成/取消不得重复取消");

        // 工具证据
        repo.recordToolCall(s, new ConversationRepository.ToolCallRecord(run.getId(), 1, "sellersprite", "search", "q=test", "api", "hash1", 10, "OK", null, 15));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) repo.debugState();
        assertEquals(1L, ((Number) state.get("toolCalls")).longValue());
    }

    @Test
    void assistantAnswerRoundTrip() {
        assumeDb(); clean();
        JdbcConversationRepository repo = new JdbcConversationRepository(jdbc);
        ChatScope s = new ChatScope("org1", "ws1", "u1");
        ConversationRepository.Conversation c = repo.createConversation(s, "回答测试");
        ConversationRepository.PreparedMessage p = repo.prepareUserMessage(s, c.getId(), "问题", null, "idem-a", null, null);
        ConversationRepository.Run run = repo.createRun(s, c.getId(), p.getMessageId(), p.getRequestKey(), null, null);
        ChatAnswer ans = new ChatAnswer();
        ans.setText("这是回答");
        ans.setModel("gpt-test");
        String msgId = repo.saveAssistantMessage(s, c.getId(), run.getId(), p.getRequestKey(), ans);
        assertNotNull(msgId);
        // 幂等：同 requestKey 再存返回相同 messageId
        String msgId2 = repo.saveAssistantMessage(s, c.getId(), run.getId(), p.getRequestKey(), ans);
        assertEquals(msgId, msgId2, "assistant 回答必须幂等");
        ChatAnswer loaded = repo.loadAnswer(s, run.getId());
        assertNotNull(loaded);
        assertEquals("这是回答", loaded.getText());
    }

    @Test
    void pointsLedgerIdempotencyAndRelease() {
        assumeDb(); clean();
        jdbc.update("INSERT INTO ai_points_balance(org_id,user_id,balance,enforce,updated_at) VALUES('org1','u1',?,0,?)", new BigDecimal("100"), System.currentTimeMillis());
        JdbcPointsLedger ledger = new JdbcPointsLedger(jdbc, "org1", "u1");
        assertEquals(0, new BigDecimal("100").compareTo(ledger.getBalance()));
        BigDecimal price = Billing.SELECTION_RESEARCH_PRICE.get("sellersprite");

        Billing.Settlement s1 = ledger.settle("rk-1", "sellersprite", price);
        assertTrue(s1.isCharged());
        assertEquals(0, new BigDecimal("98").compareTo(ledger.getBalance()));
        Billing.Settlement s2 = ledger.settle("rk-1", "sellersprite", price);
        assertTrue(s2.isDuplicate(), "同 requestKey 不得重复扣分");
        assertEquals(0, new BigDecimal("98").compareTo(ledger.getBalance()));

        ledger.release("rk-1");
        assertEquals(0, new BigDecimal("100").compareTo(ledger.getBalance()), "释放后余额恢复");
        ledger.release("rk-1");
        assertEquals(0, new BigDecimal("100").compareTo(ledger.getBalance()), "重复释放不得重复返还");
    }
}

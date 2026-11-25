package com.wimoor.ai.chat;

import com.wimoor.ai.chat.ChatTypes.ChatAnswer;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ChatTypes.ChatSkillRef;
import com.wimoor.ai.chat.ChatTypes.SkillAvailability;
import com.wimoor.ai.chat.ConversationRepository.CancelResult;
import com.wimoor.ai.chat.ConversationRepository.ChatAccessError;
import com.wimoor.ai.chat.ConversationRepository.Conversation;
import com.wimoor.ai.chat.ConversationRepository.PreparedMessage;
import com.wimoor.ai.chat.ConversationRepository.Run;
import com.wimoor.ai.chat.ConversationRepository.ToolCallRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 repository.ts：三元组隔离、revision 乐观锁、幂等准备、run 状态机与回答装载。 */
class ConversationRepositoryTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
    private final ChatScope scope = new ChatScope("org", "ws", "user");

    private InMemoryConversationRepository repo() {
        AtomicInteger seq = new AtomicInteger();
        return new InMemoryConversationRepository(FIXED, () -> "id-" + seq.incrementAndGet());
    }

    @Test
    void conversationsAreTenantAndWorkspaceScoped() {
        InMemoryConversationRepository repo = repo();
        Conversation c = repo.createConversation(scope, "会话一");
        assertEquals("会话一", c.getTitle());
        assertEquals(0, c.getRevision());
        assertEquals(1, repo.listConversations(scope).size());
        assertTrue(repo.listConversations(new ChatScope("org", "other-ws", "user")).isEmpty());
        assertTrue(repo.listConversations(new ChatScope("other", "ws", "user")).isEmpty());
        ChatAccessError ex = assertThrows(ChatAccessError.class,
                () -> repo.getConversation(new ChatScope("org", "other-ws", "user"), c.getId()));
        assertEquals(404, ex.getStatus());
    }

    @Test
    void prepareUserMessageIsIdempotentByClientKey() {
        InMemoryConversationRepository repo = repo();
        Conversation c = repo.createConversation(scope, "会话");
        PreparedMessage first = repo.prepareUserMessage(scope, c.getId(), "问题", "client-1",
                "idem-1", null, null);
        assertFalse(first.isDuplicate());
        PreparedMessage second = repo.prepareUserMessage(scope, c.getId(), "问题", "client-1",
                "idem-2", null, null);
        assertTrue(second.isDuplicate());
        assertEquals(first.getMessageId(), second.getMessageId());
        assertEquals("org:ws:user:" + c.getId() + ":idem-1", first.getRequestKey());
        assertEquals(1, repo.debugState().get("messages"));
    }

    @Test
    void prepareIsIdempotentByRequestKey() {
        InMemoryConversationRepository repo = repo();
        Conversation c = repo.createConversation(scope, "会话");
        PreparedMessage first = repo.prepareUserMessage(scope, c.getId(), "问题", "client-a", "idem-X", null, null);
        repo.createRun(scope, c.getId(), first.getMessageId(), first.getRequestKey(), null, null);
        PreparedMessage second = repo.prepareUserMessage(scope, c.getId(), "问题", "client-b", "idem-X", null, null);
        assertTrue(second.isDuplicate());
        assertEquals("", second.getMessageId());
    }

    @Test
    void renameUsesRevisionOptimisticLock() {
        InMemoryConversationRepository repo = repo();
        Conversation c = repo.createConversation(scope, "会话");
        ChatAccessError conflict = assertThrows(ChatAccessError.class,
                () -> repo.renameConversation(scope, c.getId(), "新标题", 5));
        assertEquals(409, conflict.getStatus());
        Conversation renamed = repo.renameConversation(scope, c.getId(), "  新标题  ", 0);
        assertEquals("新标题", renamed.getTitle());
        assertEquals(1, renamed.getRevision());
        assertEquals(400, assertThrows(ChatAccessError.class,
                () -> repo.renameConversation(scope, c.getId(), "  ", 1)).getStatus());
    }

    @Test
    void cancelStateMachineAndSoftDelete() {
        InMemoryConversationRepository repo = repo();
        Conversation c = repo.createConversation(scope, "会话");
        PreparedMessage prepared = repo.prepareUserMessage(scope, c.getId(), "q", "ck", "ik", null, null);
        Run run = repo.createRun(scope, c.getId(), prepared.getMessageId(), prepared.getRequestKey(), null, null);
        assertEquals(CancelResult.CANCELLED, repo.cancelRun(scope, run.getId()));
        assertEquals(CancelResult.ALREADY_FINISHED, repo.cancelRun(scope, run.getId()));
        assertTrue(repo.getRunStatus(scope, run.getId()).isCancelled());
        assertEquals(CancelResult.NOT_FOUND, repo.cancelRun(scope, "missing"));

        assertTrue(repo.deleteConversation(scope, c.getId()).isDeleted());
        assertTrue(repo.listConversations(scope).isEmpty());
    }

    @Test
    void loadAnswerIncludesOrderedToolEvidenceAndSkillRef() {
        InMemoryConversationRepository repo = repo();
        Conversation c = repo.createConversation(scope, "会话");
        PreparedMessage prepared = repo.prepareUserMessage(scope, c.getId(), "q", "ck", "ik", "slug", "hash");
        Run run = repo.createRun(scope, c.getId(), prepared.getMessageId(), prepared.getRequestKey(), "slug", "hash");
        repo.recordToolCall(scope, new ToolCallRecord(run.getId(), 2, "amazon", "erp__list_orders",
                "{}", "erp", null, 7, "ok", null, 12));
        repo.recordToolCall(scope, new ToolCallRecord(run.getId(), 1, "xiyou", "xiyou__product_search",
                "{}", "xiyou", null, 3, "error", "TOOL_FAILED", 5));

        ChatAnswer partial = repo.loadAnswer(scope, run.getId());
        assertEquals("", partial.getText());
        assertEquals(2, partial.getTools().size());
        assertEquals(1, partial.getTools().get(0).getSeq());
        assertEquals("xiyou", partial.getTools().get(0).getProvider());

        ChatAnswer answer = new ChatAnswer();
        answer.setText("最终答案");
        answer.setSkill(new ChatSkillRef("slug", "hash", "name", "sum", SkillAvailability.AVAILABLE));
        repo.saveAssistantMessage(scope, c.getId(), run.getId(), prepared.getRequestKey(), answer);
        repo.finishRun(scope, run.getId(), new ConversationRepository.FinishResult(
                "COMPLETE", "gpt", "slug", "hash", "最终答案", null));

        ChatAnswer loaded = repo.loadAnswer(scope, run.getId());
        assertEquals("最终答案", loaded.getText());
        assertEquals("gpt", loaded.getModel());
        assertEquals("hash", loaded.getSkill().getHash());
        assertEquals(2, loaded.getTools().size());
    }

    @Test
    void orphanPendingMessageCanBeMarkedFailed() {
        InMemoryConversationRepository repo = repo();
        Conversation c = repo.createConversation(scope, "会话");
        PreparedMessage prepared = repo.prepareUserMessage(scope, c.getId(), "q", "ck", "ik", null, null);
        assertEquals("PENDING", repo.getConversation(scope, c.getId()).getMessages().get(0).getStatus());
        repo.markOrphanPendingFailed(scope, prepared.getMessageId());
        assertEquals("FAILED", repo.getConversation(scope, c.getId()).getMessages().get(0).getStatus());
    }
}

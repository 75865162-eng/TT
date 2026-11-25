package com.wimoor.ai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wimoor.ai.chat.Billing;
import com.wimoor.ai.chat.Billing.PointsLedger;
import com.wimoor.ai.chat.ChatTypes.ChatAnswer;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ConversationRepository;
import com.wimoor.ai.persistence.HttpLlmClient;
import com.wimoor.ai.research.ResearchStore;
import com.wimoor.ai.research.ResearchTypes.ResearchRun;
import com.wimoor.ai.skill.AiModelSettings;
import com.wimoor.ai.skill.LlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实持久化写端点：仅当 ai.persistence=jdbc 时装配真实仓储，否则返回 503（明确告知未启用持久化）。
 *
 * 安全约定：org/workspace/user 三元组由网关/会话鉴权后注入；本层仅做完整性校验与租户隔离，
 * 不信任客户端自称身份。生产部署须在网关注入这些 header 并校验与主会话一致。
 */
@RestController
@RequestMapping("/ai/api/v1")
public class AiWriteController {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private ConversationRepository conversationRepository;

    @Autowired(required = false)
    private PointsLedger pointsLedger;

    @Autowired(required = false)
    private ResearchStore researchStore;

    @Autowired(required = false)
    private LlmClient llmClient;

    private void requirePersistence() {
        if (conversationRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_PERSISTENCE_DISABLED: 需以 --ai.persistence=jdbc 启动并配置 db_ai");
        }
    }

    private ChatScope scope(String org, String ws, String user) {
        if (org == null || org.isEmpty() || ws == null || ws.isEmpty() || user == null || user.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_SCOPE: org/workspace/user 必填");
        }
        return new ChatScope(org, ws, user);
    }

    // ---------- 对话 ----------

    @GetMapping("/conversations")
    public Object listConversations(@RequestParam String org, @RequestParam String ws, @RequestParam String user) {
        requirePersistence();
        return conversationRepository.listConversations(scope(org, ws, user));
    }

    @PostMapping("/conversations")
    public Object createConversation(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                                     @RequestBody Map<String, String> body) {
        requirePersistence();
        return conversationRepository.createConversation(scope(org, ws, user), body.get("title"));
    }

    @GetMapping("/conversations/{id}")
    public Object getConversation(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                                  @PathVariable String id) {
        requirePersistence();
        try { return conversationRepository.getConversation(scope(org, ws, user), id); }
        catch (ConversationRepository.ChatAccessError e) {
            throw new ResponseStatusException(HttpStatus.valueOf(e.getStatus()), e.getMessage());
        }
    }

    @PatchMapping("/conversations/{id}")
    public Object renameConversation(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                                     @PathVariable String id, @RequestBody Map<String, Object> body) {
        requirePersistence();
        int rev = body.get("expectedRevision") == null ? 0 : ((Number) body.get("expectedRevision")).intValue();
        try { return conversationRepository.renameConversation(scope(org, ws, user), id, String.valueOf(body.get("title")), rev); }
        catch (ConversationRepository.ChatAccessError e) {
            throw new ResponseStatusException(HttpStatus.valueOf(e.getStatus()), e.getMessage());
        }
    }

    @DeleteMapping("/conversations/{id}")
    public Object deleteConversation(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                                     @PathVariable String id) {
        requirePersistence();
        return conversationRepository.deleteConversation(scope(org, ws, user), id);
    }

    @PostMapping("/conversations/{id}/messages")
    public Object prepareMessage(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                                 @PathVariable String id, @RequestBody Map<String, String> body) {
        requirePersistence();
        try {
            return conversationRepository.prepareUserMessage(scope(org, ws, user), id,
                    body.get("content"), body.get("clientKey"), body.get("idempotencyKey"),
                    body.get("skillSlug"), body.get("skillHash"));
        } catch (ConversationRepository.ChatAccessError e) {
            throw new ResponseStatusException(HttpStatus.valueOf(e.getStatus()), e.getMessage());
        }
    }

    @PostMapping("/runs")
    public Object createRun(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                            @RequestBody Map<String, String> body) {
        requirePersistence();
        return conversationRepository.createRun(scope(org, ws, user), body.get("conversationId"),
                body.get("messageId"), body.get("requestKey"), body.get("skillSlug"), body.get("skillHash"));
    }

    @GetMapping("/runs/{id}")
    public Object runStatus(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                            @PathVariable String id) {
        requirePersistence();
        return conversationRepository.getRunStatus(scope(org, ws, user), id);
    }

    @PostMapping("/runs/{id}/cancel")
    public Object cancelRun(@RequestParam String org, @RequestParam String ws, @RequestParam String user,
                            @PathVariable String id) {
        requirePersistence();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", conversationRepository.cancelRun(scope(org, ws, user), id).wire());
        return out;
    }

    /** 真实模型直调（诊断用）：需服务端已配置 endpoint/apiKey；不落库、不编排，仅验证 transport 连通。 */
    @PostMapping("/llm/probe")
    public Object llmProbe(@RequestBody Map<String, String> body) {
        if (llmClient == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_TRANSPORT_DISABLED");
        AiModelSettings s = new AiModelSettings(body.get("apiKey"), body.get("model"), body.get("provider"),
                body.getOrDefault("wireApi", "chat_completions"), body.get("endpoint"));
        try {
            String text = llmClient.call(s, body.getOrDefault("system", "你是一个助手。"), body.getOrDefault("user", "ping"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("text", text);
            return out;
        } catch (RuntimeException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    // ---------- 积分 ----------

    @GetMapping("/points")
    public Object points(@RequestParam String org, @RequestParam String user) {
        if (pointsLedger == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PERSISTENCE_DISABLED");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balance", pointsLedger.getBalance());
        out.put("enforce", pointsLedger.isEnforce());
        out.put("entries", pointsLedger.entries());
        return out;
    }

    // ---------- 选品研究 ----------

    @PostMapping("/research")
    public Object createResearch(@RequestParam String org, @RequestParam String user, @RequestBody JsonNode body) {
        if (researchStore == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PERSISTENCE_DISABLED");
        try {
            ResearchRun run = mapper.treeToValue(body, ResearchRun.class);
            if (run.id == null || run.id.isEmpty()) throw new IllegalArgumentException("id 必填");
            if (run.scope == null) throw new IllegalArgumentException("scope 必填");
            researchStore.create(run);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", run.id);
            out.put("status", "CREATED");
            return out;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "RESEARCH_CREATE_FAILED");
        }
    }

    @GetMapping("/research/{id}")
    public Object getResearch(@PathVariable String id) {
        if (researchStore == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PERSISTENCE_DISABLED");
        ResearchRun run = researchStore.read(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RESEARCH_RUN_NOT_FOUND");
        return run;
    }
}

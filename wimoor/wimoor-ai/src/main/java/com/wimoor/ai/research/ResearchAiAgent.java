package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP 现有 AI 传输上的研究代理（移植自 src/lib/selection-research/ai-agent.ts）。
 * 凭证只存在请求头；只持久化主机生成的状态码，绝不保存模型原文或密钥。
 */
public final class ResearchAiAgent implements AiAgent {

    public static final class AiRequest {
        public String endpoint;
        public String method;
        public Map<String, String> headers = new LinkedHashMap<>();
        public String body;
    }

    public static final class AiResponse {
        public boolean ok;
        public int status;
        public JsonNode body;
    }

    /** 宿主注入实际 HTTP 实现，便于隔离测试。 */
    public interface AiRequestSender {
        AiResponse send(AiRequest request);
    }

    private static final int MAX_PROMPT = 180_000;
    private final AiSettings.AiModelSettings settings;
    private final AiRequestSender sender;

    public ResearchAiAgent(AiSettings.AiModelSettings settings, AiRequestSender sender) {
        this.settings = settings;
        this.sender = sender;
    }

    @Override
    public ResearchTypes.Action next(ResearchTypes.ResearchContext context) {
        if (settings.apiKey == null || settings.apiKey.isEmpty())
            throw new ResearchError("RESEARCH_AI_NOT_CONFIGURED");
        ResearchTypes.ResearchRun run = context.run;
        Map<String, Object> promptMap = new LinkedHashMap<>();
        promptMap.put("skill", context.skill);
        promptMap.put("tools", context.tools);
        Map<String, Object> runView = new LinkedHashMap<>();
        runView.put("id", run.id);
        runView.put("validationFeedback", run.validationFeedback);
        runView.put("brief", run.brief);
        runView.put("requirement", run.requirement);
        runView.put("asin", run.asin);
        runView.put("marketplace", run.marketplace);
        runView.put("stage", run.stage);
        runView.put("requiredProviders", run.requiredProviders);
        runView.put("selectedCandidateId", run.selectedCandidateId);
        runView.put("results", run.results);
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ResearchTypes.CallRecord call : run.calls) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", call.id);
            view.put("stage", call.stage);
            view.put("provider", call.provider);
            view.put("tool", call.tool);
            view.put("arguments", call.arguments);
            view.put("status", call.status);
            view.put("errorCode", call.errorCode);
            boolean exactSkill = "meta_load_skill".equals(call.tool) && call.arguments != null
                    && context.skill != null && context.skill.name.equals(call.arguments.path("skill_name").asText());
            view.put("rawResponse", exactSkill
                    ? Map.of("note", "Exact Skill body is supplied in context.skill.") : call.rawResponse);
            calls.add(view);
        }
        runView.put("calls", calls);
        promptMap.put("run", runView);
        String prompt;
        try {
            prompt = ResearchSchema.MAPPER.writeValueAsString(promptMap);
        } catch (Exception e) {
            throw new ResearchError("RESEARCH_AI_REQUEST_FAILED", e);
        }
        if (prompt.length() > MAX_PROMPT) throw new ResearchError("RESEARCH_CONTEXT_TOO_LARGE_REQUIRES_REVIEW");
        boolean chat = "chat_completions".equals(settings.wireApi);
        AiRequest request = new AiRequest();
        request.method = "POST";
        request.endpoint = settings.endpoint == null ? "" : settings.endpoint;
        request.headers.put("content-type", "application/json");
        request.headers.put("authorization", "Bearer " + settings.apiKey);
        try {
            if (chat) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", settings.model);
                body.put("messages", List.of(
                        Map.of("role", "system", "content", context.instructions),
                        Map.of("role", "user", "content", prompt)));
                body.put("response_format", Map.of("type", "json_object"));
                request.body = ResearchSchema.MAPPER.writeValueAsString(body);
            } else {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", settings.model);
                body.put("instructions", context.instructions);
                body.put("input", prompt);
                body.put("text", Map.of("format", Map.of("type", "json_object")));
                request.body = ResearchSchema.MAPPER.writeValueAsString(body);
            }
        } catch (Exception e) {
            throw new ResearchError("RESEARCH_AI_REQUEST_FAILED", e);
        }
        AiResponse response = Bounded.call(() -> {
            AiResponse r = sender.send(request);
            if (r == null) throw new ResearchError("RESEARCH_AI_REQUEST_FAILED");
            return r;
        }, 25_000L, "RESEARCH_AI_REQUEST_FAILED");
        if (!response.ok)
            throw new ResearchError("RESEARCH_AI_HTTP_" + (response.status >= 400 && response.status <= 599
                    ? response.status : "INVALID"));
        String text = extractText(chat, response.body);
        if (text == null || text.isEmpty() || text.contains(settings.apiKey))
            throw new ResearchError("UNSAFE_OR_EMPTY_AI_RESPONSE");
        try {
            return ResearchSchema.MAPPER.treeToValue(Fixture.parseAction(text), ResearchTypes.Action.class);
        } catch (Exception e) {
            throw new ResearchError("INVALID_AGENT_JSON", e);
        }
    }

    private static String extractText(boolean chat, JsonNode body) {
        if (body == null) return null;
        if (chat) {
            JsonNode content = body.path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : null;
        }
        if (body.path("output_text").isTextual()) return body.path("output_text").asText();
        JsonNode output = body.path("output");
        if (!output.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : output) {
            for (JsonNode content : part.path("content")) {
                JsonNode text = content.path("text");
                if (text.isTextual()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(text.asText());
                }
            }
        }
        return sb.toString();
    }
}

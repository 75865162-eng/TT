package com.wimoor.ai.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wimoor.ai.skill.AiModelSettings;
import com.wimoor.ai.skill.LlmClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 真实 LLM 客户端：JDK 内置 HttpClient，支持 chat_completions 与 responses 两种 wireApi。
 * 无第三方 SDK 依赖；apiKey 只放在请求头，绝不写入日志或返回值。
 * 由 ai.persistence=jdbc（或 ai.llm.enabled=true）装配；默认仍用 LlmClient.InMemory。
 */
public final class HttpLlmClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;

    public HttpLlmClient() { this(Duration.ofSeconds(60)); }

    public HttpLlmClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String call(AiModelSettings settings, String system, String user) {
        if (settings == null || !settings.hasApiKey() || settings.getEndpoint() == null || settings.getEndpoint().isEmpty()) {
            throw new IllegalStateException("LLM_NOT_CONFIGURED");
        }
        String body = settings.isChatCompletions()
                ? chatCompletionsBody(settings, system, user)
                : responsesBody(settings, system, user);
        HttpRequest req = HttpRequest.newBuilder(URI.create(settings.getEndpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + settings.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("LLM_HTTP_" + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            return extractText(root, settings.isChatCompletions());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("LLM_IO_ERROR", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM_INTERRUPTED", e);
        }
    }

    private String chatCompletionsBody(AiModelSettings s, String system, String user) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", s.getModel());
        ArrayNode msgs = root.putArray("messages");
        ObjectNode sys = msgs.addObject(); sys.put("role", "system"); sys.put("content", system == null ? "" : system);
        ObjectNode usr = msgs.addObject(); usr.put("role", "user"); usr.put("content", user == null ? "" : user);
        return root.toString();
    }

    private String responsesBody(AiModelSettings s, String system, String user) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", s.getModel());
        root.put("instructions", system == null ? "" : system);
        root.put("input", user == null ? "" : user);
        return root.toString();
    }

    private String extractText(JsonNode root, boolean chatCompletions) {
        if (chatCompletions) {
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("message").path("content").asText("");
            }
        } else {
            JsonNode out = root.path("output");
            if (out.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : out) {
                    JsonNode content = item.path("content");
                    if (content.isArray()) for (JsonNode c : content) {
                        if (c.has("text")) sb.append(c.path("text").asText());
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
            if (root.has("output_text")) return root.path("output_text").asText("");
        }
        return "";
    }
}

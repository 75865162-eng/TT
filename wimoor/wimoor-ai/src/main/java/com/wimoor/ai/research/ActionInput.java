package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 外部动作规范化（移植自 src/lib/selection-research/action-input.ts）。
 * MCP 客户端可能把 JSON 字段编码成字符串：在记录与指纹之前只解码一次；从不解码 provider 响应。
 */
public final class ActionInput {

    private static final Set<String> ALLOWED_KEYS = Set.of("type", "tool", "arguments", "result");
    private static final int MAX_JSON_BYTES = 64_000;

    private ActionInput() {}

    @SuppressWarnings("unchecked")
    public static ResearchTypes.Action normalizeResearchAction(Object value) {
        if (!(value instanceof Map)) throw new ResearchError("INVALID_EXTERNAL_RESEARCH_ACTION");
        Map<String, Object> input = (Map<String, Object>) value;
        String type = input.get("type") == null ? null : String.valueOf(input.get("type"));
        if (!Set.of("call", "complete").contains(type)
                || input.keySet().stream().anyMatch(k -> !ALLOWED_KEYS.contains(k)))
            throw new ResearchError("INVALID_EXTERNAL_RESEARCH_ACTION");
        boolean call = "call".equals(type);
        if (call && (!(input.get("tool") instanceof String) || ((String) input.get("tool")).strip().isEmpty()
                || input.containsKey("result"))
                || !call && (input.containsKey("tool") || input.containsKey("arguments")))
            throw new ResearchError("INVALID_EXTERNAL_RESEARCH_ACTION");
        String key = call ? "arguments" : "result";
        Object payload = input.get(key);
        if (payload instanceof String) {
            String text = (String) payload;
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
                throw new ResearchError("RESEARCH_ACTION_JSON_TOO_LARGE");
            try {
                payload = ResearchSchema.MAPPER.readValue(text, Object.class);
            } catch (Exception e) {
                throw new ResearchError("RESEARCH_ACTION_JSON_INVALID", e);
            }
        }
        if (!(payload instanceof Map)) throw new ResearchError("RESEARCH_ACTION_OBJECT_REQUIRED");
        ResearchSchema.rejectCredentials(payload);
        ResearchTypes.Action action = new ResearchTypes.Action();
        action.type = type;
        if (call) {
            action.tool = (String) input.get("tool");
            action.arguments = ResearchSchema.MAPPER.valueToTree(payload);
        } else {
            try {
                action.result = ResearchSchema.MAPPER.treeToValue(
                        ResearchSchema.MAPPER.valueToTree(payload), ResearchTypes.StageResult.class);
            } catch (Exception e) {
                throw new ResearchError("INVALID_EXTERNAL_RESEARCH_ACTION", e);
            }
        }
        return action;
    }

    /** 把 call 动作的 payload 保留为 ObjectNode，供指纹计算与工具参数校验复用。 */
    public static ObjectNode arguments(ResearchTypes.Action action) {
        if (action.arguments == null) return ResearchSchema.MAPPER.createObjectNode();
        return action.arguments.isObject() ? (ObjectNode) action.arguments
                : ResearchSchema.MAPPER.createObjectNode();
    }
}

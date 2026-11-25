package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * JSON Schema 的保守子集校验与稳定序列化（移植自 src/lib/selection-research/schema.ts）。
 * 只忽略对象键顺序，保留全部值与数组顺序；不支持的关键字一律 fail-closed。
 */
public final class ResearchSchema {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SUPPORTED = Set.of(
            "type", "properties", "required", "additionalProperties", "items", "enum", "const",
            "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "description",
            "title", "default", "$schema", "format", "anyOf", "allOf", "if", "then", "else",
            "dependentRequired", "pattern", "uniqueItems", "examples");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final Pattern DIAGNOSTIC_PATH = Pattern.compile(
            "^SCHEMA_(?:TYPE|REQUIRED|ENUM|RANGE|LENGTH|ITEMS|PATTERN|DATE|UNIQUE_ITEMS):(\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+\\])*)$");
    private static final Pattern CREDENTIAL_KEY = Pattern.compile(
            "^(?:authorization|cookie|password|secret[\\w-]*|(?:api|access|refresh|mcp)[_-]?(?:key|token)|credentials|headers)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("\\bBearer\\s+\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_SECRET = Pattern.compile("[?&](?:api[_-]?key|token|secret|password)=", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private ResearchSchema() {}

    /** 对象键字典序排列的稳定 JSON；用于工具参数去重和授权摘要。 */
    public static String stableJson(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(sorted(value));
        } catch (Exception e) {
            throw new ResearchError("SCHEMA_SERIALIZATION_FAILED", e);
        }
    }

    public static String stableJson(Object value) {
        return stableJson(MAPPER.valueToTree(value));
    }

    private static JsonNode sorted(JsonNode value) {
        if (value == null) return MAPPER.nullNode();
        if (value.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode child : value) out.add(sorted(child));
            return MAPPER.valueToTree(out);
        }
        if (value.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = value.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), sorted(e.getValue()));
            }
            ObjectNode node = MAPPER.createObjectNode();
            sorted.forEach(node::set);
            return node;
        }
        return value.deepCopy();
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new ResearchError("SCHEMA_INVALID_JSON", e);
        }
    }

    /** 对 schema（对象或布尔）校验 value；strictProperties=false 用于 anyOf/allOf 分支。 */
    public static void validate(JsonNode schema, JsonNode value) {
        validate(schema, value, "$", 0, true);
    }

    public static void validate(JsonNode schema, JsonNode value, String at, int depth, boolean strictProperties) {
        if (schema == null) throw new ResearchError("SCHEMA_BRANCH_INVALID");
        if (schema.isBoolean()) {
            if (!schema.booleanValue()) throw new ResearchError("SCHEMA_FORBIDDEN:" + at);
            return;
        }
        if (!schema.isObject()) throw new ResearchError("SCHEMA_BRANCH_INVALID");
        if (depth > 20) throw new ResearchError("SCHEMA_DEPTH");
        Iterator<String> keys = schema.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!SUPPORTED.contains(key)) throw new ResearchError("SCHEMA_UNSUPPORTED:" + key);
        }
        java.util.function.Predicate<JsonNode> matches = branch -> {
            if (branch == null || (!branch.isObject() && !branch.isBoolean()))
                throw new ResearchError("SCHEMA_BRANCH_INVALID");
            try {
                validate(branch, value, at, depth + 1, false);
                return true;
            } catch (ResearchError error) {
                String code = error.code();
                if (code.startsWith("SCHEMA_UNSUPPORTED") || code.startsWith("SCHEMA_DEPTH") || code.startsWith("SCHEMA_BRANCH_INVALID"))
                    throw error;
                return false;
            }
        };
        JsonNode anyOf = schema.get("anyOf");
        if (anyOf != null && anyOf.isArray()) {
            boolean ok = false;
            for (JsonNode branch : anyOf) if (matches.test(branch)) { ok = true; break; }
            if (!ok) throw new ResearchError("SCHEMA_ANY_OF:" + at);
        }
        JsonNode allOf = schema.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode branch : allOf) {
                if (!branch.isObject() && !branch.isBoolean()) throw new ResearchError("SCHEMA_BRANCH_INVALID");
                validate(branch, value, at, depth + 1, false);
            }
        }
        JsonNode ifBranch = schema.get("if");
        if (ifBranch != null && !ifBranch.isNull()) {
            boolean ok = matches.test(ifBranch);
            JsonNode branch = ok ? schema.get("then") : schema.get("else");
            if (branch != null && !branch.isNull()) {
                if (!branch.isObject() && !branch.isBoolean()) throw new ResearchError("SCHEMA_BRANCH_INVALID");
                validate(branch, value, at, depth + 1, false);
            }
        }
        JsonNode typeNode = schema.get("type");
        if (typeNode != null && typeNode.isArray()) {
            if (typeNode.isEmpty()) throw new ResearchError("SCHEMA_TYPE_UNSUPPORTED");
            for (JsonNode t : typeNode) if (!TYPES.contains(t.asText())) throw new ResearchError("SCHEMA_TYPE_UNSUPPORTED");
            for (JsonNode t : typeNode) {
                ObjectNode variant = ((ObjectNode) schema).deepCopy();
                variant.put("type", t.asText());
                try {
                    validate(variant, value, at, depth + 1, strictProperties);
                    return;
                } catch (ResearchError error) {
                    String code = error.code();
                    if (code.startsWith("SCHEMA_UNSUPPORTED") || code.startsWith("SCHEMA_DEPTH")) throw error;
                }
            }
            throw new ResearchError("SCHEMA_TYPE:" + at);
        }
        String type = typeNode == null || typeNode.isNull() ? null : typeNode.asText();
        if (type != null && !TYPES.contains(type)) throw new ResearchError("SCHEMA_TYPE_UNSUPPORTED");
        if (!matchesType(type, value)) throw new ResearchError("SCHEMA_TYPE:" + at);

        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean ok = false;
            for (JsonNode item : enumNode) if (stableJson(item).equals(stableJson(value))) { ok = true; break; }
            if (!ok) throw new ResearchError("SCHEMA_ENUM:" + at);
        }
        JsonNode constNode = schema.get("const");
        if (constNode != null && !stableJson(value).equals(stableJson(constNode))) throw new ResearchError("SCHEMA_CONST:" + at);
        if (value.isNumber()) {
            JsonNode min = schema.get("minimum"), max = schema.get("maximum");
            if (min != null && min.isNumber() && value.decimalValue().compareTo(min.decimalValue()) < 0) throw new ResearchError("SCHEMA_RANGE:" + at);
            if (max != null && max.isNumber() && value.decimalValue().compareTo(max.decimalValue()) > 0) throw new ResearchError("SCHEMA_RANGE:" + at);
        }
        if (value.isTextual()) {
            JsonNode format = schema.get("format");
            if (format != null && !format.isNull()) {
                if (!"date".equals(format.asText())) throw new ResearchError("SCHEMA_FORMAT_UNSUPPORTED");
                String text = value.asText();
                if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) throw new ResearchError("SCHEMA_DATE:" + at);
                try {
                    LocalDate d = LocalDate.parse(text, DATE);
                    if (!DATE.format(d).equals(text)) throw new ResearchError("SCHEMA_DATE:" + at);
                } catch (Exception e) {
                    throw new ResearchError("SCHEMA_DATE:" + at);
                }
            }
            JsonNode pattern = schema.get("pattern");
            if (pattern != null && pattern.isTextual() && !Pattern.compile(pattern.asText()).matcher(value.asText()).find())
                throw new ResearchError("SCHEMA_PATTERN:" + at);
            JsonNode minLength = schema.get("minLength"), maxLength = schema.get("maxLength");
            if (minLength != null && minLength.isNumber() && value.asText().length() < minLength.asInt()) throw new ResearchError("SCHEMA_LENGTH:" + at);
            if (maxLength != null && maxLength.isNumber() && value.asText().length() > maxLength.asInt()) throw new ResearchError("SCHEMA_LENGTH:" + at);
        }
        if (value.isArray()) {
            JsonNode unique = schema.get("uniqueItems");
            if (unique != null && unique.booleanValue()) {
                Set<String> seen = new java.util.HashSet<>();
                for (JsonNode item : value) if (!seen.add(stableJson(item))) throw new ResearchError("SCHEMA_UNIQUE_ITEMS:" + at);
            }
            JsonNode minItems = schema.get("minItems"), maxItems = schema.get("maxItems");
            if (minItems != null && minItems.isNumber() && value.size() < minItems.asInt()) throw new ResearchError("SCHEMA_ITEMS:" + at);
            if (maxItems != null && maxItems.isNumber() && value.size() > maxItems.asInt()) throw new ResearchError("SCHEMA_ITEMS:" + at);
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                for (int i = 0; i < value.size(); i++) validate(items, value.get(i), at + "[" + i + "]", depth + 1, true);
            }
        }
        if (value.isObject()) {
            JsonNode properties = schema.get("properties");
            ObjectNode props = properties != null && properties.isObject() ? (ObjectNode) properties : MAPPER.createObjectNode();
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode key : required) {
                    if (key.isTextual() && value.get(key.asText()) == null)
                        throw new ResearchError("SCHEMA_REQUIRED:" + at + "." + key.asText());
                }
            }
            JsonNode dependent = schema.get("dependentRequired");
            if (dependent != null && dependent.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = dependent.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (value.has(e.getKey()) && e.getValue().isArray()) {
                        for (JsonNode dep : e.getValue()) {
                            if (dep.isTextual() && !value.has(dep.asText()))
                                throw new ResearchError("SCHEMA_REQUIRED:" + at + "." + dep.asText());
                        }
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> it = value.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                if (!props.has(key)) {
                    JsonNode additional = schema.get("additionalProperties");
                    if (strictProperties || (additional != null && additional.isBoolean() && !additional.booleanValue()))
                        throw new ResearchError("SCHEMA_UNKNOWN:" + at + "." + key);
                    continue;
                }
                JsonNode spec = props.get(key);
                if (spec.isBoolean() && !spec.booleanValue()) throw new ResearchError("SCHEMA_FORBIDDEN:" + at + "." + key);
                if (spec.isBoolean()) continue;
                if (!spec.isObject()) throw new ResearchError("SCHEMA_UNKNOWN:" + at + "." + key);
                validate(spec, e.getValue(), at + "." + key, depth + 1, true);
            }
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        if (type == null) return true;
        switch (type) {
            case "object": return value.isObject();
            case "array": return value.isArray();
            case "string": return value.isTextual();
            case "boolean": return value.isBoolean();
            case "null": return value.isNull();
            case "number": return value.isNumber();
            case "integer": return value.isIntegralNumber() && value.canConvertToLong() && Math.abs(value.longValue()) <= MAX_SAFE_INTEGER;
            default: return false;
        }
    }

    /** 只把限定路径（最多160字符）写入诊断；绝不回显被拒的参数值或 provider 错误。 */
    public static String toolArgumentPath(ResearchError error) {
        java.util.regex.Matcher m = DIAGNOSTIC_PATH.matcher(error.code());
        if (!m.matches()) return null;
        String path = m.group(1);
        return path.length() <= 160 ? path : null;
    }

    /** 递归拒绝凭证字段名、Bearer/URL 密钥以及字符串内嵌 JSON 中的凭证。 */
    public static void rejectCredentials(Object value) {
        if (value == null) return;
        // Run 中可能含 ResearchCharge（无 getter），用带自定义序列化器的 mapper 保证可遍历。
        rejectCredentialsNode(value instanceof JsonNode ? (JsonNode) value
                : ResearchJson.MAPPER.valueToTree(value), new ArrayDeque<>());
    }

    public static void rejectCredentials(JsonNode value) {
        rejectCredentialsNode(value, new ArrayDeque<>());
    }

    private static void rejectCredentialsNode(JsonNode value, Deque<String> path) {
        if (value == null || value.isNull()) return;
        if (value.isArray()) {
            for (JsonNode child : value) rejectCredentialsNode(child, path);
        } else if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = value.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (CREDENTIAL_KEY.matcher(e.getKey()).matches()) throw new ResearchError("CREDENTIAL_FIELD_REJECTED");
                rejectCredentialsNode(e.getValue(), path);
            }
        } else if (value.isTextual()) {
            String text = value.asText();
            if (BEARER.matcher(text).find() || URL_SECRET.matcher(text).find()) throw new ResearchError("CREDENTIAL_VALUE_REJECTED");
            if (text.stripLeading().startsWith("[") || text.stripLeading().startsWith("{")) {
                try {
                    JsonNode parsed = MAPPER.readTree(text);
                    if (parsed != null) rejectCredentialsNode(parsed, path);
                } catch (Exception ignored) {
                    // 非 JSON 文本按普通字符串处理
                }
            }
        }
    }
}

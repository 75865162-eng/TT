package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.wimoor.ai.lili.Ports.ToolNotDispatchedException;

/**
 * 移植辅助类：对应 @/lib/selection-research/schema 的 rejectCredentials / validate。
 * 参数校验和凭证字段拦截是「真实逻辑」，在工具分发前强制执行。
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    private static final Pattern CREDENTIAL_KEY = Pattern.compile(
        "(?i).*(token|secret|password|api[_-]?key|authorization|credential|cookie|private[_-]?key).*");

    /** rejectCredentials：输入或结果中携带凭证字段即拒绝分发。 */
    @SuppressWarnings("unchecked")
    public static void rejectCredentials(Object value) {
        if (value instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (CREDENTIAL_KEY.matcher(e.getKey()).matches())
                    throw new ToolNotDispatchedException("CREDENTIAL_FIELD_REJECTED");
                rejectCredentials(e.getValue());
            }
        } else if (value instanceof List) {
            for (Object item : (List<Object>) value) rejectCredentials(item);
        }
    }

    /** validate：按 JSON Schema 子集校验，失败抛 SCHEMA_* 错误。 */
    @SuppressWarnings("unchecked")
    public static void validate(Map<String, Object> schema, Object input) {
        if (schema == null) return;
        String type = JsonSupport.str(schema.get("type"));
        if ("object".equals(type)) {
            if (!(input instanceof Map)) throw new IllegalArgumentException("SCHEMA_TYPE_MISMATCH: object");
            Map<String, Object> values = (Map<String, Object>) input;
            List<Object> required = JsonSupport.arr(schema.get("required"));
            if (required != null) for (Object req : required) {
                if (!(req instanceof String)) continue;
                if (!values.containsKey(req) || values.get(req) == null) throw new IllegalArgumentException("SCHEMA_REQUIRED:" + req);
            }
            Map<String, Object> props = JsonSupport.obj(schema.get("properties"));
            boolean additional = !Boolean.FALSE.equals(schema.get("additionalProperties"));
            for (Map.Entry<String, Object> e : values.entrySet()) {
                Object spec = props == null ? null : props.get(e.getKey());
                if (spec == null) {
                    if (!additional) throw new IllegalArgumentException("SCHEMA_ADDITIONAL_PROPERTY:" + e.getKey());
                    continue;
                }
                check(JsonSupport.obj(spec), e.getKey(), e.getValue());
            }
            return;
        }
        check(schema, "value", input);
    }

    private static void check(Map<String, Object> spec, String key, Object value) {
        String type = JsonSupport.str(spec.get("type"));
        if (type != null && !matchesType(type, value)) throw new IllegalArgumentException("SCHEMA_TYPE_MISMATCH:" + key);
        if (value instanceof String) {
            String s = (String) value;
            Integer min = intOf(spec.get("minLength"));
            Integer max = intOf(spec.get("maxLength"));
            if (min != null && s.length() < min) throw new IllegalArgumentException("SCHEMA_MIN_LENGTH:" + key);
            if (max != null && s.length() > max) throw new IllegalArgumentException("SCHEMA_MAX_LENGTH:" + key);
            String pattern = JsonSupport.str(spec.get("pattern"));
            if (pattern != null && !Pattern.compile(pattern).matcher(s).find())
                throw new IllegalArgumentException("SCHEMA_PATTERN:" + key);
        }
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            Number min = spec.get("minimum") instanceof Number ? (Number) spec.get("minimum") : null;
            Number max = spec.get("maximum") instanceof Number ? (Number) spec.get("maximum") : null;
            if (min != null && n < min.doubleValue()) throw new IllegalArgumentException("SCHEMA_MINIMUM:" + key);
            if (max != null && n > max.doubleValue()) throw new IllegalArgumentException("SCHEMA_MAXIMUM:" + key);
        }
        if (spec.containsKey("enum") && spec.get("enum") instanceof List) {
            boolean found = false;
            for (Object allowed : (List<Object>) spec.get("enum")) if (allowed.equals(value)) { found = true; break; }
            if (!found) throw new IllegalArgumentException("SCHEMA_ENUM:" + key);
        }
    }

    private static boolean matchesType(String type, Object value) {
        switch (type) {
            case "string": return value instanceof String;
            case "integer": return value instanceof Number && ((Number) value).doubleValue() == Math.rint(((Number) value).doubleValue());
            case "number": return value instanceof Number;
            case "boolean": return value instanceof Boolean;
            case "array": return value instanceof List;
            case "object": return value instanceof Map;
            default: return true;
        }
    }

    private static Integer intOf(Object v) { return v instanceof Number ? ((Number) v).intValue() : null; }

    /** tool-execution.ts 的 validateToolInput 使用的简化类型检查（返回错误列表，不抛异常）。 */
    public static List<String> validateToolInput(Catalog.InputSchema schema, Object input) {
        List<String> errors = new ArrayList<>();
        if (!(input instanceof Map)) { errors.add("input must be an object"); return errors; }
        Map<String, Object> values = JsonSupport.obj(input);
        for (String key : schema.required()) {
            Object v = values.get(key);
            if (v == null || "".equals(v)) errors.add(key + " is required");
        }
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String key = e.getKey();
            Object spec = schema.item(key);
            if (spec == null) { errors.add(key + " is not a defined parameter"); continue; }
            Map<String, Object> prop = JsonSupport.obj(spec);
            String type = JsonSupport.str(prop.get("type"));
            boolean valid = type == null || matchesType(type, e.getValue());
            if (!valid) errors.add(key + " must be " + type);
            List<Object> en = JsonSupport.arr(prop.get("enum"));
            if (en != null && !en.contains(e.getValue())) errors.add(key + " is not an allowed value");
            if (e.getValue() instanceof String && ((String) e.getValue()).length() > 4000) errors.add(key + " is too long");
        }
        return errors;
    }
}

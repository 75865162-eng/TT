package com.wimoor.ai.lili;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移植辅助类：对应各 TS 源文件中的 JSON.parse / JSON.stringify 与 JSON 资源导入。
 * 仅使用 JDK 标准库实现递归下降解析，避免引入新的 Maven 依赖。
 * stableStringify 用于 runtime-tools.ts 中 stableJson(tool.inputSchema) 的 Schema 等价比较。
 */
public final class JsonSupport {

    private JsonSupport() {}

    /** 读取 classpath 上的 shipped JSON 资源（catalog.ts 的 import rawXxx from "@/data/lili/*.json"）。 */
    public static String resource(String name) {
        try (InputStream in = JsonSupport.class.getResourceAsStream("/lili/" + name)) {
            if (in == null) throw new IllegalStateException("LILI_RESOURCE_MISSING:" + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("LILI_RESOURCE_UNREADABLE:" + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) {
        Object value = parse(text);
        if (!(value instanceof List)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) value;
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.value();
        p.ws();
        if (p.i != text.length()) throw new IllegalArgumentException("Invalid JSON at " + p.i);
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, false);
        return sb.toString();
    }

    /** 键排序的确定性序列化，等价于 selection-research/schema 的 stableJson。 */
    public static String stableStringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder sb, boolean stable) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof String) { quote((String) value, sb); return; }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) { sb.append(value); return; }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d) && d == Math.rint(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) sb.append((long) d);
            else sb.append(d);
            return;
        }
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            List<String> keys = new ArrayList<>(map.keySet());
            if (stable) Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                quote(k, sb);
                sb.append(':');
                write(map.get(k), sb, stable);
            }
            sb.append('}');
            return;
        }
        if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                write(item, sb, stable);
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** 类型安全的读取辅助，模拟 TS 的 as Record&lt;string, unknown&gt; 访问。 */
    public static Object get(Map<String, Object> map, String key) { return map == null ? null : map.get(key); }
    public static String str(Object v) { return v instanceof String ? (String) v : null; }
    public static Number num(Object v) { return v instanceof Number ? (Number) v : null; }
    public static Boolean bool(Object v) { return v instanceof Boolean ? (Boolean) v : null; }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) { return v instanceof Map ? (Map<String, Object>) v : null; }
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) { return v instanceof List ? (List<Object>) v : null; }

    /** 与 TS Object.hasOwn(obj, key) 等价。 */
    public static boolean has(Map<String, Object> map, String key) { return map != null && map.containsKey(key); }

    /** List<Object> 转 List<String>，非字符串元素跳过。 */
    public static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) for (Object v : (List<Object>) value) if (v instanceof String) out.add((String) v);
        return out;
    }

    /** 深拷贝可变 Map/List，避免 shipped 定义被调用方改写。 */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) value).forEach((k, v) -> out.put(k, copy(v)));
            return (T) out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object v : (List<Object>) value) out.add(copy(v));
            return (T) out;
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { lit("true"); return Boolean.TRUE; }
            if (c == 'f') { lit("false"); return Boolean.FALSE; }
            if (c == 'n') { lit("null"); return null; }
            return number();
        }

        private void lit(String word) {
            if (!s.startsWith(word, i)) throw new IllegalArgumentException("Invalid JSON literal at " + i);
            i += word.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                ws();
                String key = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException("Expected ':' at " + i);
                i++;
                map.put(key, value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON object");
                char c = s.charAt(i++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException("Expected ',' at " + (i - 1));
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON array");
                char c = s.charAt(i++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("Expected ',' at " + (i - 1));
            }
        }

        private String string() {
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("Expected string at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new IllegalArgumentException("Bad escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("Bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("Bad escape: " + e);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Number number() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean decimal = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) { i++; continue; }
                if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { decimal = true; i++; continue; }
                break;
            }
            if (start == i) throw new IllegalArgumentException("Expected number at " + i);
            String token = s.substring(start, i);
            try {
                return decimal ? Double.valueOf(token) : Long.valueOf(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid number: " + token, ex);
            }
        }
    }
}

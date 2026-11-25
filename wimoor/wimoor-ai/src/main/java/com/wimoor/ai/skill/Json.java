package com.wimoor.ai.skill;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写工具，供移植后的 Java 代码解析模型输出的 JSON（action / 结构化报告）。
 * 移植说明：TypeScript 原代码使用全局 {@code JSON.parse/stringify}；本模块 pom 只依赖
 * spring-boot-starter-web，为保持纯 JDK 可编译且行为确定，这里实现等价的最小子集。
 * 支持 object/array/string/number/boolean/null，字符串转义与 Unicode 完整处理。
 */
public final class Json {

    private Json() {}

    /** 把 JSON 文本解析为 Map/List/String/Long/BigDecimal/Boolean/null。非法输入抛 IllegalArgumentException。 */
    public static Object parse(String text) {
        if (text == null) return null;
        Parser p = new Parser(text);
        Object value = p.value();
        p.skipWhitespace();
        if (!p.eof()) throw new IllegalArgumentException("JSON_TRAILING_CONTENT");
        return value;
    }

    /** 解析并强制为对象；非对象或空返回空 Map。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** 等价 JSON.stringify：保持 Map 插入顺序，null 输出为 {@code null}。 */
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) { writeString((String) value, out); return; }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof BigDecimal || value instanceof Double || value instanceof Float
                || value instanceof Short || value instanceof Byte) {
            out.append(value.toString()); return;
        }
        if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            out.append('[');
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) {
                if (i > 0) out.append(',');
                write(java.lang.reflect.Array.get(value, i), out);
            }
            out.append(']');
            return;
        }
        // 未知类型按文本处理，与 JS 的 String(value) 行为接近。
        writeString(value.toString(), out);
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String src;
        private int pos;
        Parser(String src) { this.src = src; }

        boolean eof() { return pos >= src.length(); }
        void skipWhitespace() {
            while (!eof()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }
        private char peek() {
            if (eof()) throw new IllegalArgumentException("JSON_UNEXPECTED_END");
            return src.charAt(pos);
        }
        private void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("JSON_EXPECTED_" + c);
            pos++;
        }

        Object value() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default: return number();
            }
        }

        private void literal(String word) {
            if (!src.startsWith(word, pos)) throw new IllegalArgumentException("JSON_INVALID_LITERAL");
            pos += word.length();
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                char c = peek();
                if (c == '}') { pos++; return map; }
                if (c != ',') throw new IllegalArgumentException("JSON_EXPECTED_COMMA");
                pos++;
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                skipWhitespace();
                char c = peek();
                if (c == ']') { pos++; return list; }
                if (c != ',') throw new IllegalArgumentException("JSON_EXPECTED_COMMA");
                pos++;
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') return out.toString();
                if (c == '\\') {
                    char e = peek();
                    pos++;
                    switch (e) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (pos + 4 > src.length()) throw new IllegalArgumentException("JSON_BAD_UNICODE");
                            out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new IllegalArgumentException("JSON_BAD_ESCAPE");
                    }
                } else {
                    if (c < 0x20) throw new IllegalArgumentException("JSON_CONTROL_CHAR");
                    out.append(c);
                }
            }
        }

        private Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (!eof() && Character.isDigit(src.charAt(pos))) pos++;
            boolean decimal = false;
            if (!eof() && src.charAt(pos) == '.') {
                decimal = true; pos++;
                while (!eof() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (!eof() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                decimal = true; pos++;
                if (!eof() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (!eof() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (start == pos) throw new IllegalArgumentException("JSON_INVALID_NUMBER");
            String raw = src.substring(start, pos);
            try {
                if (decimal) return new BigDecimal(raw);
                long l = Long.parseLong(raw);
                return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Object) (int) l : (Object) l;
            } catch (NumberFormatException ex) {
                return new BigDecimal(raw);
            }
        }
    }
}

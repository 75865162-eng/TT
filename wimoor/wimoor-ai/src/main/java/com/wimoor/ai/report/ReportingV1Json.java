package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Ported from {@code amazon-ads/reporting-v1-json.ts}.
 *
 * <p>Bounded GZIP_JSON syntax reader. It preserves exact number lexemes in
 * {@link JsonNumber} (so decimal precision is never rounded by a JSON parser) and
 * rejects duplicate decoded object keys. Limits are hard ceilings: a caller may
 * pass smaller values but never larger ones.
 */
public final class ReportingV1Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    private ReportingV1Json() {}

    /** Number wrapper preserving the original source lexeme. */
    public static final class JsonNumber {
        public final String source;

        public JsonNumber(String source) { this.source = source; }

        @Override
        public String toString() { return source; }

        @Override
        public boolean equals(Object o) {
            return o instanceof JsonNumber && ((JsonNumber) o).source.equals(source);
        }

        @Override
        public int hashCode() { return source.hashCode(); }
    }

    public static final class Limits {
        public long compressedPartBytes = 8L * 1024 * 1024;
        public long uncompressedPartBytes = 8L * 1024 * 1024;
        public long uncompressedReportBytes = 32L * 1024 * 1024;
        public int valuesPerPart = 200_000;
        public int maxDepth = 64;
    }

    public static final Limits DEFAULT_LIMITS = new Limits();

    public static final class Parsed {
        public final Object value;
        public final long uncompressedBytes;

        Parsed(Object value, long uncompressedBytes) {
            this.value = value;
            this.uncompressedBytes = uncompressedBytes;
        }
    }

    public static Parsed parse(byte[] bytes, Limits limits) {
        Limits l = limits == null ? DEFAULT_LIMITS : limits;
        boolean badLimits = l.compressedPartBytes < 1 || l.compressedPartBytes > DEFAULT_LIMITS.compressedPartBytes
                || l.uncompressedPartBytes < 1 || l.uncompressedPartBytes > DEFAULT_LIMITS.uncompressedPartBytes
                || l.uncompressedReportBytes < 1 || l.uncompressedReportBytes > DEFAULT_LIMITS.uncompressedReportBytes
                || l.valuesPerPart < 1 || l.valuesPerPart > DEFAULT_LIMITS.valuesPerPart
                || l.maxDepth < 1 || l.maxDepth > DEFAULT_LIMITS.maxDepth;
        if (badLimits || bytes == null || bytes.length < 1 || bytes.length > l.compressedPartBytes) {
            throw new AdsQueryError("ADS_REPORT_V1_COMPRESSED_LIMIT");
        }
        byte[] decoded;
        try {
            decoded = gunzip(bytes, Math.min(l.uncompressedPartBytes, l.uncompressedReportBytes));
        } catch (IOException e) {
            throw new AdsQueryError("ADS_REPORT_V1_GZIP_INVALID_OR_LIMIT");
        }
        String source = new String(decoded, StandardCharsets.UTF_8);
        // Fatal UTF-8: Java's decoder replaces malformed input; detect via round-trip.
        if (!new String(source.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(source)) {
            throw new AdsQueryError("ADS_REPORT_V1_JSON_INVALID");
        }
        Reader reader = new Reader(source, l);
        Object value = reader.parse(0);
        reader.whitespace();
        if (reader.offset != source.length()) throw new AdsQueryError("ADS_REPORT_V1_JSON_INVALID");
        return new Parsed(value, decoded.length);
    }

    public static Parsed parse(byte[] bytes) { return parse(bytes, DEFAULT_LIMITS); }

    public static final class PartsResult {
        public final List<Parsed> parts;
        public final long uncompressedBytes;

        PartsResult(List<Parsed> parts, long uncompressedBytes) {
            this.parts = parts;
            this.uncompressedBytes = uncompressedBytes;
        }
    }

    /** Enforces a report-wide expanded-byte limit while checking each part independently. */
    public static PartsResult parseParts(List<byte[]> parts, long maxUncompressedBytes) {
        if (parts == null || parts.isEmpty() || parts.size() > 100) {
            throw new AdsQueryError("ADS_REPORT_V1_PART_COUNT_INVALID");
        }
        if (maxUncompressedBytes < 1 || maxUncompressedBytes > DEFAULT_LIMITS.uncompressedReportBytes) {
            throw new AdsQueryError("ADS_REPORT_V1_LIMIT_INVALID");
        }
        long total = 0;
        List<Parsed> results = new ArrayList<>();
        for (byte[] part : parts) {
            Parsed result = parse(part);
            total += result.uncompressedBytes;
            if (total > maxUncompressedBytes) throw new AdsQueryError("ADS_REPORT_V1_EXPANDED_REPORT_LIMIT");
            results.add(result);
        }
        return new PartsResult(results, total);
    }

    private static byte[] gunzip(byte[] bytes, long maxOutput) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxOutput) throw new AdsQueryError("ADS_REPORT_V1_GZIP_INVALID_OR_LIMIT");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static final class Reader {
        final String source;
        final Limits limits;
        int offset;
        int values;

        Reader(String source, Limits limits) {
            this.source = source;
            this.limits = limits;
        }

        void whitespace() {
            while (offset < source.length()) {
                char c = source.charAt(offset);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') offset++;
                else break;
            }
        }

        Object parse(int depth) {
            if (++values > limits.valuesPerPart || depth > limits.maxDepth) invalid();
            whitespace();
            if (offset >= source.length()) invalid();
            char c = source.charAt(offset);
            if (c == '"') return string();
            if (c == '{') {
                offset++;
                whitespace();
                Map<String, Object> result = new LinkedHashMap<>();
                if (offset < source.length() && source.charAt(offset) == '}') { offset++; return result; }
                while (offset < source.length()) {
                    if (source.charAt(offset) != '"') invalid();
                    String key = string();
                    whitespace();
                    if (result.containsKey(key)) invalid(); // duplicate decoded key
                    if (offset >= source.length() || source.charAt(offset++) != ':') invalid();
                    Object value = parse(depth + 1);
                    result.put(key, value);
                    whitespace();
                    if (offset >= source.length()) invalid();
                    char delimiter = source.charAt(offset++);
                    if (delimiter == '}') return result;
                    if (delimiter != ',') invalid();
                    whitespace();
                }
                invalid();
            }
            if (c == '[') {
                offset++;
                whitespace();
                List<Object> result = new ArrayList<>();
                if (offset < source.length() && source.charAt(offset) == ']') { offset++; return result; }
                while (offset < source.length()) {
                    result.add(parse(depth + 1));
                    whitespace();
                    if (offset >= source.length()) invalid();
                    char delimiter = source.charAt(offset++);
                    if (delimiter == ']') return result;
                    if (delimiter != ',') invalid();
                }
                invalid();
            }
            if (source.startsWith("true", offset)) { offset += 4; return Boolean.TRUE; }
            if (source.startsWith("false", offset)) { offset += 5; return Boolean.FALSE; }
            if (source.startsWith("null", offset)) { offset += 4; return null; }
            Matcher matcher = NUMBER.matcher(source);
            matcher.region(offset, source.length());
            if (!matcher.lookingAt()) invalid();
            String lexeme = matcher.group();
            if (lexeme.length() > 200) invalid();
            offset = matcher.end();
            return new JsonNumber(lexeme);
        }

        String string() {
            int start = offset++;
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '\\') { offset++; continue; }
                if (c == '"') {
                    String token = source.substring(start, offset);
                    try {
                        return MAPPER.readValue(token, String.class);
                    } catch (IOException e) {
                        invalid();
                    }
                }
            }
            invalid();
            return null; // unreachable
        }

        void invalid() { throw new AdsQueryError("ADS_REPORT_V1_JSON_INVALID"); }
    }
}

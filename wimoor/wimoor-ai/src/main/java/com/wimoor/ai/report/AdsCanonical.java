package com.wimoor.ai.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ported from the small shared helpers used by {@code report-replica-contract.ts}
 * and {@code change-contract.ts}: {@code digest}, {@code stable} and the
 * {@code amazonBusinessData} normalization entry point.
 *
 * <p>Canonical JSON sorts object keys recursively and is used for content digests
 * and fingerprint comparison, so jsonb key reordering or a different insertion
 * order can never make identical business data look changed.
 */
public final class AdsCanonical {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdsCanonical() {}

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sorts object keys recursively, preserving arrays in order. */
    @SuppressWarnings("unchecked")
    public static Object stable(Object value) {
        if (value instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), stable(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) result.add(stable(item));
            return result;
        }
        if (value instanceof Object[]) {
            List<Object> result = new ArrayList<>();
            for (Object item : (Object[]) value) result.add(stable(item));
            return result;
        }
        return value;
    }

    public static String stableJson(Object value) {
        return json(stable(value));
    }

    public static String digest(Object value) {
        return ReportScope.sha256(stableJson(value));
    }

    /** Mirrors amazonBusinessData: drop null/empty metadata, keep business fields. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> businessData(Object value) {
        if (!(value instanceof Map)) return Collections.emptyMap();
        Map<String, Object> source = (Map<String, Object>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object item = entry.getValue();
            if (item == null || "".equals(item)) continue;
            if (item instanceof BigInteger || item instanceof BigDecimal) {
                result.put(entry.getKey(), item.toString());
            } else {
                result.put(entry.getKey(), item);
            }
        }
        return result;
    }
}

package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Scope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一查询服务（对应源文件 query-service.ts）。
 * 移植了：queryKinds 枚举、limit（1-100）校验、每种查询的允许过滤字段白名单、
 * 状态枚举校验、指纹（SHA256）计算与游标编解码（base64url）、分页语义。
 * 源端按 kind 分派到不同 Prisma 模型并做关系过滤；本实现提供 QueryRepository 接口
 * 与内存实现，把过滤/游标/分页逻辑真实实现在领域层。
 */
public final class QueryService {

    /** queryKinds。 */
    public static final List<String> QUERY_KINDS = List.of(
            "inventory-valuation", "overseas-transfers", "cost-rules", "finance-drafts",
            "cost-entries", "stock-aging", "conversions", "inventory-history", "consumables",
            "warehouse-types", "warehouse-addresses", "transfer-orders", "warehouses", "areas",
            "locations", "lots", "receipts", "outbounds", "reservations", "balances", "movements",
            "transfers", "approvals", "reconciliation", "stocktakes");

    private static final Map<String, List<String>> ALLOWED = new LinkedHashMap<>();
    static {
        ALLOWED.put("inventory-valuation", List.of("warehouseId", "locationId", "lotId", "barcode", "lotCode"));
        ALLOWED.put("overseas-transfers", List.of("id", "orderId"));
        ALLOWED.put("cost-rules", List.of("id", "lotId", "status"));
        ALLOWED.put("finance-drafts", List.of("id", "commandId", "status"));
        ALLOWED.put("cost-entries", List.of("draftId", "movementId"));
        ALLOWED.put("stock-aging", List.of("warehouseId", "productRecordId"));
        ALLOWED.put("inventory-history", List.of("from", "to", "granularity", "warehouseId", "locationId", "lotId"));
        ALLOWED.put("consumables", List.of("outboundLineId", "warehouseId"));
        ALLOWED.put("warehouse-addresses", List.of("warehouseId"));
        ALLOWED.put("transfer-orders", List.of("id", "number", "status"));
        ALLOWED.put("stocktakes", List.of("id", "number", "warehouseId", "status"));
        ALLOWED.put("outbounds", List.of("id", "number", "warehouseId", "status", "productRecordId"));
        ALLOWED.put("areas", List.of("warehouseId"));
        ALLOWED.put("locations", List.of("warehouseId", "areaId"));
        ALLOWED.put("lots", List.of("productRecordId", "barcode", "lotCode"));
        ALLOWED.put("receipts", List.of("warehouseId", "id", "number", "sourceReference", "status",
                "workflowStatus", "barcode", "lotCode"));
        ALLOWED.put("balances", List.of("warehouseId", "locationId", "lotId", "barcode", "lotCode"));
        ALLOWED.put("reconciliation", List.of("warehouseId", "locationId", "lotId", "barcode", "lotCode"));
        ALLOWED.put("movements", List.of("balanceId", "sourceId", "warehouseId", "locationId", "lotId",
                "barcode", "lotCode"));
        ALLOWED.put("transfers", List.of("sourceId", "warehouseId", "locationId", "lotId", "barcode", "lotCode"));
        ALLOWED.put("reservations", List.of("balanceId", "number", "workflowStatus", "warehouseId",
                "locationId", "lotId", "barcode", "lotCode"));
        ALLOWED.put("approvals", List.of("status", "workflowStatus", "aggregateId"));
    }

    private QueryService() {}

    /** 查询参数（URLSearchParams 的领域抽象）。 */
    public static final class Params {
        private final Map<String, String> values = new LinkedHashMap<>();

        public Params set(String key, String value) {
            if (value != null) values.put(key, value);
            return this;
        }

        public String get(String key) { return values.get(key); }
        public Map<String, String> getValues() { return values; }
    }

    /** 分页结果。 */
    public static final class Page {
        private final List<Map<String, Object>> items;
        private final String nextCursor;

        public Page(List<Map<String, Object>> items, String nextCursor) {
            this.items = items;
            this.nextCursor = nextCursor;
        }

        public List<Map<String, Object>> getItems() { return items; }
        public String getNextCursor() { return nextCursor; }
    }

    /** 查询仓储接口。 */
    public interface QueryRepository {
        List<Map<String, Object>> find(String kind, Map<String, String> filters, String afterId, int limit);
        int count(String kind, Map<String, String> filters);
    }

    /** 内存实现：按 kind 存储记录并按过滤字段等值过滤、按 id 升序分页。 */
    public static final class InMemoryQueryRepository implements QueryRepository {
        private final Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();

        public void add(String kind, Map<String, Object> record) {
            records.computeIfAbsent(kind, k -> new ArrayList<>()).add(record);
        }

        @Override
        public List<Map<String, Object>> find(String kind, Map<String, String> filters,
                                              String afterId, int limit) {
            List<Map<String, Object>> source = records.getOrDefault(kind, new ArrayList<>());
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> record : source) {
                boolean ok = true;
                for (Map.Entry<String, String> filter : filters.entrySet()) {
                    if ("workflowStatus".equals(filter.getKey())) continue;
                    if (!String.valueOf(record.getOrDefault(filter.getKey(), "")).equals(filter.getValue())) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                String id = String.valueOf(record.get("id"));
                if (afterId != null && id.compareTo(afterId) <= 0) continue;
                matched.add(record);
            }
            matched.sort((a, b) -> String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id"))));
            return new ArrayList<>(matched.subList(0, Math.min(limit, matched.size())));
        }

        @Override
        public int count(String kind, Map<String, String> filters) {
            return find(kind, filters, null, Integer.MAX_VALUE).size();
        }
    }

    /** 校验并规范化查询参数，返回 {limit, filters, fingerprint, afterId}。 */
    public static Map<String, Object> prepare(Scope scope, String kind, Params params) {
        if (!QUERY_KINDS.contains(kind)) throw new WmsError("查询类型无效。", 400);
        int limit = 30;
        String rawLimit = params.get("limit");
        if (rawLimit != null) {
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (NumberFormatException e) {
                throw new WmsError("分页大小须为 1-100。", 400);
            }
        }
        if (limit < 1 || limit > 100) throw new WmsError("分页大小须为 1-100。", 400);
        Map<String, String> filters = new LinkedHashMap<>();
        for (String key : ALLOWED.getOrDefault(kind, new ArrayList<>())) {
            String value = params.get(key);
            if (value != null) filters.put(key, value);
        }
        validateFilters(kind, filters);
        List<Object> fingerprintInput = new ArrayList<>();
        fingerprintInput.add(scope.scoped());
        fingerprintInput.add(kind);
        fingerprintInput.add(filters);
        if ("approvals".equals(kind)) fingerprintInput.add(scope.getActorUserId());
        String fingerprint = sha256(WmsRules.stableJson(fingerprintInput));

        String afterId = null;
        String cursor = params.get("cursor");
        if (cursor != null) {
            try {
                String json = new String(Base64.getUrlDecoder().decode(cursor));
                Object decodedObj = parseSimpleJson(json);
                if (!(decodedObj instanceof Map)) throw new IllegalArgumentException();
                Map<?, ?> decoded = (Map<?, ?>) decodedObj;
                if (!fingerprint.equals(decoded.get("fingerprint")) || decoded.get("id") == null
                        || String.valueOf(decoded.get("time")).isEmpty()) throw new IllegalArgumentException();
                afterId = String.valueOf(decoded.get("id"));
            } catch (Exception e) {
                throw new WmsError("游标与查询范围不一致。", 400);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limit", limit);
        out.put("filters", filters);
        out.put("fingerprint", fingerprint);
        out.put("afterId", afterId);
        return out;
    }

    /** 对各 kind 的状态/文本字段做校验（源 queryWms 中的校验块）。 */
    private static void validateFilters(String kind, Map<String, String> filters) {
        if ("transfer-orders".equals(kind)) {
            for (String field : Arrays.asList("id", "number")) {
                if (filters.get(field) != null) filters.put(field, WmsRules.requireText(filters.get(field), field));
            }
            checkStatus(filters, "status", List.of("DRAFT", "PARTIALLY_SHIPPED", "IN_TRANSIT",
                    "PARTIALLY_RECEIVED", "COMPLETED", "CANCELLED"), "调拨状态无效。");
        }
        if ("stocktakes".equals(kind)) {
            for (String field : Arrays.asList("id", "number", "warehouseId")) {
                if (filters.get(field) != null) filters.put(field, WmsRules.requireText(filters.get(field), field));
            }
            checkStatus(filters, "status", List.of("DRAFT", "COUNTING", "REVIEW", "POSTED", "CANCELLED"),
                    "盘点状态无效。");
        }
        if ("outbounds".equals(kind)) {
            for (String field : Arrays.asList("id", "number", "warehouseId")) {
                if (filters.get(field) != null) filters.put(field, WmsRules.requireText(filters.get(field), field));
            }
            if (filters.get("productRecordId") != null) {
                filters.put("productRecordId", WmsRules.requireText(filters.get("productRecordId"), "商品 ID"));
            }
            checkStatus(filters, "status", List.of("DRAFT", "RELEASED", "COMPLETED", "CANCELLED"),
                    "出库单状态无效。");
        }
        if ("approvals".equals(kind)) {
            if (filters.get("aggregateId") != null) {
                filters.put("aggregateId", WmsRules.requireText(filters.get("aggregateId"), "业务单据 ID"));
            }
            checkStatus(filters, "status", List.of("PENDING", "APPROVED", "REJECTED", "CONSUMED"),
                    "审批状态无效。");
            if (filters.get("workflowStatus") != null && !ApprovalQuery.WORK_FILTERS.contains(filters.get("workflowStatus"))) {
                throw new WmsError("审批作业筛选无效。", 400);
            }
        }
        if ("receipts".equals(kind)) {
            if (filters.get("id") != null) filters.put("id", WmsRules.requireText(filters.get("id"), "收货单 ID"));
            checkStatus(filters, "status", List.of("DRAFT", "RECEIVED", "INSPECTED", "PUTAWAY", "CANCELLED"),
                    "收货状态无效。");
            if (filters.get("workflowStatus") != null && !ReceiptQuery.WORK_FILTERS.contains(filters.get("workflowStatus"))) {
                throw new WmsError("收货作业筛选无效。", 400);
            }
        }
        if ("reservations".equals(kind) && filters.get("workflowStatus") != null
                && !ReservationQuery.STATUS_FILTERS.contains(filters.get("workflowStatus"))) {
            throw new WmsError("预占作业状态无效。", 400);
        }
        for (String key : Arrays.asList("barcode", "lotCode")) {
            String value = filters.get(key);
            if (value != null && (List.of("lots", "balances", "reconciliation", "movements",
                    "transfers", "reservations", "receipts").contains(kind))) {
                filters.put(key, WmsRules.requireText(value, "barcode".equals(key) ? "条码" : "批次编码"));
            }
        }
        if ("receipts".equals(kind) || "reservations".equals(kind)) {
            for (String key : Arrays.asList("number", "sourceReference")) {
                String value = filters.get(key);
                if (value != null) filters.put(key, WmsRules.requireText(value, "number".equals(key) ? "单号" : "来源单号"));
            }
        }
    }

    private static void checkStatus(Map<String, String> filters, String key, List<String> allowed, String message) {
        String value = filters.get(key);
        if (value != null && !allowed.contains(value)) throw new WmsError(message, 400);
    }

    /** 执行查询。 */
    @SuppressWarnings("unchecked")
    public static Page query(Scope scope, String kind, Params params, QueryRepository repo) {
        Map<String, Object> prepared = prepare(scope, kind, params);
        int limit = (Integer) prepared.get("limit");
        Map<String, String> filters = (Map<String, String>) prepared.get("filters");
        String fingerprint = (String) prepared.get("fingerprint");
        String afterId = (String) prepared.get("afterId");
        List<Map<String, Object>> rows = repo.find(kind, filters, afterId, limit + 1);
        List<Map<String, Object>> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        String nextCursor = null;
        if (rows.size() > limit && !page.isEmpty()) {
            Map<String, Object> cursor = new LinkedHashMap<>();
            cursor.put("fingerprint", fingerprint);
            cursor.put("id", page.get(page.size() - 1).get("id"));
            cursor.put("time", "1970-01-01T00:00:00.000Z");
            nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    WmsRules.stableJson(cursor).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new Page(page, nextCursor);
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 极简 JSON 对象解析（仅用于游标解码，支持字符串值）。 */
    private static Object parseSimpleJson(String json) {
        String text = json.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) throw new IllegalArgumentException();
        Map<String, Object> out = new LinkedHashMap<>();
        String body = text.substring(1, text.length() - 1).trim();
        if (body.isEmpty()) return out;
        for (String pair : body.split(",")) {
            int idx = pair.indexOf(':');
            if (idx < 0) throw new IllegalArgumentException();
            String key = unquote(pair.substring(0, idx).trim());
            String value = pair.substring(idx + 1).trim();
            out.put(key, value.equals("null") ? null : unquote(value));
        }
        return out;
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}

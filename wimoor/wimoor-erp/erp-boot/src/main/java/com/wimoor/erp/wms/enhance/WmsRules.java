package com.wimoor.erp.wms.enhance;

import com.wimoor.erp.wms.enhance.WmsContracts.Action;
import com.wimoor.erp.wms.enhance.WmsContracts.BalanceSnapshot;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandInput;
import com.wimoor.erp.wms.enhance.WmsContracts.CommandPayload;
import com.wimoor.erp.wms.enhance.WmsContracts.CountLine;
import com.wimoor.erp.wms.enhance.WmsContracts.DiscrepancyLine;
import com.wimoor.erp.wms.enhance.WmsContracts.InspectLine;
import com.wimoor.erp.wms.enhance.WmsContracts.LineQuantity;
import com.wimoor.erp.wms.enhance.WmsContracts.PutawayLine;
import com.wimoor.erp.wms.enhance.WmsContracts.ValueRule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WMS 通用校验与命令规范化规则（对应源文件 rules.ts）。
 * 移植了：数量/文本/版本校验、strictKeys 未知字段检查、normalizeWmsPayload
 * 对 27 种动作的规范化与排序去重、stableJson + SHA256 请求指纹、
 * assertBalanceInvariant 余额不变量、availableQuantity 可用量计算。
 */
public final class WmsRules {

    /** rules.ts: wmsQuantityLimit。 */
    public static final int WMS_QUANTITY_LIMIT = 2_147_483_647;
    /** rules.ts: wmsMaxReceiptLines。 */
    public static final int WMS_MAX_RECEIPT_LINES = 100;
    /** rules.ts: wmsApprovalLifetimeMs。 */
    public static final long WMS_APPROVAL_LIFETIME_MS = 15L * 60 * 1000;

    private WmsRules() {}

    /** requireText(value, label, maxLength=120)：去空格、限长。 */
    public static String requireText(Object value, String label) {
        return requireText(value, label, 120);
    }

    public static String requireText(Object value, String label, int maxLength) {
        if (!(value instanceof String)) throw new WmsError.Input(label + "不能为空。");
        String result = ((String) value).trim();
        if (result.isEmpty() || result.length() > maxLength) {
            throw new WmsError.Input(label + "长度须为 1-" + maxLength + " 个字符。");
        }
        return result;
    }

    /** normalizeCode：大写编码。 */
    public static String normalizeCode(Object value, String label) {
        return requireText(value, label, 64).toUpperCase();
    }

    /** requirePositiveQuantity：1 到 2^31-1 的整数（默认标签"数量"）。 */
    public static int requirePositiveQuantity(Object value) {
        return requirePositiveQuantity(value, "数量");
    }

    /** requirePositiveQuantity：1 到 2^31-1 的整数。 */
    public static int requirePositiveQuantity(Object value, String label) {
        if (label == null) label = "数量";
        if (!(value instanceof Number)) throw new WmsError.Input(label + "必须是 1-" + WMS_QUANTITY_LIMIT + " 的整数。");
        long n = ((Number) value).longValue();
        double d = ((Number) value).doubleValue();
        if (d != Math.floor(d) || n < 1 || n > WMS_QUANTITY_LIMIT) {
            throw new WmsError.Input(label + "必须是 1-" + WMS_QUANTITY_LIMIT + " 的整数。");
        }
        return (int) n;
    }

    /** requireRevision：revision 须为 >=1 的安全整数（冲突状态 409）。 */
    public static int requireRevision(Object value) {
        if (!(value instanceof Number)) throw new WmsError.Input("revision 无效。", 409);
        long n = ((Number) value).longValue();
        double d = ((Number) value).doubleValue();
        if (d != Math.floor(d) || n < 1) throw new WmsError.Input("revision 无效。", 409);
        return (int) n;
    }

    /** strictKeys：拒绝未知字段。 */
    public static void strictKeys(Map<String, Object> value, String... allowed) {
        Set<String> allowedSet = new HashSet<>(Arrays.asList(allowed));
        for (String key : value.keySet()) {
            if (!allowedSet.contains(key)) throw new WmsError.Input("包含未知字段。");
        }
    }

    /** assertBalanceInvariant：5 个数量桶非负且不超上限，分类之和不超过实物。 */
    public static void assertBalanceInvariant(BalanceSnapshot balance) {
        int[] values = {balance.getOnHand(), balance.getPendingQc(), balance.getPendingPutaway(),
                balance.getRejected(), balance.getReserved()};
        for (int value : values) {
            if (value < 0 || value > WMS_QUANTITY_LIMIT) throw new WmsError.Conflict("库存余额越界。");
        }
        long classified = (long) balance.getPendingQc() + balance.getPendingPutaway()
                + balance.getRejected() + balance.getReserved();
        if (classified > balance.getOnHand()) throw new WmsError.Conflict("库存分类数量超过实物数量。");
    }

    /** availableQuantity = onHand − 各分类。 */
    public static int availableQuantity(BalanceSnapshot balance) {
        assertBalanceInvariant(balance);
        return balance.getOnHand() - balance.getPendingQc() - balance.getPendingPutaway()
                - balance.getRejected() - balance.getReserved();
    }

    /** normalizeWmsPayload：把原始 Map 规范化为命令负载，含排序/去重/零值处理。 */
    @SuppressWarnings("unchecked")
    public static CommandPayload normalizeWmsPayload(Object input) {
        if (!(input instanceof Map)) throw new WmsError.Input("命令内容无效。");
        Map<String, Object> value = (Map<String, Object>) input;
        Object actionRaw = value.get("action");
        Action action = Action.of(String.valueOf(actionRaw));

        switch (action) {
            case TRANSFER_ORDER_SHIP:
            case TRANSFER_ORDER_RECEIVE:
            case TRANSFER_ORDER_CANCEL:
            case TRANSFER_ORDER_DISCREPANCY:
                return normalizeTransferOrder(action, value);
            case LOCATION_FREEZE:
            case LOCATION_UNFREEZE:
                strictKeys(value, "action", "reason");
                return new CommandPayload(action, requireText(value.get("reason"), "冻结/解冻原因", 500),
                        null, null, null, null, 0, null, null, 0, null, null, null, null, null, null);
            case STOCKTAKE_START:
            case STOCKTAKE_REVIEW:
            case STOCKTAKE_CANCEL:
            case STOCKTAKE_POST:
            case CANCEL:
                strictKeys(value, "action");
                return new CommandPayload(action);
            case STOCKTAKE_COUNT: {
                strictKeys(value, "action", "lines");
                List<Object> rawLines = requireArray(value.get("lines"), "盘点明细须为 1-100 行。");
                Set<String> seen = new HashSet<>();
                List<CountLine> lines = new ArrayList<>();
                for (Object raw : rawLines) {
                    if (!(raw instanceof Map)) throw new WmsError.Input("盘点明细无效。");
                    Map<String, Object> line = (Map<String, Object>) raw;
                    strictKeys(line, "locationId", "lotId", "countedQty");
                    String locationId = requireText(line.get("locationId"), "库位 ID");
                    String lotId = requireText(line.get("lotId"), "批次 ID");
                    String key = stableJson(Arrays.asList(locationId, lotId));
                    if (!seen.add(key)) throw new WmsError.Input("同一库位批次不能重复。");
                    int countedQty = isZero(line.get("countedQty")) ? 0
                            : requirePositiveQuantity(line.get("countedQty"), "实盘数量");
                    lines.add(new CountLine(locationId, lotId, countedQty));
                }
                return new CommandPayload(action, null, null, null, null, null, 0, null, null, 0,
                        null, null, null, null, lines, null);
            }
            case RESERVE:
                strictKeys(value, "action", "number", "quantity", "outboundLineId");
                return new CommandPayload(action, null, null, null, null,
                        requireText(value.get("number"), "预占单号"),
                        requirePositiveQuantity(value.get("quantity")),
                        value.get("outboundLineId") == null ? null
                                : requireText(value.get("outboundLineId"), "出库需求明细 ID", 100),
                        null, 0, null, null, null, null, null, null);
            case CONSUMABLE_ISSUE:
                strictKeys(value, "action", "sourceBalanceId", "mainQuantity", "reason");
                return new CommandPayload(action, requireText(value.get("reason"), "发料依据", 1000),
                        null, null, null, null, 0, null,
                        requireText(value.get("sourceBalanceId"), "耗材来源余额"),
                        requirePositiveQuantity(value.get("mainQuantity"), "主商品数量"),
                        null, null, null, null, null, null);
            case ACTIVATE_COST_RULE:
            case SKU_CONVERSION_REVERSE:
            case ASSEMBLY_COMPLETE: {
                strictKeys(value, "action", "reason");
                String label = action == Action.ACTIVATE_COST_RULE ? "规则生效依据"
                        : action == Action.SKU_CONVERSION_REVERSE ? "冲销依据" : "组装完成依据";
                return new CommandPayload(action, requireText(value.get("reason"), label, 1000),
                        null, null, null, null, 0, null, null, 0, null, null, null, null, null, null);
            }
            case VALUE_COMMAND: {
                strictKeys(value, "action", "reason", "rules");
                List<Object> rawRules = requireArray(value.get("rules"), "计价规则须为 1-200 行。");
                if (rawRules.size() > 200) throw new WmsError.Input("计价规则须为 1-200 行。");
                Set<String> seen = new HashSet<>();
                List<ValueRule> rules = new ArrayList<>();
                for (Object raw : rawRules) {
                    if (!(raw instanceof Map)) throw new WmsError.Input("计价规则无效。");
                    Map<String, Object> row = (Map<String, Object>) raw;
                    strictKeys(row, "movementId", "ruleId");
                    String movementId = requireText(row.get("movementId"), "流水");
                    if (!seen.add(movementId)) throw new WmsError.Input("计价流水重复。");
                    rules.add(new ValueRule(movementId, requireText(row.get("ruleId"), "成本规则")));
                }
                rules.sort(Comparator.comparing(ValueRule::getMovementId));
                return new CommandPayload(action, requireText(value.get("reason"), "补充计价依据", 1000),
                        null, null, null, null, 0, null, null, 0, null, null, null, null, null, rules);
            }
            case SKU_CONVERT:
                strictKeys(value, "action", "targetLotId", "quantity", "reason");
                return new CommandPayload(action, requireText(value.get("reason"), "转换依据", 1000),
                        null, null, null, null, requirePositiveQuantity(value.get("quantity")), null,
                        null, 0, requireText(value.get("targetLotId"), "目标批次"), null, null,
                        null, null, null);
            case TRANSFER:
                strictKeys(value, "action", "targetLocationId", "quantity");
                return new CommandPayload(action, null, null, null, null, null,
                        requirePositiveQuantity(value.get("quantity")), null, null, 0, null,
                        requireText(value.get("targetLocationId"), "目标库位 ID", 100), null,
                        null, null, null);
            case RELEASE:
            case PICK:
            case UNPICK:
            case DISPATCH:
                strictKeys(value, "action", "quantity");
                return new CommandPayload(action, null, null, null, null, null,
                        requirePositiveQuantity(value.get("quantity")), null, null, 0, null, null,
                        null, null, null, null);
            default:
                return normalizeInventoryLines(action, value);
        }
    }

    /** RECEIVE/INSPECT/PUTAWAY/RETURN_REJECTED 的明细规范化。 */
    @SuppressWarnings("unchecked")
    private static CommandPayload normalizeInventoryLines(Action action, Map<String, Object> value) {
        strictKeys(value, "action", "lines");
        if (action != Action.RECEIVE && action != Action.INSPECT
                && action != Action.PUTAWAY && action != Action.RETURN_REJECTED) {
            throw new WmsError.Input("不支持的库存动作。");
        }
        List<Object> rawLines = requireArray(value.get("lines"),
                "明细行数须为 1-" + WMS_MAX_RECEIPT_LINES + "。");
        Set<String> seen = new HashSet<>();
        List<LineQuantity> simpleLines = new ArrayList<>();
        List<InspectLine> inspectLines = new ArrayList<>();
        List<PutawayLine> putawayLines = new ArrayList<>();
        for (Object raw : rawLines) {
            if (!(raw instanceof Map)) throw new WmsError.Input("库存明细无效。");
            Map<String, Object> line = (Map<String, Object>) raw;
            if (action == Action.INSPECT) {
                strictKeys(line, "lineId", "acceptedQty", "rejectedQty");
            } else if (action == Action.PUTAWAY) {
                strictKeys(line, "lineId", "locationId", "quantity");
            } else {
                strictKeys(line, "lineId", "quantity");
            }
            String lineId = requireText(line.get("lineId"), "明细 ID", 100);
            if (!seen.add(lineId)) throw new WmsError.Input("同一命令不能重复包含明细。", 409);
            if (action == Action.INSPECT) {
                int accepted = isZero(line.get("acceptedQty")) ? 0
                        : requirePositiveQuantity(line.get("acceptedQty"), "合格数量");
                int rejected = isZero(line.get("rejectedQty")) ? 0
                        : requirePositiveQuantity(line.get("rejectedQty"), "拒收数量");
                inspectLines.add(new InspectLine(lineId, accepted, rejected));
            } else if (action == Action.PUTAWAY) {
                putawayLines.add(new PutawayLine(lineId,
                        requireText(line.get("locationId"), "目标库位 ID", 100),
                        requirePositiveQuantity(line.get("quantity"))));
            } else {
                simpleLines.add(new LineQuantity(lineId, requirePositiveQuantity(line.get("quantity"))));
            }
        }
        return new CommandPayload(action, null, simpleLines, inspectLines, putawayLines,
                null, 0, null, null, 0, null, null, null, null, null, null);
    }

    /** 调拨动作规范化（委托给 TransferOrderRules 的纯函数）。 */
    @SuppressWarnings("unchecked")
    private static CommandPayload normalizeTransferOrder(Action action, Map<String, Object> value) {
        if (action == Action.TRANSFER_ORDER_CANCEL) {
            strictKeys(value, "action", "reason");
            return new CommandPayload(action, requireText(value.get("reason"), "取消原因", 1000),
                    null, null, null, null, 0, null, null, 0, null, null, null, null, null, null);
        }
        return TransferOrderRules.parseTransferOrderPayload(value).toCommandPayload();
    }

    private static List<Object> requireArray(Object raw, String message) {
        if (!(raw instanceof List)) throw new WmsError.Input(message);
        List<Object> list = (List<Object>) raw;
        if (list.isEmpty() || list.size() > WMS_MAX_RECEIPT_LINES) throw new WmsError.Input(message);
        return list;
    }

    private static boolean isZero(Object value) {
        return value instanceof Number && ((Number) value).longValue() == 0;
    }

    /** parseWmsCommand：校验外层字段并规范化 payload。 */
    @SuppressWarnings("unchecked")
    public static CommandInput parseWmsCommand(Object raw) {
        if (!(raw instanceof Map)) throw new WmsError.Input("命令无效。");
        Map<String, Object> value = (Map<String, Object>) raw;
        strictKeys(value, "workspaceId", "aggregateId", "expectedRevision", "idempotencyKey", "payload");
        return new CommandInput(
                requireText(value.get("workspaceId"), "工作区"),
                requireText(value.get("aggregateId"), "单据 ID"),
                requireRevision(value.get("expectedRevision")),
                requireText(value.get("idempotencyKey"), "幂等键"),
                normalizeWmsPayload(value.get("payload")));
    }

    /** rules.ts: stableJson —— 对象键排序后序列化，用于请求指纹。 */
    public static String stableJson(Object value) {
        if (value == null) return "null";
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) sb.append(',');
                sb.append(stableJson(item));
                first = false;
            }
            return sb.append(']').toString();
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) keys.add(String.valueOf(key));
            keys.sort(Comparator.naturalOrder());
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quote(keys.get(i))).append(':').append(stableJson(map.get(keys.get(i))));
            }
            return sb.append('}').toString();
        }
        if (value instanceof String) return quote((String) value);
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** hashWmsRequest：SHA256(stableJson({action, aggregateId, expectedRevision, payload}))。 */
    public static String hashWmsRequest(Action action, String aggregateId, int expectedRevision,
                                        CommandPayload payload) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("action", action.name());
        input.put("aggregateId", aggregateId);
        input.put("expectedRevision", expectedRevision);
        input.put("payload", payloadToMap(payload));
        return sha256(stableJson(input));
    }

    private static Map<String, Object> payloadToMap(CommandPayload p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", p.getAction().name());
        putIfNotNull(out, "reason", p.getReason());
        putIfNotNull(out, "lines", p.getLines());
        putIfNotNull(out, "inspectLines", p.getInspectLines());
        putIfNotNull(out, "putawayLines", p.getPutawayLines());
        putIfNotNull(out, "number", p.getNumber());
        putIfNotNull(out, "quantity", p.getQuantity() == 0 ? null : p.getQuantity());
        putIfNotNull(out, "outboundLineId", p.getOutboundLineId());
        putIfNotNull(out, "sourceBalanceId", p.getSourceBalanceId());
        putIfNotNull(out, "mainQuantity", p.getMainQuantity() == 0 ? null : p.getMainQuantity());
        putIfNotNull(out, "targetLotId", p.getTargetLotId());
        putIfNotNull(out, "targetLocationId", p.getTargetLocationId());
        putIfNotNull(out, "reference", p.getReference());
        putIfNotNull(out, "discrepancyLines", p.getDiscrepancyLines());
        putIfNotNull(out, "countLines", p.getCountLines());
        putIfNotNull(out, "valueRules", p.getValueRules());
        return out;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 供集合类型在 stableJson 中递归使用。 */
    public static String stableJson(Collection<?> value) {
        return stableJson((Object) value);
    }
}

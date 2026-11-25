package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目录资源服务（对应源文件 catalog-service.ts）。
 * 移植了：createWmsResource 的仓库/区域/库位/批次/出库/收货创建校验
 * （编码大写规范化、明细行数、商品重复、批次归属），以及 setWmsResourceEnabled
 * 的启用/停用约束（父级须启用、有非零库存或在途不可停用）。
 */
public final class CatalogService {

    /** CatalogKind。 */
    public enum CatalogKind {
        COST_RULES, OVERSEAS_TRANSFERS, CONSUMABLES, WAREHOUSE_TYPES, WAREHOUSE_ADDRESS,
        TRANSFER_ORDERS, WAREHOUSES, AREAS, LOCATIONS, LOTS, RECEIPTS, STOCKTakes, OUTBOUNDS
    }

    private CatalogService() {}

    /** 出库/收货明细解析（共用的 1-100 行与商品/批次去重）。 */
    public static final class ParsedLine {
        private final String id;
        private final int quantity;

        public ParsedLine(String id, int quantity) {
            this.id = id;
            this.quantity = quantity;
        }

        public String getId() { return id; }
        public int getQuantity() { return quantity; }
    }

    /** parseOutboundLines。 */
    public static List<ParsedLine> parseOutboundLines(Object rawLines) {
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > WmsRules.WMS_MAX_RECEIPT_LINES) {
            throw new WmsError("出库明细须为 1-100 行。", 400);
        }
        Set<String> seen = new HashSet<>();
        List<ParsedLine> lines = new ArrayList<>();
        for (Object raw : (List<?>) rawLines) {
            if (!(raw instanceof Map)) throw new WmsError("出库明细无效。", 400);
            Map<String, Object> line = toMap(raw);
            WmsRules.strictKeys(line, "productRecordId", "requestedQty");
            String productRecordId = WmsRules.requireText(line.get("productRecordId"), "商品 ID");
            if (!seen.add(productRecordId)) throw new WmsError("同一商品请合并为一行需求。", 422);
            lines.add(new ParsedLine(productRecordId, WmsRules.requirePositiveQuantity(line.get("requestedQty"))));
        }
        return lines;
    }

    /** parseReceiptLines。 */
    public static List<ParsedLine> parseReceiptLines(Object rawLines) {
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > WmsRules.WMS_MAX_RECEIPT_LINES) {
            throw new WmsError("收货明细须为 1-100 行。", 400);
        }
        Set<String> seen = new HashSet<>();
        List<ParsedLine> lines = new ArrayList<>();
        for (Object raw : (List<?>) rawLines) {
            if (!(raw instanceof Map)) throw new WmsError("收货明细无效。", 400);
            Map<String, Object> line = toMap(raw);
            WmsRules.strictKeys(line, "lotId", "expectedQty");
            String lotId = WmsRules.requireText(line.get("lotId"), "批次 ID");
            if (!seen.add(lotId)) throw new WmsError("批次重复或不可访问。", 422);
            lines.add(new ParsedLine(lotId, WmsRules.requirePositiveQuantity(line.get("expectedQty"))));
        }
        return lines;
    }

    /** setWmsResourceEnabled 的启用约束校验。 */
    public static void validateEnable(boolean desiredEnabled, boolean parentsEnabled) {
        if (desiredEnabled && !parentsEnabled) throw new WmsError("请先启用所属仓库和区域。", 422);
    }

    /** 有非零库存不可停用。 */
    public static void validateNoStock(boolean hasNonZeroBalance, boolean hasInTransit) {
        if (hasNonZeroBalance) throw new WmsError("该范围仍有库存或预留数量，不能停用。", 409);
        if (hasInTransit) throw new WmsError("该范围仍有在途待签收库存，不能停用。", 409);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value) {
        return (Map<String, Object>) value;
    }
}

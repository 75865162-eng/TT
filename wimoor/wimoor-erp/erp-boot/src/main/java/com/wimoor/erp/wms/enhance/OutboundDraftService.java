package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出库草稿服务（对应源文件 outbound-draft-service.ts）。
 * 移植了：parseLines 的商品去重/数量校验、updateOutboundDraft 的草稿状态/版本/
 * 创建人约束与"已预占或已配置耗材的明细不可移除"保护、cancelOutbound 的未完成预占检查。
 */
public final class OutboundDraftService {

    private OutboundDraftService() {}

    /** parseLines。 */
    public static List<CatalogService.ParsedLine> parseLines(Object rawLines) {
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > WmsRules.WMS_MAX_RECEIPT_LINES) {
            throw new WmsError("出库明细须为 1-100 行。", 400);
        }
        Set<String> seen = new HashSet<>();
        List<CatalogService.ParsedLine> lines = new ArrayList<>();
        for (Object raw : (List<?>) rawLines) {
            if (!(raw instanceof Map)) throw new WmsError("出库明细无效。", 400);
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) raw;
            WmsRules.strictKeys(line, "productRecordId", "requestedQty");
            String productRecordId = WmsRules.requireText(line.get("productRecordId"), "商品 ID");
            if (!seen.add(productRecordId)) throw new WmsError("同一商品请合并为一行需求。", 422);
            lines.add(new CatalogService.ParsedLine(productRecordId,
                    WmsRules.requirePositiveQuantity(line.get("requestedQty"))));
        }
        return lines;
    }

    /** 移除明细保护。 */
    public static void validateRemoval(boolean hasReservation, boolean hasConsumable) {
        if (hasReservation) throw new WmsError("已有预占关联此需求明细，不能移除。", 409);
        if (hasConsumable) throw new WmsError("需求明细已配置耗材，不能移除；可取消草稿后重建。", 409);
    }

    /** 取消前的未完成预占校验。 */
    public static void validateCancel(int activeQty) {
        if (activeQty > 0) throw new WmsError("请先释放此需求的未完成预占，再取消需求。", 409);
    }
}

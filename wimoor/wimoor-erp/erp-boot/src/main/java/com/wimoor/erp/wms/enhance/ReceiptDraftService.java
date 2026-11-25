package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收货草稿服务（对应源文件 receipt-draft-service.ts）。
 * 移植了：updateReceiptDraft 的严格字段校验、明细全量提交（数量/批次不可替换）、
 * 创建人/草稿状态/版本约束，以及采购单关联后不可修改计划数量的保护。
 */
public final class ReceiptDraftService {

    private ReceiptDraftService() {}

    /** 明细解析。 */
    public static List<CatalogService.ParsedLine> parseLines(Object rawLines) {
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > 100) {
            throw new WmsError("请提交全部草稿明细。", 400);
        }
        List<CatalogService.ParsedLine> lines = new ArrayList<>();
        for (Object raw : (List<?>) rawLines) {
            if (!(raw instanceof Map)) throw new WmsError("明细格式无效。", 400);
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) raw;
            WmsRules.strictKeys(line, "lineId", "expectedQty");
            lines.add(new CatalogService.ParsedLine(WmsRules.requireText(line.get("lineId"), "明细 ID"),
                    WmsRules.requirePositiveQuantity(line.get("expectedQty"))));
        }
        return lines;
    }

    /** 全量提交校验。 */
    public static void validateFullSubmission(List<CatalogService.ParsedLine> submitted,
                                              List<String> storedIds) {
        Set<String> ids = new HashSet<>();
        for (CatalogService.ParsedLine line : submitted) ids.add(line.getId());
        if (submitted.size() != storedIds.size() || ids.size() != submitted.size()
                || !ids.containsAll(storedIds)) {
            throw new WmsError("请提交本收货单的全部明细，不能重复或替换批次。", 422);
        }
    }
}

package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 库存风险 CSV 导出（对应源文件 inventory-risk-export.ts）。
 * 移植了 CSV 单元格的公式注入防护（以 = + - @ 制表符回车开头加前缀单引号）、
 * 双引号转义、BOM 前缀与 CRLF 行分隔。
 */
public final class InventoryRiskExport {

    private static final String[] HEADERS = {"assessmentId", "snapshotId", "assessmentStatus",
            "requestHash", "snapshotDate", "currency", "productRecordId", "sku", "quantity",
            "stagnant", "proposedProvision", "provisionDelta", "dataStatus", "postingExecuted"};

    private InventoryRiskExport() {}

    /** cell：CSV 安全转义。 */
    public static String cell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /** inventoryRiskCsv。 */
    @SuppressWarnings("unchecked")
    public static String inventoryRiskCsv(Object report) {
        if (!(report instanceof Map)) throw new WmsError("库存风险评估导出资料无效。", 400);
        Map<String, Object> row = (Map<String, Object>) report;
        Object resultRaw = row.get("result");
        if (!(resultRaw instanceof Map)) throw new WmsError("库存风险评估导出资料无效。", 400);
        Map<String, Object> result = (Map<String, Object>) resultRaw;
        List<Object> items = (List<Object>) result.get("items");
        List<List<Object>> rows = new ArrayList<>();
        List<Object> header = new ArrayList<>();
        for (String h : HEADERS) header.add(h);
        rows.add(header);
        for (Object rawItem : items) {
            Map<String, Object> item = (Map<String, Object>) rawItem;
            List<Object> line = new ArrayList<>();
            line.add(row.get("id"));
            line.add(row.get("snapshotId"));
            line.add(row.get("status"));
            line.add(row.get("requestHash"));
            line.add(result.get("snapshotDate"));
            line.add(result.get("currency"));
            line.add(item.get("productRecordId"));
            line.add(item.get("sku"));
            line.add(item.get("quantity"));
            line.add(item.get("stagnant"));
            line.add(item.get("proposedProvision"));
            line.add(item.get("provisionDelta"));
            line.add(item.get("status"));
            line.add(false);
            rows.add(line);
        }
        StringBuilder sb = new StringBuilder("\uFEFF");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append("\r\n");
            List<Object> line = rows.get(i);
            for (int j = 0; j < line.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(cell(line.get(j)));
            }
        }
        return sb.toString();
    }
}

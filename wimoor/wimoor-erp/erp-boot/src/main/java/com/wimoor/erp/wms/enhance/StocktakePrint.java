package com.wimoor.erp.wms.enhance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盘点打印（对应源文件 stocktake-print.ts）。
 * 移植了 stocktakePrintDocument 的允许列表投影（盲盘不输出账面、ID 与隐藏原始字段）、
 * renderStocktakePrint 的表头/行构造与 HTML 转义。
 */
public final class StocktakePrint {

    private StocktakePrint() {}

    /** PrintLine。 */
    public static final class PrintLine {
        private final String lotCode;
        private final String barcode;
        private final Integer countedQty;
        private final Integer baselineOnHand;

        public PrintLine(String lotCode, String barcode, Integer countedQty, Integer baselineOnHand) {
            this.lotCode = lotCode;
            this.barcode = barcode;
            this.countedQty = countedQty;
            this.baselineOnHand = baselineOnHand;
        }

        public String getLotCode() { return lotCode; }
        public String getBarcode() { return barcode; }
        public Integer getCountedQty() { return countedQty; }
        public Integer getBaselineOnHand() { return baselineOnHand; }
    }

    /** PrintLocation。 */
    public static final class PrintLocation {
        private final String code;
        private final List<PrintLine> lines;

        public PrintLocation(String code, List<PrintLine> lines) {
            this.code = code;
            this.lines = lines;
        }

        public String getCode() { return code; }
        public List<PrintLine> getLines() { return lines; }
    }

    /** StocktakePrint 文档。 */
    public static final class Document {
        private final String number;
        private final String status;
        private final String mode;
        private final boolean blind;
        private final int revision;
        private final List<PrintLocation> locations;

        public Document(String number, String status, String mode, boolean blind, int revision,
                        List<PrintLocation> locations) {
            this.number = number;
            this.status = status;
            this.mode = mode;
            this.blind = blind;
            this.revision = revision;
            this.locations = locations;
        }

        public String getNumber() { return number; }
        public String getStatus() { return status; }
        public String getMode() { return mode; }
        public boolean isBlind() { return blind; }
        public int getRevision() { return revision; }
        public List<PrintLocation> getLocations() { return locations; }
    }

    /** 渲染结果。 */
    public static final class Rendered {
        private final String html;
        private final Document document;

        public Rendered(String html, Document document) {
            this.html = html;
            this.document = document;
        }

        public String getHtml() { return html; }
        public Document getDocument() { return document; }
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : "";
    }

    /** escape：HTML 实体转义。 */
    public static String escape(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Integer asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String message, int status) {
        if (!(raw instanceof Map)) throw new WmsError(message, status);
        return (Map<String, Object>) raw;
    }

    /** stocktakePrintDocument。 */
    public static Document stocktakePrintDocument(Object raw) {
        Map<String, Object> row = object(raw, "盘点打印资料无效。", 400);
        Object rawLocations = row.get("locations");
        if (!(rawLocations instanceof List)) throw new WmsError("盘点打印须读取单据详情。", 422);
        boolean blind = Boolean.TRUE.equals(row.get("blind"));
        List<PrintLocation> locations = new ArrayList<>();
        for (Object rawLocation : (List<Object>) rawLocations) {
            Map<String, Object> location = object(rawLocation, "盘点库位资料无效。", 422);
            Object label = location.get("location");
            String code = label instanceof Map ? text(((Map<?, ?>) label).get("code")) : "";
            Object rawLines = location.get("lines");
            if (!(rawLines instanceof List)) throw new WmsError("盘点库位资料无效。", 422);
            List<PrintLine> lines = new ArrayList<>();
            for (Object rawLine : (List<Object>) rawLines) {
                Map<String, Object> line = object(rawLine, "盘点库位资料无效。", 422);
                Object lot = line.get("lot");
                String lotCode = lot instanceof Map ? text(((Map<?, ?>) lot).get("lotCode")) : "";
                String barcode = lot instanceof Map && ((Map<?, ?>) lot).get("barcode") instanceof String
                        ? (String) ((Map<?, ?>) lot).get("barcode") : null;
                Integer countedQty = asInt(line.get("countedQty"));
                Integer baseline = (!blind && line.get("baselineOnHand") instanceof Number)
                        ? asInt(line.get("baselineOnHand")) : null;
                lines.add(new PrintLine(lotCode, barcode, countedQty, baseline));
            }
            locations.add(new PrintLocation(code, lines));
        }
        return new Document(text(row.get("number")), text(row.get("status")), text(row.get("mode")),
                blind, asInt(row.get("revision")) == null ? 0 : asInt(row.get("revision")), locations);
    }

    /** renderStocktakePrint。 */
    public static Rendered renderStocktakePrint(Object raw) {
        Document document = stocktakePrintDocument(raw);
        boolean review = "REVIEW".equals(document.getStatus()) || "POSTED".equals(document.getStatus());
        List<String> headers = new ArrayList<>();
        headers.add("库位");
        headers.add("批次");
        headers.add("条码");
        if (!document.isBlind()) headers.add("账面");
        headers.add("实盘");
        if (!document.isBlind() && review) headers.add("差异");

        List<List<String>> rows = new ArrayList<>();
        for (PrintLocation location : document.getLocations()) {
            if (location.getLines().isEmpty()) {
                List<String> row = new ArrayList<>();
                row.add(location.getCode()); row.add(""); row.add("");
                if (!document.isBlind()) row.add("");
                row.add("");
                if (!document.isBlind() && review) row.add("");
                rows.add(row);
                continue;
            }
            for (PrintLine line : location.getLines()) {
                List<String> row = new ArrayList<>();
                row.add(location.getCode());
                row.add(line.getLotCode());
                row.add(line.getBarcode() == null ? "" : line.getBarcode());
                if (!document.isBlind()) row.add(line.getBaselineOnHand() == null ? "" : String.valueOf(line.getBaselineOnHand()));
                row.add(review ? (line.getCountedQty() == null ? "" : String.valueOf(line.getCountedQty())) : "");
                if (!document.isBlind() && review) {
                    row.add(line.getCountedQty() == null || line.getBaselineOnHand() == null
                            ? "" : String.valueOf(line.getCountedQty() - line.getBaselineOnHand()));
                }
                rows.add(row);
            }
        }

        StringBuilder headerHtml = new StringBuilder();
        for (String header : headers) headerHtml.append("<th>").append(escape(header)).append("</th>");
        StringBuilder bodyHtml = new StringBuilder();
        for (List<String> row : rows) {
            bodyHtml.append("<tr>");
            for (String cell : row) bodyHtml.append("<td>").append(escape(cell)).append("</td>");
            bodyHtml.append("</tr>");
        }
        String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>盘点单 "
                + escape(document.getNumber())
                + "</title><style>body{font:14px sans-serif;margin:24px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #999;padding:10px;text-align:left}td{height:26px}@media print{body{margin:8mm}thead{display:table-header-group}tr{break-inside:avoid}}</style><body><h1>盘点单 "
                + escape(document.getNumber()) + "</h1><p>状态 " + escape(document.getStatus())
                + " · 模式 " + escape(document.getMode()) + " · 版本 " + document.getRevision()
                + (document.isBlind() ? " · 盲盘：不输出账面及差异" : "")
                + "</p><p>打印内容仅为此版本作业凭据，实盘结果仍须回系统保存。盘点人：________ 复核人：________ 日期：________</p><table><thead><tr>"
                + headerHtml + "</tr></thead><tbody>" + bodyHtml + "</tbody></table></body></html>";
        return new Rendered(html, document);
    }
}

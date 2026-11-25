package com.wimoor.ai.skill;

import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import com.wimoor.ai.skill.SkillTypes.SkillReportRow;
import com.wimoor.ai.skill.SkillTypes.StructuredReport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 报告导出。移植自 {@code src/lib/skill-engine/exporter.ts}。
 * Excel 用最小 OOXML（SpreadsheetML），Word 用最小 OOXML（WordprocessingML）+ ZIP，
 * 全部基于 JDK {@link ZipOutputStream}，零新增依赖（等价原代码复用既有 xlsx/JSZip）。
 * 只导出模型真实产出的内容：rows 为空时不伪造表格，缺失值保留 NEEDS_DATA。
 */
public final class Exporter {

    public static final int MAX_ROWS = 5000;
    public static final int MAX_CELL = 32000;

    private final Clock clock;
    public Exporter() { this(Clock.systemUTC()); }
    public Exporter(Clock clock) { this.clock = clock == null ? Clock.systemUTC() : clock; }

    /** 与原 safeCell 一致：null → NEEDS_DATA，超长截断并加省略号。 */
    public static String safeCell(Object value) {
        if (value == null) return SkillTypes.NEEDS_DATA;
        String text = String.valueOf(value);
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) + "…" : text;
    }

    /** 与原 xmlEscape 一致。 */
    public static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 取前 200 行出现过的全部列键，保持首次出现顺序。 */
    public static List<String> columnKeys(List<SkillReportRow> rows) {
        Set<String> keys = new LinkedHashSet<>();
        int limit = Math.min(200, rows.size());
        for (int i = 0; i < limit; i++) {
            for (String key : rows.get(i).keySet()) keys.add(key);
        }
        return new ArrayList<>(keys);
    }

    /** 按格式分发（exportReport）。 */
    public byte[] exportReport(ExportFormat format, StructuredReport report, String fileName) {
        if (format == ExportFormat.XLSX) return exportExcel(report, fileName);
        return exportWord(report, fileName);
    }

    /** 生成 xlsx 字节：报告数据 sheet + 说明 sheet。 */
    public byte[] exportExcel(StructuredReport report, String fileName) {
        List<String> headers = columnKeys(report.getRows());
        List<List<Object>> matrix = new ArrayList<>();
        if (!headers.isEmpty()) {
            matrix.add(new ArrayList<Object>(headers));
            int limit = Math.min(MAX_ROWS, report.getRows().size());
            for (int i = 0; i < limit; i++) {
                SkillReportRow row = report.getRows().get(i);
                List<Object> line = new ArrayList<>();
                for (String key : headers) line.add(row.get(key));
                matrix.add(line);
            }
        } else {
            matrix.add(java.util.Collections.singletonList("说明"));
            matrix.add(java.util.Collections.singletonList("当前报告未包含可导出的结构化数据行。"));
        }

        StringBuilder data = new StringBuilder();
        for (int r = 0; r < matrix.size(); r++) {
            List<Object> line = matrix.get(r);
            data.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < line.size(); c++) {
                Object cell = line.get(c);
                String ref = colName(c + 1) + (r + 1);
                if (cell == null) continue;
                if (cell instanceof Number) {
                    data.append("<c r=\"").append(ref).append("\"><v>").append(cell).append("</v></c>");
                } else {
                    data.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(xmlEscape(safeCell(cell))).append("</t></is></c>");
                }
            }
            data.append("</row>");
        }
        String sheet1 = worksheet(data.toString());

        String exportedAt = Instant.now(clock).toString();
        String[][] notes = {
                {"报告标题", report.getTitle()},
                {"导出时间", exportedAt},
                {"数据说明", "数值均为模型基于已提供事实生成；未提供的事实标记 NEEDS_DATA，未做补零或估算。"},
                {"注意事项", "报告为分析建议，未执行任何店铺写入操作；写入请走主系统审批流程。"},
        };
        StringBuilder overview = new StringBuilder();
        for (int r = 0; r < notes.length; r++) {
            overview.append("<row r=\"").append(r + 1).append("\">")
                    .append(inlineCell("A" + (r + 1), notes[r][0]))
                    .append(inlineCell("B" + (r + 1), notes[r][1]))
                    .append("</row>");
        }
        String sheet2 = worksheet(overview.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", xlsxContentTypes());
            put(zip, "_rels/.rels", officeRels("xl/workbook.xml"));
            put(zip, "xl/workbook.xml", workbookXml());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
            put(zip, "xl/worksheets/sheet1.xml", sheet1);
            put(zip, "xl/worksheets/sheet2.xml", sheet2);
        } catch (IOException e) {
            throw new IllegalStateException("EXPORT_XLSX_FAILED", e);
        }
        return out.toByteArray();
    }

    /** 生成 docx 字节：标题、markdown 分段、结构化数据表、说明与免责声明。 */
    public byte[] exportWord(StructuredReport report, String title) {
        StringBuilder blocks = new StringBuilder();
        blocks.append(paragraph(report.getTitle() == null || report.getTitle().isEmpty() ? title : report.getTitle(), true));

        String markdown = report.getMarkdown() == null ? "" : report.getMarkdown();
        for (String raw : markdown.split("\\r?\\n", -1)) {
            String line = trailingTrim(raw);
            if (line.trim().isEmpty()) { blocks.append("<w:p/>"); continue; }
            if (line.matches("^#{1,6}\\s+.*$")) {
                blocks.append(paragraph(line.replaceFirst("^#{1,6}\\s+", ""), true));
            } else {
                blocks.append(paragraph(line, false));
            }
        }

        List<String> headers = columnKeys(report.getRows());
        if (!headers.isEmpty()) {
            blocks.append(paragraph("结构化数据", true));
            blocks.append(tableXml(headers, report.getRows()));
        }
        if (!report.getNotes().isEmpty()) {
            blocks.append(paragraph("说明", true));
            for (String note : report.getNotes()) blocks.append(paragraph("• " + note, false));
        }
        blocks.append(paragraph("本报告由 AI 基于已提供数据生成，未执行任何店铺写入操作。缺失事实标注 NEEDS_DATA，未做补零或估算。", false));

        String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>" + blocks + "<w:sectPr/></w:body></w:document>";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", docxContentTypes());
            put(zip, "_rels/.rels", officeRels("word/document.xml"));
            put(zip, "word/document.xml", documentXml);
        } catch (IOException e) {
            throw new IllegalStateException("EXPORT_DOCX_FAILED", e);
        }
        return out.toByteArray();
    }

    private static String trailingTrim(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private static String inlineCell(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + xmlEscape(safeCell(text)) + "</t></is></c>";
    }

    private static String worksheet(String rows) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData>" + rows + "</sheetData></worksheet>";
    }

    private static String paragraph(String text, boolean heading) {
        if (text == null || text.trim().isEmpty()) return "<w:p/>";
        String run = heading
                ? "<w:r><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr><w:t xml:space=\"preserve\">" + xmlEscape(text) + "</w:t></w:r>"
                : "<w:r><w:t xml:space=\"preserve\">" + xmlEscape(text) + "</w:t></w:r>";
        return "<w:p>" + run + "</w:p>";
    }

    private static String tableXml(List<String> headers, List<SkillReportRow> rows) {
        if (headers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:color=\"CBD5E1\"/>")
                .append("<w:left w:val=\"single\" w:sz=\"4\" w:color=\"CBD5E1\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"CBD5E1\"/>")
                .append("<w:right w:val=\"single\" w:sz=\"4\" w:color=\"CBD5E1\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"CBD5E1\"/>")
                .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"CBD5E1\"/>")
                .append("</w:tblBorders></w:tblPr>");
        sb.append("<w:tr>");
        for (String h : headers) sb.append(cellXml(h, true));
        sb.append("</w:tr>");
        int limit = Math.min(MAX_ROWS, rows.size());
        for (int i = 0; i < limit; i++) {
            SkillReportRow row = rows.get(i);
            sb.append("<w:tr>");
            for (String h : headers) sb.append(cellXml(safeCell(row.get(h)), false));
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl>");
        return sb.toString();
    }

    private static String cellXml(String text, boolean header) {
        String shading = header ? "<w:shd w:val=\"clear\" w:fill=\"E8EEF5\"/>" : "";
        return "<w:tc><w:tcPr><w:tcW w:w=\"2400\" w:type=\"dxa\"/>" + shading + "</w:tcPr>"
                + paragraph(text, false) + "</w:tc>";
    }

    private static String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + (n % 26)));
            n /= 26;
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String officeRels(String target) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"" + target + "\"/>"
                + "</Relationships>";
    }

    private static String xlsxContentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>";
    }

    private static String workbookXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"报告数据\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "<sheet name=\"说明\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>";
    }

    private static String workbookRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "</Relationships>";
    }

    private static String docxContentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
    }
}

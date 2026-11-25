package com.wimoor.ai.skill;

import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import com.wimoor.ai.skill.SkillTypes.SkillReportRow;
import com.wimoor.ai.skill.SkillTypes.StructuredReport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 exporter.ts：真实 OOXML 结构、safeCell/xmlEscape、列键提取。 */
class ExporterTest {

    private StructuredReport reportWithRows() {
        StructuredReport report = new StructuredReport();
        report.setTitle("季度报告");
        report.setMarkdown("## 概览\n> 重要 & 关键");
        SkillReportRow row = new SkillReportRow();
        row.put("ASIN", "B0X");
        row.put("销量", 12);
        report.setRows(Arrays.asList(row));
        report.getNotes().add("注");
        return report;
    }

    private Set<String> zipEntries(byte[] bytes) throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.add(entry.getName());
        }
        return entries;
    }

    private String readEntry(byte[] bytes, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                if (name.equals(entry.getName())) return out.toString(StandardCharsets.UTF_8.name());
            }
        }
        return "";
    }

    @Test
    void excelIsRealOoXmlZip() throws Exception {
        byte[] bytes = new Exporter().exportReport(ExportFormat.XLSX, reportWithRows(), "r.xlsx");
        Set<String> entries = zipEntries(bytes);
        assertTrue(entries.contains("[Content_Types].xml"));
        assertTrue(entries.contains("xl/workbook.xml"));
        assertTrue(entries.contains("xl/worksheets/sheet1.xml"));
        assertTrue(entries.contains("xl/worksheets/sheet2.xml"));
        String sheet = readEntry(bytes, "xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("B0X"));
        assertTrue(sheet.contains("ASIN"));
        assertTrue(sheet.contains("<v>12</v>"));
        String overview = readEntry(bytes, "xl/worksheets/sheet2.xml");
        assertTrue(overview.contains("季度报告"));
    }

    @Test
    void wordIsRealOoXmlZip() throws Exception {
        byte[] bytes = new Exporter().exportReport(ExportFormat.DOCX, reportWithRows(), "r.docx");
        Set<String> entries = zipEntries(bytes);
        assertTrue(entries.contains("word/document.xml"));
        assertTrue(entries.contains("_rels/.rels"));
        String doc = readEntry(bytes, "word/document.xml");
        assertTrue(doc.contains("季度报告"));
        assertTrue(doc.contains("<w:tbl>"));
        assertTrue(doc.contains("B0X"));
    }

    @Test
    void safeCellAndXmlEscapeMatchTypeScript() {
        assertEquals(SkillTypes.NEEDS_DATA, Exporter.safeCell(null));
        assertEquals("abc", Exporter.safeCell("abc"));
        assertEquals("&lt;a&gt;&amp;&quot;&apos;", Exporter.xmlEscape("<a>&\"'"));
        String longValue = new String(new char[32001]).replace('\0', 'x');
        assertEquals(32001, Exporter.safeCell(longValue).length());
    }

    @Test
    void columnKeysKeepFirstSeenOrderAndDeduplicate() {
        SkillReportRow a = new SkillReportRow();
        a.put("A", 1); a.put("B", 2);
        SkillReportRow b = new SkillReportRow();
        b.put("B", 3); b.put("C", 4);
        assertEquals(Arrays.asList("A", "B", "C"), Exporter.columnKeys(Arrays.asList(a, b)));
    }
}

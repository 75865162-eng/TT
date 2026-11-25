package com.wimoor.ai.skill;

import com.wimoor.ai.skill.Config.LoadedSkill;
import com.wimoor.ai.skill.Config.SkillDefinition;
import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import com.wimoor.ai.skill.SkillTypes.SkillInput;
import com.wimoor.ai.skill.SkillTypes.SkillView;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 config.ts 的保守派生规则：导出格式、通用参数、分类标签、数据表与正文加载。 */
class ConfigTest {

    private final Config.InMemorySkillCatalog catalog = new Config.InMemorySkillCatalog();

    @Test
    void exportFormatIsDerivedFromCategoryThenSlug() {
        assertEquals(ExportFormat.XLSX, Config.exportFormatFor("inventory", "anything"));
        assertEquals(ExportFormat.XLSX, Config.exportFormatFor("research", "keyword-traffic-reverse"));
        assertEquals(ExportFormat.DOCX, Config.exportFormatFor("research", "amazon-niche-finder"));
        assertEquals(ExportFormat.XLSX, Config.exportFormatFor("listing", "reverse-asin"));
        assertEquals(ExportFormat.DOCX, Config.exportFormatFor(null, null));
    }

    @Test
    void inputsAreTheFourCommonParams() {
        List<SkillInput> inputs = Config.inputsFor(ExportFormat.DOCX);
        assertEquals(4, inputs.size());
        assertEquals("marketplace", inputs.get(0).getKey());
        assertTrue(inputs.get(0).isRequired());
        assertEquals("US", inputs.get(0).getDefaultValue());
        assertEquals("dateRange", inputs.get(1).getKey());
        assertEquals("last30days", inputs.get(1).getDefaultValue());
        assertEquals("asin", inputs.get(2).getKey());
        assertFalse(inputs.get(2).isRequired());
        assertEquals("brief", inputs.get(3).getKey());
        assertFalse(inputs.get(3).isRequired());
    }

    @Test
    void viewDerivesLabelsAndDedupedDataTables() {
        SkillView view = Config.findSkillView(catalog, "keyword-traffic-reverse");
        assertNotNull(view);
        assertEquals("关键词", view.getCategoryLabel());
        assertEquals(ExportFormat.XLSX, view.getExportFormat());
        assertEquals("Excel 表格 (.xlsx)", view.getExportFormatLabel());
        assertEquals(Arrays.asList("keyword_rank", "traffic_source"), view.getDataTables());
        assertTrue(view.isNew());
        assertNull(Config.findSkillView(catalog, "not-exist"));
    }

    @Test
    void categoriesStartWithAllAndSortByCount() {
        List<Map<String, Object>> categories = Config.listSkillCategories(catalog);
        assertEquals("all", categories.get(0).get("id"));
        assertEquals(4, categories.get(0).get("count"));
        assertTrue(categories.size() >= 2);
    }

    @Test
    void loadSkillBodyReturnsViewAndUntrustedMarkdown() {
        LoadedSkill loaded = Config.loadSkillBody(catalog, "inventory-restock-plan");
        assertNotNull(loaded);
        assertEquals("inventory-restock-plan", loaded.getView().getSlug());
        assertTrue(loaded.getBody().contains("流程"));
        assertNull(Config.loadSkillBody(catalog, "missing"));
    }

    @Test
    void manifestVersionIsBodySha256() {
        SkillDefinition def = catalog.findSkill("amazon-niche-finder");
        SkillManifest.Manifest manifest = catalog.manifest(def.getSlug());
        assertEquals(SkillManifest.sha256(def.getMarkdown()), manifest.getSha256());
        assertEquals("sha256:" + manifest.getSha256(), manifest.getVersion());
        assertEquals(def.getRequiredTools(), manifest.getRequiredTools());
    }
}

package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wimoor.ai.skill.SkillManifest;

/** shipped 目录加载、过滤、排序与 meta 工具执行的确定性测试（移植自 catalog.ts / tool-execution.ts）。 */
class CatalogTest {

    @Test
    void shippedDefinitionsHaveExpectedCounts() {
        assertEquals(116, Catalog.listSkills().size());
        assertEquals(107, Catalog.listTools().size());
        assertEquals(13, Catalog.listWorkflows().size());
    }

    @Test
    void skillsAreSortedByTaxonomyThenName() {
        List<Catalog.CatalogSkill> skills = Catalog.listSkills();
        List<String> slugs = new java.util.ArrayList<>();
        skills.forEach(s -> slugs.add(s.slug));
        assertEquals(slugs, Catalog.listSkills().stream().map(s -> s.slug).collect(java.util.stream.Collectors.toList()),
            "shipped catalog sort must be deterministic");
        Catalog.CatalogSkill first = skills.get(0);
        assertNotNull(first.slug);
        assertTrue(first.promptZh.contains("使用粒粒lili技能 " + first.slug));
        assertTrue(first.promptZh.contains(ApprovalPolicy.INSTRUCTIONS_ZH));
        assertTrue(first.promptEn.contains(ApprovalPolicy.INSTRUCTIONS_EN));
    }

    @Test
    void findSkillStripsPrefixes() {
        Catalog.CatalogSkill skill = Catalog.listSkills().get(0);
        assertNotNull(Catalog.findSkill(skill.slug));
        assertNotNull(Catalog.findSkill("lili-" + skill.slug));
        assertNotNull(Catalog.findSkill("duobaoyu-" + skill.slug));
        assertNull(Catalog.findSkill("definitely-not-a-skill"));
    }

    @Test
    void toolsHaveAvailabilityAndChineseLabels() {
        Catalog.CatalogTool meta = null, ads = null;
        for (Catalog.CatalogTool t : Catalog.listTools()) {
            if ("meta_search_skills".equals(t.name)) meta = t;
            if (t.name.startsWith("ads_") && ads == null) ads = t;
        }
        assertNotNull(meta);
        assertEquals("catalog", meta.availability);
        assertTrue(meta.readOnly);
        assertNotNull(ads);
        assertEquals("unmapped", ads.availability);
        assertEquals("ads", ads.category);
        List<String> names = new java.util.ArrayList<>();
        Catalog.listTools().forEach(t -> names.add(t.name));
        List<String> sorted = new java.util.ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertEquals(sorted, names);
    }

    @Test
    void workflowsHaveAlignedTranslationsAndSkillRefs() {
        for (Catalog.CatalogWorkflow w : Catalog.listWorkflows()) {
            assertTrue(w.index >= 1 && w.index <= 13);
            assertNotNull(w.slug);
            assertTrue(w.promptZh.contains(ApprovalPolicy.INSTRUCTIONS_ZH));
            assertTrue(w.promptEn.contains(ApprovalPolicy.INSTRUCTIONS_EN));
            assertFalse(w.steps.isEmpty());
            for (Catalog.WorkflowStep s : w.steps) assertEquals(s.n, w.steps.indexOf(s) + 1);
        }
    }

    @Test
    void skillMarkdownVersionIsSha256OfBody() {
        Catalog.CatalogSkill skill = Catalog.listSkills().get(0);
        String markdown = Catalog.skillMarkdown(skill.slug);
        assertNotNull(markdown);
        Map<String, Object> manifest = SkillMeta.getSkillManifest(skill.slug);
        assertEquals("sha256:" + SkillManifest.sha256(markdown), manifest.get("version"));
        assertEquals(SkillManifest.sha256(markdown), manifest.get("sha256"));
        assertEquals(skill.slug, manifest.get("skill_id"));
        assertNull(Catalog.skillMarkdown("no-such-skill"));
    }

    @Test
    void validateToolInputChecksRequiredAndTypes() {
        Catalog.InputSchema schema = new Catalog.InputSchema(Ports.jsonObject("type", "object",
            "properties", Ports.jsonObject("count", Ports.jsonObject("type", "integer"),
                "mode", Ports.jsonObject("type", "string", "enum", List.of("fast", "slow"))),
            "required", List.of("count")));
        assertEquals(1, ToolExecution.validateToolInput(schema, Map.of("mode", "fast")).size());
        assertEquals(1, ToolExecution.validateToolInput(schema, Map.of("count", 1, "unknown", "x")).size());
        assertEquals(2, ToolExecution.validateToolInput(schema, Map.of("count", "x", "mode", "bad")).size());
        assertTrue(ToolExecution.validateToolInput(schema, Map.of("count", 1)).isEmpty());
    }

    @Test
    void metaSearchAndWorkflowToolsExecuteDeterministically() {
        ToolExecution.ToolResult search = ToolExecution.runCatalogTool("meta_search_skills", Map.of("query", "ACOS"));
        assertEquals(200, search.status);
        assertEquals(true, search.body.get("ok"));
        assertTrue(((List<?>) search.body.get("skills")).size() <= 20);
        assertEquals(400, ToolExecution.runCatalogTool("meta_search_skills", Map.of("query", "  ")).status);
        assertEquals(404, ToolExecution.runCatalogTool("no_such_tool", Map.of()).status);
        assertEquals(200, ToolExecution.runCatalogTool("meta_list_workflows", Map.of()).status);
        assertEquals(13, ((List<?>) ToolExecution.runCatalogTool("meta_list_workflows", Map.of()).body.get("templates")).size());
        assertEquals(501, ToolExecution.runCatalogTool("ads_generate_recommendations", Map.of("store_scope", "profile-1")).status);
    }
}

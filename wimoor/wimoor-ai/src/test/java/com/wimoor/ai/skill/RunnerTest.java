package com.wimoor.ai.skill;

import com.wimoor.ai.skill.Config.InMemorySkillCatalog;
import com.wimoor.ai.skill.LlmClient.InMemory;
import com.wimoor.ai.skill.Runner.RunOptions;
import com.wimoor.ai.skill.Runner.TableAvailability;
import com.wimoor.ai.skill.SkillTypes.DataSource;
import com.wimoor.ai.skill.SkillTypes.PublishedRun;
import com.wimoor.ai.skill.SkillTypes.SkillRunEvent;
import com.wimoor.ai.skill.SkillTypes.SkillView;
import com.wimoor.ai.skill.SkillTypes.StructuredReport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 runner.ts：事件序列、NEEDS_DATA、结构化解析、空正文与安全校验。 */
class RunnerTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-25T01:02:03Z"), ZoneOffset.UTC);

    private SkillView view(String slug) {
        return Config.findSkillView(new InMemorySkillCatalog(), slug);
    }

    private AiModelSettings settings() {
        return new AiModelSettings("sk-secret", "gpt-test", "openai", "chat_completions", "http://llm");
    }

    private String llmResponse(String reportJson) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("message", Collections.singletonMap("content", reportJson));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("choices", Collections.singletonList(content));
        return Json.stringify(response);
    }

    @Test
    void runsAndEmitsRealReportEvents() {
        String reportJson = "{\"title\":\"报告\",\"markdown\":\"## 概览\\n12 单\",\"rows\":[{\"ASIN\":\"B0X\",\"销量\":12}],\"notes\":[\"注\"]}";
        LlmClient.InMemory llm = new InMemory(llmResponse(reportJson));
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("asin", "B0X");
        RunOptions options = new RunOptions(view("inventory-restock-plan"), "## 流程", inputs,
                settings(), Collections.emptyList(), "user-1", llm);

        String[] fileName = new String[1];
        List<SkillRunEvent> events = new Runner(FIXED).run(options, (report, name) -> {
            fileName[0] = name;
            assertEquals("报告", report.getTitle());
            assertEquals(1, report.getRows().size());
            return new Runner.PersistedReport("dl-1", "hi-1");
        });

        assertEquals(SkillRunEvent.Type.STEP, events.get(0).getType());
        assertEquals("3 个分析步骤", events.get(0).getStep().getDetail());
        assertTrue(events.stream().anyMatch(e -> e.getType() == SkillRunEvent.Type.DELTA));
        SkillRunEvent done = events.get(events.size() - 1);
        assertEquals(SkillRunEvent.Type.DONE, done.getType());
        assertEquals("## 概览\n12 单", done.getReport());
        assertEquals("dl-1", done.getDownloadId());
        assertEquals("hi-1", done.getHistoryId());
        assertEquals("补货计划_B0X_20261125.xlsx", fileName[0]);
        assertTrue(llm.getRecords().get(0).getSystem().contains("=== Skill 名称 ==="));
        assertTrue(llm.getRecords().get(0).getSystem().contains("本地数据事实"));
    }

    @Test
    void missingApiKeyEmitsErrorWithoutCallingModel() {
        AiModelSettings noKey = new AiModelSettings("", "m", "p", "chat_completions", "http://llm");
        RunOptions options = new RunOptions(view("listing-optimizer"), "", new LinkedHashMap<>(),
                noKey, Collections.emptyList(), "u", new InMemory("{}"));
        List<SkillRunEvent> events = new Runner(FIXED).run(options, (r, f) -> new Runner.PersistedReport("d", "h"));
        SkillRunEvent last = events.get(events.size() - 1);
        assertEquals(SkillRunEvent.Type.ERROR, last.getType());
        assertEquals("AI_NOT_CONFIGURED", last.getCode());
    }

    @Test
    void nonJsonReportKeepsOriginalText() {
        String raw = llmResponse("plain text report");
        RunOptions options = new RunOptions(view("listing-optimizer"), "", new LinkedHashMap<>(),
                settings(), Collections.emptyList(), "u", new InMemory(raw));
        List<SkillRunEvent> events = new Runner(FIXED).run(options, (r, f) -> {
            assertEquals("分析报告", r.getTitle());
            assertEquals("plain text report", r.getMarkdown());
            assertTrue(r.getNotes().get(0).contains("原文"));
            return new Runner.PersistedReport("d", "h");
        });
        assertEquals("plain text report", events.get(events.size() - 1).getReport());
    }

    @Test
    void unsafeResponseContainingApiKeyIsRejected() {
        String raw = llmResponse("回答里包含 sk-secret 密钥");
        RunOptions options = new RunOptions(view("listing-optimizer"), "", new LinkedHashMap<>(),
                settings(), Collections.emptyList(), "u", new InMemory(raw));
        List<SkillRunEvent> events = new Runner(FIXED).run(options, (r, f) -> new Runner.PersistedReport("d", "h"));
        assertEquals("AI_RESPONSE_UNSAFE", events.get(events.size() - 1).getCode());
    }

    @Test
    void availabilityRequiresPublishedFreshNonEmptyData() {
        DataSource stale = new DataSource("1", "amazon_catalog", "s", "US", null, "2026-01-01T00:00:00Z",
                1600, 10, new PublishedRun("COMPLETED", 1, "amazon_catalog", "src", "2026-01-01T00:00:00Z", "done"));
        DataSource empty = new DataSource("2", "review_feed", "s", "US", null, "2026-11-25T00:00:00Z",
                5, 0, new PublishedRun("COMPLETED", 1, "review_feed", "src", "2026-11-25T00:00:00Z", "done"));
        DataSource good = new DataSource("3", "inventory_snapshot", "s", "US", null, "2026-11-25T00:00:00Z",
                5, 42, new PublishedRun("COMPLETED", 1, "inventory_snapshot", "src", "2026-11-25T00:00:00Z", "done"));
        List<TableAvailability> tables = Runner.skillTableAvailability(Arrays.asList(stale, empty, good));
        assertFalse(tables.get(0).isAvailable());
        assertFalse(tables.get(1).isAvailable());
        assertTrue(tables.get(2).isAvailable());
        assertEquals("42", tables.get(2).getRowCount());
    }

    @Test
    void dataContextMarksMissingFacts() {
        String context = Runner.buildDataContext(view("amazon-niche-finder"), Collections.emptyList());
        assertTrue(context.contains(SkillTypes.NEEDS_DATA));
        assertTrue(context.contains("方法性报告"));
    }
}

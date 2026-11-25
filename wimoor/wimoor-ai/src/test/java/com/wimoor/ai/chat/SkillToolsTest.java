package com.wimoor.ai.chat;

import com.wimoor.ai.chat.ChatTypes.ChatScope;
import com.wimoor.ai.chat.ChatTypes.ToolDescriptor;
import com.wimoor.ai.chat.SkillTools.SkillToolContext;
import com.wimoor.ai.chat.SkillTools.ValidationException;
import com.wimoor.ai.skill.AiModelSettings;
import com.wimoor.ai.skill.Config;
import com.wimoor.ai.skill.Json;
import com.wimoor.ai.skill.LlmClient;
import com.wimoor.ai.skill.LlmClient.InMemory;
import com.wimoor.ai.skill.ReportStore;
import com.wimoor.ai.skill.RunHistory.InMemoryHistoryStore;
import com.wimoor.ai.skill.Runner;
import com.wimoor.ai.skill.Exporter;
import com.wimoor.ai.skill.SkillManifest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 skill-tools.ts：三件套注册、参数校验、身份拒绝、skill_run 报告与下载/历史链接。 */
class SkillToolsTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
    private static final ChatScope SCOPE = new ChatScope("org", "ws", "user");

    private final Config.InMemorySkillCatalog catalog = new Config.InMemorySkillCatalog();
    private final AtomicInteger sessionChecks = new AtomicInteger();
    private final ReportStore reportStore = new ReportStore(FIXED, () -> "abcdef");
    private final InMemoryHistoryStore historyStore = new InMemoryHistoryStore(FIXED, () -> "history-1");
    private final AiModelSettings settings = new AiModelSettings("sk-secret", "gpt-test", "openai", "chat_completions", "http://llm");

    private SkillToolContext context(LlmClient llm) {
        return new SkillToolContext(SCOPE, settings, "run-1", sessionChecks::incrementAndGet,
                catalog, new Runner(FIXED), reportStore, historyStore, new Exporter(FIXED),
                scope -> Collections.emptyList(), llm);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private static String llmReport(String markdown) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", Json.stringify(linked("title", "报告", "markdown", markdown)));
        return Json.stringify(linked("choices", Collections.singletonList(linked("message", message))));
    }

    private static Map<String, Object> linked(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private Map<String, Object> dispatch(ToolDescriptor tool, Map<String, Object> args) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.getDispatch().apply(args);
        return result;
    }

    @Test
    void registersThreeReadOnlyTools() {
        List<ToolDescriptor> tools = SkillTools.createChatSkillTools(context(new InMemory()));
        assertEquals(3, tools.size());
        assertEquals(Arrays.asList("skill_list", "skill_get", "skill_run"),
                Arrays.asList(tools.get(0).getName(), tools.get(1).getName(), tools.get(2).getName()));
        assertTrue(tools.stream().allMatch(t -> Boolean.TRUE.equals(t.getReadOnlyHint())));
        assertTrue(tools.stream().allMatch(t -> "read".equals(t.getPermission())));
        assertTrue(SkillTools.CHAT_SKILL_TOOL_NAMES.contains("skill_run"));
    }

    @Test
    void skillListRejectsUnknownArguments() {
        ToolDescriptor list = SkillTools.createChatSkillTools(context(new InMemory())).get(0);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> dispatch(list, args("slug", "x")));
        assertEquals("INVALID_ARGUMENT", ex.getCode());
    }

    @Test
    void skillGetRequiresValidSlug() {
        ToolDescriptor get = SkillTools.createChatSkillTools(context(new InMemory())).get(1);
        assertEquals("INVALID_ARGUMENT", assertThrows(ValidationException.class,
                () -> dispatch(get, args("other", "x"))).getCode());
        assertEquals("INVALID_SLUG", assertThrows(ValidationException.class,
                () -> dispatch(get, args())).getCode());
        String longSlug = new String(new char[121]).replace('\0', 'a');
        assertEquals("INVALID_SLUG", assertThrows(ValidationException.class,
                () -> dispatch(get, args("slug", longSlug))).getCode());
    }

    @Test
    void identityArgumentsAreAlwaysRejected() {
        ToolDescriptor get = SkillTools.createChatSkillTools(context(new InMemory())).get(1);
        assertEquals("CREDENTIAL_REJECTED", assertThrows(ValidationException.class,
                () -> dispatch(get, args("slug", "x", "userId", "attacker"))).getCode());
        assertEquals("CREDENTIAL_REJECTED", assertThrows(ValidationException.class,
                () -> dispatch(get, args("slug", "x", "organizationId", "other"))).getCode());
        assertEquals("CREDENTIAL_REJECTED", assertThrows(ValidationException.class,
                () -> dispatch(get, args("slug", "x", "workspace_id", "other"))).getCode());
    }

    @Test
    void normalizeInputsTruncatesAndSkipsNonStrings() {
        Map<String, Object> raw = new LinkedHashMap<>();
        String longValue = new String(new char[2500]).replace('\0', 'z');
        raw.put("long", longValue);
        for (int i = 0; i < 25; i++) raw.put("k" + i, "v" + i);
        raw.put("number", 123);
        Map<String, String> normalized = SkillTools.normalizeInputs(raw);
        assertEquals(20, normalized.size());
        assertEquals(2000, normalized.get("long").length());
        assertFalse(normalized.containsKey("number"));
    }

    @Test
    void skillGetReturnsManifestSha256AndDisclaimer() {
        ToolDescriptor get = SkillTools.createChatSkillTools(context(new InMemory())).get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) get.getDispatch().apply(args("slug", "amazon-niche-finder"));
        assertEquals(true, result.get("ok"));
        assertEquals("sha256:" + SkillManifest.sha256(catalog.skillMarkdown("amazon-niche-finder")), result.get("version"));
        assertEquals(SkillManifest.sha256(catalog.skillMarkdown("amazon-niche-finder")), result.get("sha256"));
        assertTrue(((String) result.get("body")).contains("## 流程"));
        assertTrue(((String) result.get("disclaimer")).contains("不是可执行工具"));
    }

    @Test
    void skillRunProducesReportDownloadAndHistory() {
        LlmClient llm = new InMemory(llmReport("## 概览\n只读分析结果"));
        ToolDescriptor run = SkillTools.createChatSkillTools(context(llm)).get(2);
        Map<String, Object> result = dispatch(run, args("slug", "inventory-restock-plan",
                "inputs", args("marketplace", "us", "asin", "B0X")));

        assertEquals(true, result.get("ok"));
        assertEquals("inventory-restock-plan", result.get("slug"));
        assertEquals("只读分析结果", ((String) result.get("report")).trim().replace("## 概览\n", ""));
        assertNotNull(result.get("downloadId"));
        assertEquals("history-1", result.get("historyId"));
        assertTrue(((String) result.get("source")).startsWith("/api/skills/inventory-restock-plan/export/"));
        assertTrue(((String) result.get("source")).contains((String) result.get("downloadId")));
        ReportStore.Entry entry = reportStore.getReport((String) result.get("downloadId"));
        assertNotNull(entry);
        assertTrue(entry.getFileName().endsWith(".xlsx"));
        assertEquals(1, historyStore.size());
        assertTrue(sessionChecks.get() >= 2);
    }

    @Test
    void skillRunRejectsIdentityInsideInputs() {
        ToolDescriptor run = SkillTools.createChatSkillTools(context(new InMemory())).get(2);
        assertEquals("CREDENTIAL_REJECTED", assertThrows(ValidationException.class,
                () -> dispatch(run, args("slug", "listing-optimizer",
                        "inputs", args("userId", "attacker")))).getCode());
    }

    @Test
    void unknownSkillReturnsNotFound() {
        LlmClient llm = new InMemory(llmReport("x"));
        ToolDescriptor get = SkillTools.createChatSkillTools(context(llm)).get(1);
        Map<String, Object> result = dispatch(get, args("slug", "not-exist"));
        assertEquals(false, result.get("ok"));
        assertEquals("SKILL_NOT_FOUND", result.get("error"));
    }
}

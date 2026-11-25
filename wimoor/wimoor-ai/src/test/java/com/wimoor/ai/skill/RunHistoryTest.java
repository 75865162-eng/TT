package com.wimoor.ai.skill;

import com.wimoor.ai.skill.RunHistory.Document;
import com.wimoor.ai.skill.RunHistory.InMemoryHistoryStore;
import com.wimoor.ai.skill.RunHistory.ListRunsResult;
import com.wimoor.ai.skill.RunHistory.SaveFailedSkillRunInput;
import com.wimoor.ai.skill.RunHistory.SaveSkillRunInput;
import com.wimoor.ai.skill.RunHistory.SkillRunDetail;
import com.wimoor.ai.skill.RunHistory.SkillRunScope;
import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import com.wimoor.ai.skill.SkillTypes.SkillReportRow;
import com.wimoor.ai.skill.SkillTypes.StructuredReport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 run-history.ts：作用域隔离、完整性校验、分页、失败运行与大小门禁。 */
class RunHistoryTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-25T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private final SkillRunScope scope = new SkillRunScope("org-1", "ws-1", "user-1");

    private StructuredReport report() {
        StructuredReport r = new StructuredReport();
        r.setTitle("标题");
        r.setMarkdown("## 正文");
        SkillReportRow row = new SkillReportRow();
        row.put("A", 1);
        r.setRows(new ArrayList<>(Collections.singletonList(row)));
        return r;
    }

    @Test
    void savesAndLoadsWithinScope() {
        InMemoryHistoryStore store = new InMemoryHistoryStore(new MutableClock(), () -> "run-1");
        byte[] bytes = "fake-zip-bytes".getBytes(StandardCharsets.UTF_8);
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("asin", "B0X");
        String id = store.saveSkillRun(new SaveSkillRunInput(scope, "slug", "技能", inputs,
                ExportFormat.XLSX, "out.xlsx", report(), bytes, "gpt", "openai", "US"));

        SkillRunDetail detail = store.getSkillRun(id, scope);
        assertEquals("run-1", detail.getId());
        assertEquals("COMPLETED", detail.getStatus());
        assertEquals("标题", detail.getTitle());
        assertEquals(1, detail.getRowCount());
        assertEquals("B0X", detail.getInputs().get("asin"));

        Document doc = store.getSkillRunDocument(id, scope);
        assertArrayEquals(bytes, doc.getBytes());
        assertEquals("out.xlsx", doc.getFileName());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", doc.getMimeType());
    }

    @Test
    void crossTenantAccessIsRejected() {
        InMemoryHistoryStore store = new InMemoryHistoryStore(new MutableClock(), () -> "run-1");
        store.saveSkillRun(new SaveSkillRunInput(scope, "s", "n", new LinkedHashMap<>(), ExportFormat.DOCX,
                "f.docx", report(), new byte[] { 1 }, "m", "p", "US"));
        SkillRunScope other = new SkillRunScope("org-2", "ws-1", "user-1");
        assertThrows(IllegalStateException.class, () -> store.getSkillRun("run-1", other));
        assertThrows(IllegalStateException.class, () -> store.getSkillRunDocument("run-1", other));
    }

    @Test
    void sizeOutsideLimitIsRejected() {
        InMemoryHistoryStore store = new InMemoryHistoryStore();
        assertThrows(IllegalArgumentException.class, () -> store.saveSkillRun(new SaveSkillRunInput(
                scope, "s", "n", new LinkedHashMap<>(), ExportFormat.DOCX, "f", report(),
                new byte[0], "m", "p", "US")));
    }

    @Test
    void failedRunHasNoDocument() {
        InMemoryHistoryStore store = new InMemoryHistoryStore(new MutableClock(), () -> "failed-1");
        String id = store.saveFailedSkillRun(new SaveFailedSkillRunInput(scope, "s", "n",
                new LinkedHashMap<>(), ExportFormat.DOCX, "m", "p", "US", "AI_NOT_CONFIGURED", "未配置"));
        SkillRunDetail detail = store.getSkillRun(id, scope);
        assertEquals("FAILED", detail.getStatus());
        assertEquals("AI_NOT_CONFIGURED", detail.getErrorCode());
        assertThrows(IllegalStateException.class, () -> store.getSkillRunDocument(id, scope));
    }

    @Test
    void listIsPagedAndNewestFirst() {
        MutableClock clock = new MutableClock();
        AtomicInteger seq = new AtomicInteger();
        InMemoryHistoryStore store = new InMemoryHistoryStore(clock, () -> "r" + seq.incrementAndGet());
        for (int i = 0; i < 3; i++) {
            store.saveSkillRun(new SaveSkillRunInput(scope, "s", "n", new LinkedHashMap<>(), ExportFormat.DOCX,
                    "f" + i + ".docx", report(), new byte[] { 1 }, "m", "p", "US"));
            clock.now = clock.now.plusSeconds(1);
        }
        ListRunsResult page1 = store.listSkillRuns(scope, 1, 2);
        assertEquals(Arrays.asList("r3", "r2"), Arrays.asList(page1.getRuns().get(0).getId(), page1.getRuns().get(1).getId()));
        assertTrue(page1.isHasNext());
        assertTrue(page1.isPersisted());
        ListRunsResult page2 = store.listSkillRuns(scope, 2, 2);
        assertEquals(1, page2.getRuns().size());
        assertEquals("r1", page2.getRuns().get(0).getId());
        assertFalse(page2.isHasNext());
    }
}

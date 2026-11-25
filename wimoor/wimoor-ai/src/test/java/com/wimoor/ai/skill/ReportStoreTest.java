package com.wimoor.ai.skill;

import com.wimoor.ai.skill.ReportStore.Entry;
import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 report-store.ts：TTL 过期、容量上限、二进制字节保真。 */
class ReportStoreTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-25T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advanceMillis(long ms) { now = now.plusMillis(ms); }
    }

    @Test
    void storesAndReadsBytes() {
        MutableClock clock = new MutableClock();
        ReportStore store = new ReportStore(clock, () -> "abc123");
        byte[] bytes = new byte[] { 1, 2, 3, -1 };
        String id = store.putReport(new ReportStore.PutInput(bytes, "r.xlsx", ExportFormat.XLSX, "org", "user"));
        assertTrue(id.matches("^[0-9a-z]+-1-abc123$"), id);
        Entry entry = store.getReport(id);
        assertNotNull(entry);
        assertArrayEquals(bytes, entry.getBytes());
        assertEquals("r.xlsx", entry.getFileName());
        assertEquals(ExportFormat.XLSX, entry.getFormat());
    }

    @Test
    void expiredEntryIsRemoved() {
        MutableClock clock = new MutableClock();
        ReportStore store = new ReportStore(clock, () -> "abc123");
        String id = store.putReport(new ReportStore.PutInput(new byte[] { 9 }, "r.docx", ExportFormat.DOCX, "o", "u"));
        clock.advanceMillis(ReportStore.TTL_MS + 1);
        assertNull(store.getReport(id));
        assertEquals(0, store.size());
    }

    @Test
    void evictsOldestBeyondCapacity() {
        MutableClock clock = new MutableClock();
        AtomicInteger suffix = new AtomicInteger();
        ReportStore store = new ReportStore(clock, () -> "s" + suffix.incrementAndGet());
        String first = null;
        // 忠实原实现：putReport 先 purge 再写入，稳态比上限多 1 条，下一次 put 才会淘汰最旧项。
        for (int i = 0; i <= ReportStore.MAX_ENTRIES + 1; i++) {
            String id = store.putReport(new ReportStore.PutInput(new byte[] { 1 }, "f", ExportFormat.DOCX, "o", "u"));
            if (i == 0) first = id;
            clock.advanceMillis(1);
        }
        assertEquals(ReportStore.MAX_ENTRIES + 1, store.size());
        assertNull(store.getReport(first));
    }
}

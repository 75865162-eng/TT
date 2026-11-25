package com.wimoor.ai.skill;

import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import com.wimoor.ai.skill.SkillTypes.StructuredReport;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Skill 运行历史。移植自 {@code src/lib/skill-engine/run-history.ts}。
 * 原实现复用 AgentExecution + FileObject + storage 适配器；Java 侧以 {@link HistoryStore}
 * 接口抽象持久化边界，{@link InMemoryHistoryStore} 为真实内存实现（保留三重作用域隔离、
 * 大小校验、SHA-256 完整性校验、分页与失败运行记录）。只保存模型真实产出的报告与字节，
 * 缺失事实由报告内的 NEEDS_DATA 表达，本层不补造任何业务数值。
 */
public final class RunHistory {

    private RunHistory() {}

    public static final String SKILL_RUN_NAMESPACE = "skill-run-v1";
    public static final int MAX_REPORT_BYTES = 10 * 1024 * 1024;

    /** 对原始字节求 SHA-256（导出文件是二进制 ZIP，不能先转字符串）。 */
    static String sha256Bytes(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static final Map<ExportFormat, String> MIME = new LinkedHashMap<>();
    static {
        MIME.put(ExportFormat.XLSX, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME.put(ExportFormat.DOCX, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    public static final class SkillRunScope {
        private final String organizationId;
        private final String workspaceId;
        private final String ownerId;
        public SkillRunScope(String organizationId, String workspaceId, String ownerId) {
            this.organizationId = organizationId; this.workspaceId = workspaceId; this.ownerId = ownerId;
        }
        public String getOrganizationId() { return organizationId; }
        public String getWorkspaceId() { return workspaceId; }
        public String getOwnerId() { return ownerId; }
        String key() { return organizationId + "\u0000" + workspaceId + "\u0000" + ownerId; }
    }

    public static final class SaveSkillRunInput {
        private final SkillRunScope scope; private final String slug; private final String skillName;
        private final Map<String, String> inputs; private final ExportFormat exportFormat; private final String fileName;
        private final StructuredReport report; private final byte[] bytes; private final String model;
        private final String provider; private final String marketplace;
        public SaveSkillRunInput(SkillRunScope scope, String slug, String skillName, Map<String, String> inputs,
                                 ExportFormat exportFormat, String fileName, StructuredReport report, byte[] bytes,
                                 String model, String provider, String marketplace) {
            this.scope = scope; this.slug = slug; this.skillName = skillName; this.inputs = inputs;
            this.exportFormat = exportFormat; this.fileName = fileName; this.report = report; this.bytes = bytes;
            this.model = model; this.provider = provider; this.marketplace = marketplace;
        }
        public SkillRunScope getScope() { return scope; }
        public String getSlug() { return slug; }
        public String getSkillName() { return skillName; }
        public Map<String, String> getInputs() { return inputs; }
        public ExportFormat getExportFormat() { return exportFormat; }
        public String getFileName() { return fileName; }
        public StructuredReport getReport() { return report; }
        public byte[] getBytes() { return bytes; }
        public String getModel() { return model; }
        public String getProvider() { return provider; }
        public String getMarketplace() { return marketplace; }
    }

    public static final class SaveFailedSkillRunInput {
        private final SkillRunScope scope; private final String slug; private final String skillName;
        private final Map<String, String> inputs; private final ExportFormat exportFormat; private final String model;
        private final String provider; private final String marketplace; private final String errorCode;
        private final String errorMessage;
        public SaveFailedSkillRunInput(SkillRunScope scope, String slug, String skillName, Map<String, String> inputs,
                                       ExportFormat exportFormat, String model, String provider, String marketplace,
                                       String errorCode, String errorMessage) {
            this.scope = scope; this.slug = slug; this.skillName = skillName; this.inputs = inputs;
            this.exportFormat = exportFormat; this.model = model; this.provider = provider;
            this.marketplace = marketplace; this.errorCode = errorCode; this.errorMessage = errorMessage;
        }
        public SkillRunScope getScope() { return scope; }
        public String getSlug() { return slug; }
        public String getSkillName() { return skillName; }
        public Map<String, String> getInputs() { return inputs; }
        public ExportFormat getExportFormat() { return exportFormat; }
        public String getModel() { return model; }
        public String getProvider() { return provider; }
        public String getMarketplace() { return marketplace; }
        public String getErrorCode() { return errorCode; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class SkillRunSummary {
        private String id; private String slug; private String skillName; private ExportFormat exportFormat;
        private String fileName; private String status; private String marketplace;
        private String createdAt; private String finishedAt;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getSkillName() { return skillName; }
        public void setSkillName(String skillName) { this.skillName = skillName; }
        public ExportFormat getExportFormat() { return exportFormat; }
        public void setExportFormat(ExportFormat exportFormat) { this.exportFormat = exportFormat; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMarketplace() { return marketplace; }
        public void setMarketplace(String marketplace) { this.marketplace = marketplace; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
        public String getFinishedAt() { return finishedAt; }
        public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    }

    public static final class SkillRunDetail extends SkillRunSummary {
        private String title; private String markdown; private List<String> notes = new ArrayList<>();
        private int rowCount; private String model; private String provider;
        private Map<String, String> inputs = new LinkedHashMap<>();
        private String errorCode; private String errorMessage;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getMarkdown() { return markdown; }
        public void setMarkdown(String markdown) { this.markdown = markdown; }
        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> notes) { this.notes = notes; }
        public int getRowCount() { return rowCount; }
        public void setRowCount(int rowCount) { this.rowCount = rowCount; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public Map<String, String> getInputs() { return inputs; }
        public void setInputs(Map<String, String> inputs) { this.inputs = inputs; }
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }

    public static final class ListRunsResult {
        private final List<SkillRunSummary> runs; private final int page; private final int pageSize;
        private final boolean hasNext; private final boolean persisted = true;
        public ListRunsResult(List<SkillRunSummary> runs, int page, int pageSize, boolean hasNext) {
            this.runs = runs; this.page = page; this.pageSize = pageSize; this.hasNext = hasNext;
        }
        public List<SkillRunSummary> getRuns() { return runs; }
        public int getPage() { return page; }
        public int getPageSize() { return pageSize; }
        public boolean isHasNext() { return hasNext; }
        public boolean isPersisted() { return persisted; }
    }

    public static final class Document {
        private final byte[] bytes; private final String fileName; private final String mimeType;
        public Document(byte[] bytes, String fileName, String mimeType) {
            this.bytes = bytes; this.fileName = fileName; this.mimeType = mimeType;
        }
        public byte[] getBytes() { return bytes; }
        public String getFileName() { return fileName; }
        public String getMimeType() { return mimeType; }
    }

    /** 持久化边界接口。 */
    public interface HistoryStore {
        String saveSkillRun(SaveSkillRunInput input);
        String saveFailedSkillRun(SaveFailedSkillRunInput input);
        ListRunsResult listSkillRuns(SkillRunScope scope, int page, int pageSize);
        SkillRunDetail getSkillRun(String id, SkillRunScope scope);
        Document getSkillRunDocument(String id, SkillRunScope scope);
    }

    /** 内存实现：保留原实现的校验、隔离、哈希与分页语义。 */
    public static final class InMemoryHistoryStore implements HistoryStore {
        private final Clock clock;
        private final Supplier<String> idSupplier;
        private final Map<String, StoredRun> records = new LinkedHashMap<>();

        public InMemoryHistoryStore() {
            this(Clock.systemUTC(), () -> UUID.randomUUID().toString());
        }
        public InMemoryHistoryStore(Clock clock, Supplier<String> idSupplier) {
            this.clock = clock == null ? Clock.systemUTC() : clock;
            this.idSupplier = idSupplier == null ? () -> UUID.randomUUID().toString() : idSupplier;
        }

        private static final class StoredRun {
            String id; String scopeKey; String slug; String skillName; ExportFormat exportFormat;
            String fileName; String status; String marketplace; Instant createdAt; Instant finishedAt;
            String title; String markdown; List<String> notes; int rowCount; String model; String provider;
            Map<String, String> inputs; String errorCode; String errorMessage;
            byte[] bytes; String contentHash; int size;
        }

        @Override
        public String saveSkillRun(SaveSkillRunInput input) {
            byte[] bytes = input.getBytes();
            if (bytes == null || bytes.length < 1 || bytes.length > MAX_REPORT_BYTES) {
                throw new IllegalArgumentException("SKILL_REPORT_SIZE_INVALID");
            }
            String id = idSupplier.get();
            StoredRun run = new StoredRun();
            run.id = id;
            run.scopeKey = input.getScope().key();
            run.slug = input.getSlug();
            run.skillName = input.getSkillName();
            run.inputs = input.getInputs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input.getInputs());
            run.exportFormat = input.getExportFormat();
            run.fileName = input.getFileName();
            run.status = "COMPLETED";
            run.marketplace = input.getMarketplace();
            run.createdAt = clock.instant();
            run.finishedAt = clock.instant();
            StructuredReport report = input.getReport();
            if (report != null) {
                run.title = report.getTitle();
                run.markdown = report.getMarkdown();
                run.notes = new ArrayList<>(report.getNotes());
                run.rowCount = report.getRows().size();
            } else {
                run.title = ""; run.markdown = ""; run.notes = new ArrayList<>(); run.rowCount = 0;
            }
            run.model = input.getModel();
            run.provider = input.getProvider();
            run.bytes = bytes.clone();
            run.size = bytes.length;
            run.contentHash = sha256Bytes(bytes);
            records.put(id, run);
            return id;
        }

        @Override
        public String saveFailedSkillRun(SaveFailedSkillRunInput input) {
            String id = idSupplier.get();
            StoredRun run = new StoredRun();
            run.id = id;
            run.scopeKey = input.getScope().key();
            run.slug = input.getSlug();
            run.skillName = input.getSkillName();
            run.inputs = input.getInputs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input.getInputs());
            run.exportFormat = input.getExportFormat();
            run.fileName = "";
            run.status = "FAILED";
            run.marketplace = input.getMarketplace();
            run.createdAt = clock.instant();
            run.finishedAt = clock.instant();
            run.model = input.getModel();
            run.provider = input.getProvider();
            run.errorCode = input.getErrorCode();
            run.errorMessage = input.getErrorMessage();
            run.notes = new ArrayList<>();
            records.put(id, run);
            return id;
        }

        @Override
        public ListRunsResult listSkillRuns(SkillRunScope scope, int page, int pageSize) {
            int p = Math.max(1, page);
            int size = Math.max(1, pageSize);
            List<StoredRun> matched = new ArrayList<>();
            for (StoredRun run : records.values()) {
                if (run.scopeKey.equals(scope.key())) matched.add(run);
            }
            matched.sort(Comparator.comparing((StoredRun r) -> r.createdAt).reversed()
                    .thenComparing(r -> r.id, Comparator.reverseOrder()));
            int from = Math.min((p - 1) * size, matched.size());
            int to = Math.min(from + size + 1, matched.size());
            boolean hasNext = to - from > size;
            List<SkillRunSummary> summaries = new ArrayList<>();
            for (StoredRun run : matched.subList(from, Math.min(to, from + size))) summaries.add(summarize(run));
            return new ListRunsResult(summaries, p, size, hasNext);
        }

        @Override
        public SkillRunDetail getSkillRun(String id, SkillRunScope scope) {
            StoredRun run = owned(id, scope);
            SkillRunDetail detail = new SkillRunDetail();
            copySummary(run, detail);
            detail.setTitle(run.title == null ? run.skillName : run.title);
            detail.setMarkdown(run.markdown == null ? "" : run.markdown);
            detail.setNotes(run.notes == null ? new ArrayList<>() : new ArrayList<>(run.notes));
            detail.setRowCount(run.rowCount);
            detail.setModel(run.model == null ? "" : run.model);
            detail.setProvider(run.provider == null ? "" : run.provider);
            detail.setInputs(run.inputs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(run.inputs));
            detail.setErrorCode(run.errorCode == null ? "" : run.errorCode);
            detail.setErrorMessage(run.errorMessage == null ? "" : run.errorMessage);
            return detail;
        }

        @Override
        public Document getSkillRunDocument(String id, SkillRunScope scope) {
            StoredRun run = owned(id, scope);
            if (!"COMPLETED".equals(run.status) || run.bytes == null || run.bytes.length == 0) {
                throw new IllegalStateException("SKILL_RUN_NOT_FOUND");
            }
            String hash = sha256Bytes(run.bytes);
            if (run.contentHash != null && !run.contentHash.equals(hash)) {
                throw new IllegalStateException("SKILL_RUN_INTEGRITY_FAILED");
            }
            if (run.size > 0 && run.size != run.bytes.length) {
                throw new IllegalStateException("SKILL_RUN_INTEGRITY_FAILED");
            }
            String mime = MIME.getOrDefault(run.exportFormat, MIME.get(ExportFormat.DOCX));
            return new Document(run.bytes.clone(), run.fileName, mime);
        }

        private StoredRun owned(String id, SkillRunScope scope) {
            StoredRun run = records.get(id == null ? "" : id);
            if (run == null || !run.scopeKey.equals(scope.key())) throw new IllegalStateException("SKILL_RUN_NOT_FOUND");
            return run;
        }

        private static SkillRunSummary summarize(StoredRun run) {
            SkillRunSummary s = new SkillRunSummary();
            copySummary(run, s);
            return s;
        }

        private static void copySummary(StoredRun run, SkillRunSummary s) {
            s.setId(run.id);
            s.setSlug(run.slug == null ? "" : run.slug);
            s.setSkillName(run.skillName == null ? "" : run.skillName);
            s.setExportFormat(run.exportFormat == null ? ExportFormat.DOCX : run.exportFormat);
            s.setFileName(run.fileName == null ? "" : run.fileName);
            s.setStatus(run.status);
            s.setMarketplace(run.marketplace == null ? "" : run.marketplace);
            s.setCreatedAt(run.createdAt.toString());
            s.setFinishedAt(run.finishedAt == null ? null : run.finishedAt.toString());
        }

        /** 测试辅助：当前内存记录数。 */
        public int size() { return records.size(); }
    }
}

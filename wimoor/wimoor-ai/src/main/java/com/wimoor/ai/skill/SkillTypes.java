package com.wimoor.ai.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 引擎公共 DTO 与枚举。移植自 {@code src/lib/skill-engine/types.ts}。
 * 数据结构严格对齐 TypeScript 原定义：SkillView 描述流程文档，StructuredReport
 * 是模型真实产出的结构化报告，SkillRunEvent 对应原 SSE 事件联合类型。
 * 缺失事实统一以 {@link #NEEDS_DATA} 表达，绝不补零或估算。
 */
public final class SkillTypes {

    private SkillTypes() {}

    /** 复用 Lili 归属的 agents 权限模块，不新增权限面。 */
    public static final String SKILL_MODULE_ID = "agents";
    public static final String SKILL_RUN_ACTION = "create";
    public static final String SKILL_EXPORT_ACTION = "export";

    /** 缺失事实占位符（移植自 runner.ts 的 NEEDS_DATA）。 */
    public static final String NEEDS_DATA = "NEEDS_DATA";

    public enum SkillInputKind {
        MARKETPLACE("marketplace"), DATE_RANGE("dateRange"), ASIN("asin"), TEXT("text");
        private final String wire;
        SkillInputKind(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** 导出格式（types.ts 的 ExportFormat）。 */
    public enum ExportFormat {
        XLSX("xlsx", "Excel 表格 (.xlsx)"), DOCX("docx", "Word 文档 (.docx)");
        private final String wire;
        private final String label;
        ExportFormat(String wire, String label) { this.wire = wire; this.label = label; }
        public String wire() { return wire; }
        public String label() { return label; }
        public static ExportFormat from(String value) {
            return "xlsx".equalsIgnoreCase(value) ? XLSX : DOCX;
        }
    }

    public static final class Option {
        private final String value;
        private final String label;
        public Option(String value, String label) { this.value = value; this.label = label; }
        public String getValue() { return value; }
        public String getLabel() { return label; }
    }

    public static final class SkillInput {
        private final String key;
        private final String label;
        private final SkillInputKind kind;
        private final boolean required;
        private final String placeholder;
        private final List<Option> options;
        private final String defaultValue;
        public SkillInput(String key, String label, SkillInputKind kind, boolean required,
                          String placeholder, List<Option> options, String defaultValue) {
            this.key = key; this.label = label; this.kind = kind; this.required = required;
            this.placeholder = placeholder; this.options = options; this.defaultValue = defaultValue;
        }
        public String getKey() { return key; }
        public String getLabel() { return label; }
        public SkillInputKind getKind() { return kind; }
        public boolean isRequired() { return required; }
        public String getPlaceholder() { return placeholder; }
        public List<Option> getOptions() { return options; }
        public String getDefaultValue() { return defaultValue; }
    }

    /** Skill 流程视图（types.ts 的 SkillView），不是可执行代码。 */
    public static final class SkillView {
        private String slug;
        private String name;
        private String summary;
        private String category;
        private String categoryLabel;
        private List<String> steps = new ArrayList<>();
        private List<String> tools = new ArrayList<>();
        private String prompt;
        private boolean featured;
        private boolean isNew;
        private List<SkillInput> inputs = new ArrayList<>();
        private ExportFormat exportFormat = ExportFormat.DOCX;
        private String exportFormatLabel;
        private List<String> dataTables = new ArrayList<>();

        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getCategoryLabel() { return categoryLabel; }
        public void setCategoryLabel(String categoryLabel) { this.categoryLabel = categoryLabel; }
        public List<String> getSteps() { return steps; }
        public void setSteps(List<String> steps) { this.steps = steps; }
        public List<String> getTools() { return tools; }
        public void setTools(List<String> tools) { this.tools = tools; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
        public boolean isFeatured() { return featured; }
        public void setFeatured(boolean featured) { this.featured = featured; }
        public boolean isNew() { return isNew; }
        public void setNew(boolean isNew) { this.isNew = isNew; }
        public List<SkillInput> getInputs() { return inputs; }
        public void setInputs(List<SkillInput> inputs) { this.inputs = inputs; }
        public ExportFormat getExportFormat() { return exportFormat; }
        public void setExportFormat(ExportFormat exportFormat) { this.exportFormat = exportFormat; }
        public String getExportFormatLabel() { return exportFormatLabel; }
        public void setExportFormatLabel(String exportFormatLabel) { this.exportFormatLabel = exportFormatLabel; }
        public List<String> getDataTables() { return dataTables; }
        public void setDataTables(List<String> dataTables) { this.dataTables = dataTables; }
    }

    public enum StepStatus { RUNNING("running"), DONE("done"), SKIPPED("skipped"), FAILED("failed");
        private final String wire;
        StepStatus(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public static final class SkillRunStep {
        private final String id;
        private final String label;
        private final StepStatus status;
        private final String detail;
        private final Integer durationMs;
        public SkillRunStep(String id, String label, StepStatus status, String detail, Integer durationMs) {
            this.id = id; this.label = label; this.status = status; this.detail = detail; this.durationMs = durationMs;
        }
        public String getId() { return id; }
        public String getLabel() { return label; }
        public StepStatus getStatus() { return status; }
        public String getDetail() { return detail; }
        public Integer getDurationMs() { return durationMs; }
    }

    /** 对应 types.ts 的 SkillRunEvent 联合类型；用 type + 可选字段建模。 */
    public static final class SkillRunEvent {
        public enum Type { STEP, DELTA, DONE, ERROR }
        private final Type type;
        private final SkillRunStep step;
        private final String text;
        private final String report;
        private final ExportFormat exportFormat;
        private final String exportFileName;
        private final String downloadId;
        private final String historyId;
        private final String code;
        private final String message;

        private SkillRunEvent(Type type, SkillRunStep step, String text, String report,
                              ExportFormat exportFormat, String exportFileName, String downloadId,
                              String historyId, String code, String message) {
            this.type = type; this.step = step; this.text = text; this.report = report;
            this.exportFormat = exportFormat; this.exportFileName = exportFileName;
            this.downloadId = downloadId; this.historyId = historyId; this.code = code; this.message = message;
        }
        public static SkillRunEvent step(SkillRunStep s) {
            return new SkillRunEvent(Type.STEP, s, null, null, null, null, null, null, null, null);
        }
        public static SkillRunEvent delta(String text) {
            return new SkillRunEvent(Type.DELTA, null, text, null, null, null, null, null, null, null);
        }
        public static SkillRunEvent done(String report, ExportFormat format, String fileName,
                                         String downloadId, String historyId) {
            return new SkillRunEvent(Type.DONE, null, null, report, format, fileName, downloadId, historyId, null, null);
        }
        public static SkillRunEvent error(String code, String message) {
            return new SkillRunEvent(Type.ERROR, null, null, null, null, null, null, null, code, message);
        }
        public Type getType() { return type; }
        public SkillRunStep getStep() { return step; }
        public String getText() { return text; }
        public String getReport() { return report; }
        public ExportFormat getExportFormat() { return exportFormat; }
        public String getExportFileName() { return exportFileName; }
        public String getDownloadId() { return downloadId; }
        public String getHistoryId() { return historyId; }
        public String getCode() { return code; }
        public String getMessage() { return message; }
    }

    /** 表格行，保留原始键顺序；缺失值为 null。 */
    public static final class SkillReportRow extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
        public SkillReportRow() { super(); }
        public SkillReportRow(Map<String, Object> source) { super(source); }
    }

    /** 传给 skillTableAvailability 的只读数据源摘要。 */
    public static final class DataSource {
        private final String id;
        private final String resource;
        private final String sellerAccountId;
        private final String marketplaceId;
        private final String activeRunId;
        private final String lastSuccessAt;
        private final int freshnessMinutes;
        private final int rowCount;
        private final PublishedRun publishedRun;
        public DataSource(String id, String resource, String sellerAccountId, String marketplaceId,
                          String activeRunId, String lastSuccessAt, int freshnessMinutes, int rowCount,
                          PublishedRun publishedRun) {
            this.id = id; this.resource = resource; this.sellerAccountId = sellerAccountId;
            this.marketplaceId = marketplaceId; this.activeRunId = activeRunId;
            this.lastSuccessAt = lastSuccessAt; this.freshnessMinutes = freshnessMinutes;
            this.rowCount = rowCount; this.publishedRun = publishedRun;
        }
        public String getId() { return id; }
        public String getResource() { return resource; }
        public String getSellerAccountId() { return sellerAccountId; }
        public String getMarketplaceId() { return marketplaceId; }
        public String getActiveRunId() { return activeRunId; }
        public String getLastSuccessAt() { return lastSuccessAt; }
        public int getFreshnessMinutes() { return freshnessMinutes; }
        public int getRowCount() { return rowCount; }
        public PublishedRun getPublishedRun() { return publishedRun; }
    }

    public static final class PublishedRun {
        private final String status;
        private final int executionVersion;
        private final String resource;
        private final String sourceId;
        private final String finishedAt;
        private final String stage;
        public PublishedRun(String status, int executionVersion, String resource, String sourceId,
                            String finishedAt, String stage) {
            this.status = status; this.executionVersion = executionVersion; this.resource = resource;
            this.sourceId = sourceId; this.finishedAt = finishedAt; this.stage = stage;
        }
        public String getStatus() { return status; }
        public int getExecutionVersion() { return executionVersion; }
        public String getResource() { return resource; }
        public String getSourceId() { return sourceId; }
        public String getFinishedAt() { return finishedAt; }
        public String getStage() { return stage; }
    }

    /** 模型输出的结构化报告：markdown 用于预览/Word，rows 用于 Excel。 */
    public static final class StructuredReport {
        private String title = "分析报告";
        private String markdown = "";
        private List<SkillReportRow> rows = new ArrayList<>();
        private List<String> notes = new ArrayList<>();
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getMarkdown() { return markdown; }
        public void setMarkdown(String markdown) { this.markdown = markdown; }
        public List<SkillReportRow> getRows() { return rows; }
        public void setRows(List<SkillReportRow> rows) { this.rows = rows; }
        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> notes) { this.notes = notes; }
    }
}

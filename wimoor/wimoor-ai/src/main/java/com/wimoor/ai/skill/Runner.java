package com.wimoor.ai.skill;

import com.wimoor.ai.skill.SkillTypes.DataSource;
import com.wimoor.ai.skill.SkillTypes.ExportFormat;
import com.wimoor.ai.skill.SkillTypes.PublishedRun;
import com.wimoor.ai.skill.SkillTypes.SkillReportRow;
import com.wimoor.ai.skill.SkillTypes.SkillRunEvent;
import com.wimoor.ai.skill.SkillTypes.SkillRunStep;
import com.wimoor.ai.skill.SkillTypes.SkillView;
import com.wimoor.ai.skill.SkillTypes.StepStatus;
import com.wimoor.ai.skill.SkillTypes.StructuredReport;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Skill 执行引擎。移植自 {@code src/lib/skill-engine/runner.ts}。
 * 运行时不接触任何写接口：Skill 只做只读分析，结论以报告呈现，写入仍走主系统审批流。
 * 生成器在 Java 侧物化为确定性的事件列表，路由层按序消费即可等价 SSE 流式推送。
 * 真实模型不可用时产出 error 事件，绝不伪造报告。
 */
public final class Runner implements SkillRunner {

    /** 与 runner.ts 一致：缺失事实占位符。 */
    private static final String NEEDS_DATA = SkillTypes.NEEDS_DATA;
    private static final int CHUNK_SIZE = 240;
    private static final int PUBLISH_FRESHNESS_LIMIT_MIN = 1440;

    private final Clock clock;

    public Runner() { this(Clock.systemUTC()); }
    public Runner(Clock clock) { this.clock = clock == null ? Clock.systemUTC() : clock; }

    /** 执行所需的全部输入（对应 runner.ts 的 RunOptions）。 */
    public static final class RunOptions {
        private final SkillView view;
        private final String body;
        private final Map<String, String> inputs;
        private final AiModelSettings aiSettings;
        private final List<DataSource> sources;
        private final String actor;
        private final LlmClient llmClient;
        public RunOptions(SkillView view, String body, Map<String, String> inputs,
                          AiModelSettings aiSettings, List<DataSource> sources, String actor,
                          LlmClient llmClient) {
            this.view = view; this.body = body == null ? "" : body; this.inputs = inputs;
            this.aiSettings = aiSettings; this.sources = sources == null ? Collections.emptyList() : sources;
            this.actor = actor; this.llmClient = llmClient;
        }
        public SkillView getView() { return view; }
        public String getBody() { return body; }
        public Map<String, String> getInputs() { return inputs; }
        public AiModelSettings getAiSettings() { return aiSettings; }
        public List<DataSource> getSources() { return sources; }
        public String getActor() { return actor; }
        public LlmClient getLlmClient() { return llmClient; }
    }

    /** 报告生成后的持久化回调（对应 runner.ts 的 RunHooks.persistReport）。 */
    @FunctionalInterface
    public interface RunHooks {
        PersistedReport persistReport(StructuredReport report, String fileName) throws Exception;
    }

    public static final class PersistedReport {
        private final String downloadId;
        private final String historyId;
        public PersistedReport(String downloadId, String historyId) {
            this.downloadId = downloadId; this.historyId = historyId;
        }
        public String getDownloadId() { return downloadId; }
        public String getHistoryId() { return historyId; }
    }

    @Override
    public List<SkillRunEvent> run(RunOptions options, RunHooks hooks) {
        List<SkillRunEvent> events = new ArrayList<>();
        SkillView view = options.getView();
        events.add(SkillRunEvent.step(new SkillRunStep("definition", "读取 Skill 定义", StepStatus.DONE,
                view.getSteps().size() + " 个分析步骤", null)));

        String dataContext = buildDataContext(view, options.getSources());
        events.add(SkillRunEvent.step(new SkillRunStep("data", "核对本地数据可用性", StepStatus.DONE,
                dataContext.contains(NEEDS_DATA) ? "部分数据缺失，将标注 NEEDS_DATA" : "已获取可用数据集摘要", null)));

        AiModelSettings settings = options.getAiSettings();
        if (settings == null || !settings.hasApiKey()) {
            events.add(SkillRunEvent.error("AI_NOT_CONFIGURED",
                    "当前工作区未配置 AI 模型，无法生成报告。请先在系统设置中配置 AI 凭据。"));
            return events;
        }

        boolean chat = settings.isChatCompletions();
        events.add(SkillRunEvent.step(new SkillRunStep("llm", "调用 AI 分析生成报告", StepStatus.RUNNING, null, null)));

        String system = systemInstructions(view, options.getBody(), dataContext);
        String user = userPrompt(view, options.getInputs());
        String raw;
        try {
            raw = options.getLlmClient().call(settings, system, user);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            events.add(SkillRunEvent.error("AI_CONNECTION_FAILED", msg == null ? "连接 AI 服务失败。" : msg));
            return events;
        }
        if (raw == null || raw.isEmpty() || raw.contains(settings.getApiKey())) {
            events.add(SkillRunEvent.error("AI_RESPONSE_UNSAFE", "AI 返回为空或包含敏感信息，已中止。"));
            return events;
        }

        String text;
        try {
            text = extractText(Json.parseObject(raw), chat);
        } catch (RuntimeException ex) {
            events.add(SkillRunEvent.error("AI_RESPONSE_INVALID", "AI 返回内容无法解析。"));
            return events;
        }
        if (text == null || text.isEmpty() || text.contains(settings.getApiKey())) {
            events.add(SkillRunEvent.error("AI_RESPONSE_UNSAFE", "AI 返回为空或包含敏感信息，已中止。"));
            return events;
        }

        events.add(SkillRunEvent.step(new SkillRunStep("llm", "调用 AI 分析生成报告", StepStatus.DONE, null, null)));
        StructuredReport report = parseStructuredReport(text);

        // 逐段推送，内容来自真实模型的完整输出。
        String markdown = report.getMarkdown();
        for (int i = 0; i < markdown.length(); i += CHUNK_SIZE) {
            events.add(SkillRunEvent.delta(markdown.substring(i, Math.min(markdown.length(), i + CHUNK_SIZE))));
        }
        if (markdown.isEmpty()) events.add(SkillRunEvent.delta(""));

        String stamp = LocalDate.now(clock).toString().replace("-", "");
        String asin = options.getInputs() == null ? null : options.getInputs().get("asin");
        String subject = (asin == null || asin.trim().isEmpty()) ? view.getSlug() : asin.trim();
        String fileName = view.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + subject + "_" + stamp
                + "." + view.getExportFormat().wire();

        events.add(SkillRunEvent.step(new SkillRunStep("export", "生成导出文件", StepStatus.RUNNING, null, null)));
        String downloadId;
        String historyId;
        try {
            PersistedReport persisted = hooks.persistReport(report, fileName);
            downloadId = persisted.getDownloadId();
            historyId = persisted.getHistoryId();
        } catch (Exception ex) {
            events.add(SkillRunEvent.error("EXPORT_FAILED", "报告已生成，但导出文件创建失败。"));
            return events;
        }
        events.add(SkillRunEvent.step(new SkillRunStep("export", "生成导出文件", StepStatus.DONE, fileName, null)));
        events.add(SkillRunEvent.done(report.getMarkdown(), view.getExportFormat(), fileName, downloadId, historyId));
        return events;
    }

    /** 与 runner.ts 的 systemInstructions 等价的严格约束提示。 */
    static String systemInstructions(SkillView view, String body, String dataContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是条条 ERP 的运营分析助手，基于给定 Skill 定义与可用的本地数据事实，输出分析报告。\n");
        sb.append("严格约束：\n");
        sb.append("1. 只能使用下方『本地数据事实』中实际提供的数值；没有提供的事实一律写 ").append(NEEDS_DATA)
                .append("，严禁编造、估算或补零。\n");
        sb.append("2. 金额与数量保持原始精度字符串，不要四舍五入成猜测值。\n");
        sb.append("3. 结论要可执行，但不承诺已执行任何店铺修改；写入类动作只给出建议，不声称已落地。\n");
        sb.append("4. 输出必须是单个 JSON 对象，不要包裹 Markdown 代码块，不要附加解释文字。\n");
        sb.append("JSON 结构：\n");
        sb.append("{\"title\": string, \"markdown\": string, \"rows\": Array<Record<string,string|number|null>>, \"notes\"?: string[]}\n");
        sb.append("markdown 用二级标题分段（如 ## 概览、## 诊断、## 建议）；rows 用于表格导出，字段名用中文，缺失值用 null。\n");
        sb.append("\n=== Skill 名称 ===\n").append(view.getName()).append("\n");
        sb.append("=== Skill 摘要 ===\n").append(view.getSummary()).append("\n");
        sb.append("=== Skill 执行步骤（须遵循的分析路径）===\n");
        List<String> steps = view.getSteps();
        for (int i = 0; i < steps.size(); i++) sb.append(i + 1).append(". ").append(steps.get(i)).append("\n");
        sb.append("=== Skill 完整定义 ===\n").append(body == null || body.isEmpty() ? "（该 Skill 无附加正文，按摘要与步骤执行。）" : body).append("\n");
        sb.append("=== 本地数据事实（真实来源；未提供即视为缺失）===\n").append(dataContext);
        return sb.toString();
    }

    static String userPrompt(SkillView view, Map<String, String> inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(view.getSlug()).append("\n参数：\n");
        for (SkillTypes.SkillInput input : view.getInputs()) {
            String value = inputs == null ? null : inputs.get(input.getKey());
            sb.append("- ").append(input.getLabel()).append(": ")
                    .append(value == null || value.trim().isEmpty() ? "（未提供）" : value.trim()).append("\n");
        }
        sb.append("\n请按系统约束输出 JSON 报告。");
        return sb.toString();
    }

    /** 数据源可用性摘要；available 要求已发布、成功、非空行且在 24 小时新鲜度内。 */
    @SuppressWarnings("unchecked")
    public static List<TableAvailability> skillTableAvailability(List<DataSource> sources) {
        List<TableAvailability> out = new ArrayList<>();
        if (sources == null) return out;
        for (DataSource source : sources) {
            PublishedRun run = source.getPublishedRun();
            boolean published = run != null && ("COMPLETED".equals(run.getStatus())
                    || "SUCCESS".equals(run.getStatus()) || "FINISHED".equals(run.getStatus()));
            boolean fresh = source.getFreshnessMinutes() <= PUBLISH_FRESHNESS_LIMIT_MIN;
            boolean nonEmpty = source.getRowCount() > 0;
            List<String> warnings = new ArrayList<>();
            if (!published) warnings.add("无已发布运行");
            if (!fresh) warnings.add("数据超过 24 小时未更新");
            if (!nonEmpty) warnings.add("数据行为空");
            String rowCount = nonEmpty ? String.valueOf(source.getRowCount()) : NEEDS_DATA;
            String lastSync = source.getLastSuccessAt() == null || source.getLastSuccessAt().isEmpty()
                    ? NEEDS_DATA : source.getLastSuccessAt();
            out.add(new TableAvailability(source.getResource(), published && fresh && nonEmpty,
                    rowCount, source.getFreshnessMinutes() + " 分钟前", lastSync, warnings));
        }
        return out;
    }

    public static final class TableAvailability {
        private final String name;
        private final boolean available;
        private final String rowCount;
        private final String freshness;
        private final String lastSyncAt;
        private final List<String> warnings;
        public TableAvailability(String name, boolean available, String rowCount, String freshness,
                                 String lastSyncAt, List<String> warnings) {
            this.name = name; this.available = available; this.rowCount = rowCount;
            this.freshness = freshness; this.lastSyncAt = lastSyncAt; this.warnings = warnings;
        }
        public String getName() { return name; }
        public boolean isAvailable() { return available; }
        public String getRowCount() { return rowCount; }
        public String getFreshness() { return freshness; }
        public String getLastSyncAt() { return lastSyncAt; }
        public List<String> getWarnings() { return warnings; }
    }

    static String buildDataContext(SkillView view, List<DataSource> sources) {
        List<TableAvailability> availability = skillTableAvailability(sources);
        List<TableAvailability> relevant;
        if (view.getDataTables() != null && !view.getDataTables().isEmpty()) {
            relevant = new ArrayList<>();
            for (TableAvailability t : availability) {
                if (view.getDataTables().contains(t.getName())) relevant.add(t);
            }
        } else {
            relevant = new ArrayList<>();
            for (TableAvailability t : availability) if (t.isAvailable()) relevant.add(t);
        }
        if (relevant.isEmpty()) {
            return "当前工作区没有已发布、可读取的本地数据快照。所有业务数值均为 " + NEEDS_DATA
                    + "；请据此输出方法性报告，不要编造数据。";
        }
        StringBuilder sb = new StringBuilder();
        for (TableAvailability t : relevant) {
            sb.append("- 数据集 ").append(t.getName()).append("：可用=").append(t.isAvailable()).append("\n");
            sb.append("  行数=").append(t.getRowCount()).append("，新鲜度=").append(t.getFreshness())
                    .append("，最近同步=").append(t.getLastSyncAt()).append("\n");
            if (!t.getWarnings().isEmpty()) sb.append("  提示：").append(String.join("；", t.getWarnings())).append("\n");
        }
        return sb.toString();
    }

    /** 解析模型报告 JSON；非 JSON 保留为纯文本报告，rows 为空。 */
    @SuppressWarnings("unchecked")
    public static StructuredReport parseStructuredReport(String text) {
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException ex) {
            return plainReport(text, "模型未按结构化格式输出，已保留原文。");
        }
        if (!(parsed instanceof Map)) return plainReport(text, "模型返回内容格式异常，已保留原文。");
        Map<String, Object> value = (Map<String, Object>) parsed;
        StructuredReport report = new StructuredReport();
        Object title = value.get("title");
        if (title instanceof String && !((String) title).trim().isEmpty()) report.setTitle(((String) title).trim());
        Object markdown = value.get("markdown");
        if (markdown instanceof String && !((String) markdown).trim().isEmpty()) report.setMarkdown(((String) markdown).trim());
        List<SkillReportRow> rows = new ArrayList<>();
        Object rawRows = value.get("rows");
        if (rawRows instanceof List) {
            for (Object row : (List<Object>) rawRows) {
                if (!(row instanceof Map)) continue;
                SkillReportRow clean = new SkillReportRow();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) row).entrySet()) {
                    Object cell = e.getValue();
                    String key = String.valueOf(e.getKey());
                    if (cell == null || "".equals(cell)) clean.put(key, null);
                    else if (cell instanceof String || cell instanceof Number) clean.put(key, cell);
                    else clean.put(key, String.valueOf(cell));
                }
                if (!clean.isEmpty()) rows.add(clean);
            }
        }
        report.setRows(rows);
        List<String> notes = new ArrayList<>();
        Object rawNotes = value.get("notes");
        if (rawNotes instanceof List) {
            for (Object note : (List<Object>) rawNotes) if (note instanceof String) notes.add((String) note);
        }
        report.setNotes(notes);
        if (report.getMarkdown().isEmpty()) report.setMarkdown(report.getTitle());
        return report;
    }

    private static StructuredReport plainReport(String text, String note) {
        StructuredReport report = new StructuredReport();
        report.setTitle("分析报告");
        report.setMarkdown(text == null ? "" : text.trim());
        report.getNotes().add(note);
        return report;
    }

    /** 从模型响应 JSON 提取正文，兼容 chat_completions 与 responses 两种 wireApi。 */
    @SuppressWarnings("unchecked")
    static String extractText(Map<String, Object> body, boolean chat) {
        if (chat) {
            Object choices = body.get("choices");
            if (choices instanceof List && !((List<Object>) choices).isEmpty()) {
                Object first = ((List<Object>) choices).get(0);
                if (first instanceof Map) {
                    Object message = ((Map<String, Object>) first).get("message");
                    if (message instanceof Map) {
                        Object content = ((Map<String, Object>) message).get("content");
                        return content instanceof String ? (String) content : "";
                    }
                }
            }
            return "";
        }
        Object outputText = body.get("output_text");
        if (outputText instanceof String) return (String) outputText;
        StringBuilder sb = new StringBuilder();
        Object output = body.get("output");
        if (output instanceof List) {
            for (Object part : (List<Object>) output) {
                if (!(part instanceof Map)) continue;
                Object contents = ((Map<String, Object>) part).get("content");
                if (!(contents instanceof List)) continue;
                for (Object c : (List<Object>) contents) {
                    if (c instanceof Map) {
                        Object t = ((Map<String, Object>) c).get("text");
                        if (t instanceof String) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(t);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }
}

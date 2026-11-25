package com.wimoor.ai.research;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线单文件研究报告（移植自 src/lib/selection-research/report.ts）。
 * 所有结论均来自已保存证据与代码重算；不含外部资源、脚本或供应商品牌。
 */
public final class Report {

    public static final class Image {
        public String evidenceId;
        public String mime;
        public String base64;
        public String alt;
    }

    private static final List<String> LABELS = List.of("细分市场", "深度市场研究", "评论与改良机会", "关税与到岸成本", "决策与风险");

    private Report() {}

    private static String esc(Object value) {
        if (value == null) return "";
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String json(Object value) {
        try { return ResearchJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (Exception e) { throw new ResearchError("RESEARCH_REPORT_JSON_FAILED", e); }
    }

    private static String creditSection(ResearchTypes.ResearchRun run) {
        ResearchBudget.CreditUsage usage = ResearchBudget.researchCreditUsage(run);
        StringBuilder rows = new StringBuilder();
        for (ResearchTypes.CallRecord call : run.calls) {
            if (!call.paid || "NOT_SENT".equals(call.dispatch)) continue;
            ResearchCredits.ResearchCharge charge = call.researchCharge;
            String amount = charge == null ? "待核实" : esc(ChargeAccess.amount(charge)) + " " + esc(ChargeAccess.unit(charge));
            String credits = charge == null ? "待核实" : esc(ResearchCredits.chargeCredits(charge));
            String nature = charge == null ? "待核实"
                    : "confirmed".equals(ChargeAccess.status(charge)) ? "确认实耗" : "估算";
            String basis = charge == null ? "尚无价格或凭据"
                    : esc(ChargeAccess.cnyPerUnit(charge) + "人民币/原始单位 · " + ChargeAccess.rateDate(charge)
                        + " · " + ChargeAccess.rateSource(charge) + " · " + ChargeAccess.receipt(charge));
            rows.append("<tr><td>").append(esc(call.id)).append(" · ").append(esc(call.provider))
                    .append("</td><td>").append(amount).append("</td><td>").append(credits)
                    .append("</td><td>").append(nature).append("</td><td>").append(basis).append("</td></tr>");
        }
        String total = usage.total == null ? "待核实，不能按零费用计" : usage.total.toPlainString() + " 积分";
        return "<h3>研究服务积分</h3><p>1研究积分=¥0.01。已确认 " + usage.confirmed.toPlainString()
                + " 积分；估算 " + usage.estimated.toPlainString() + " 积分；待核实 " + usage.pendingCalls
                + " 笔。市场请求总实耗：" + total
                + "。AI模型费用尚未取得凭据，未计入此市场请求汇总；商品到岸成本独立测算。</p>"
                + "<table><thead><tr><th>请求与来源</th><th>原始费用</th><th>研究积分</th><th>费用性质</th><th>换算依据与凭据</th></tr></thead><tbody>"
                + rows + "</tbody></table>";
    }

    private static String reviewSection(ResearchTypes.StageResult result) {
        if (result.reviewEvidence == null) return "";
        ReviewEvidenceModel.PreparedReviews reviews = ReviewEvidenceModel.prepareLowStarReviews(result.reviewEvidence);
        List<ReviewEvidenceModel.RankedProblem> problems = ReviewEvidenceModel.rankReviewProblems(
                reviews, result.reviewProblems == null ? List.of() : result.reviewProblems);
        Map<String, Object> payload = Map.of("retained", reviews.retained, "excluded", reviews.excluded,
                "counts", Map.of("currentChild", reviews.currentChild, "parentHistory", reviews.parentHistory,
                        "unknownScope", reviews.unknownScope),
                "limitations", reviews.limitations, "problems", problems);
        return "<h3>低星评论样本与改良证据</h3><pre>" + esc(json(payload)) + "</pre>";
    }

    private static String landedCostSection(ResearchTypes.StageResult result) {
        if (result.landedCostScenarios == null) return "";
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LandedCost.LandedCostScenario scenario : result.landedCostScenarios) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("scenario", scenario);
            row.put("result", LandedCost.calculateLandedCostScenario(scenario));
            rows.add(row);
        }
        return "<h3>关税与到岸成本情景（代码重算）</h3><p>候选税目、待确认税率和压力测试仅作为预算情景；计算成功不表示税率已核实。</p><pre>"
                + esc(json(rows)) + "</pre>";
    }

    private static String decisionSection(ResearchTypes.StageResult result) {
        if (result.decisionAnalysis == null) return "";
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Economics.ResearchEconomicsScenario scenario : result.decisionAnalysis.scenarios) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("scenario", scenario);
            row.put("result", Economics.evaluateResearchEconomics(scenario));
            scenarios.add(row);
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Economics.ProductScorecard card : result.decisionAnalysis.scorecards) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("card", card);
            row.put("result", Economics.evaluateProductScorecard(card));
            cards.add(row);
        }
        return "<h3>评分与利润重算</h3><pre>" + esc(json(Map.of("scenarios", scenarios, "scorecards", cards))) + "</pre>";
    }

    private static String evidenceSection(ResearchTypes.ResearchRun run, Set<String> citedIds) {
        StringBuilder sb = new StringBuilder("<section id=\"evidence\"><h2>原始证据</h2><p>请求时间不代表来源数据日期；样本不能等同于全市场。</p>");
        for (ResearchTypes.CallRecord call : run.calls) {
            if (!citedIds.contains(call.id)) continue;
            sb.append("<details id=\"e-").append(esc(call.id)).append("\"><summary>")
                    .append(esc(call.tool)).append(" · ").append(esc(call.requestedAt)).append(" · ")
                    .append(esc(call.status)).append("</summary><pre>").append(esc(json(call))).append("</pre></details>");
        }
        return sb.append("</section>").toString();
    }

    /** 完成态报告：五个阶段 + 原始证据 + 审批预算；重渲染同一已批准快照。 */
    public static String renderResearchReport(ResearchTypes.ResearchRun run, List<Image> images) {
        if (!"COMPLETED".equals(run.status) || run.results.size() != 5
                || run.decisions.stream().noneMatch(d -> "SELECT".equals(d.decision.kind))
                || run.decisions.stream().noneMatch(d -> "CONFIRM".equals(d.decision.kind)))
            throw new ResearchError("REPORT_REQUIRES_BOTH_HUMAN_GATES");
        ResearchSchema.rejectCredentials(run);
        String approvedAt = "";
        for (int i = run.decisions.size() - 1; i >= 0; i--)
            if ("CONFIRM".equals(run.decisions.get(i).decision.kind)) { approvedAt = run.decisions.get(i).at; break; }
        StringBuilder media = new StringBuilder();
        if (images != null) {
            for (Image image : images) {
                boolean cited = run.calls.stream().anyMatch(c -> c.id.equals(image.evidenceId) && "SUCCEEDED".equals(c.status));
                if (!cited || image.base64 == null || !image.base64.matches("^[A-Za-z0-9+/]+={0,2}$"))
                    throw new ResearchError("IMAGE_EVIDENCE_REQUIRED");
                byte[] bytes = Base64.getDecoder().decode(image.base64);
                boolean valid = bytes.length <= 2_000_000
                        && ("image/png".equals(image.mime) && startsWith(bytes, new int[]{137, 80, 78, 71, 13, 10, 26, 10})
                        || "image/jpeg".equals(image.mime) && bytes.length > 2 && (bytes[0] & 0xff) == 255 && (bytes[1] & 0xff) == 216 && (bytes[2] & 0xff) == 255
                        || "image/webp".equals(image.mime) && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 12).equals("WEBP"));
                if (!valid) throw new ResearchError("INVALID_EMBEDDED_IMAGE");
                media.append("<figure><img style=\"max-width:320px\" alt=\"").append(esc(image.alt))
                        .append("\" src=\"data:").append(esc(image.mime)).append(";base64,").append(image.base64)
                        .append("\"><figcaption>").append(esc(image.alt)).append(" · 证据 ")
                        .append(esc(image.evidenceId)).append("</figcaption></figure>");
            }
        }
        StringBuilder sections = new StringBuilder();
        Set<String> citedIds = new LinkedHashSet<>();
        for (int i = 0; i < run.results.size(); i++) {
            ResearchTypes.StageResult result = run.results.get(i);
            sections.append("<section id=\"stage-").append(i + 1).append("\"><h2>").append(i + 1).append(". ")
                    .append(LABELS.get(i)).append("</h2><p>").append(esc(result.summary)).append("</p>");
            if (result.verdict != null) sections.append("<p><strong>结论：").append(esc(result.verdict)).append("</strong></p>");
            if (i == 1) sections.append(media);
            if (i == 2) sections.append(reviewSection(result));
            if (i == 4) sections.append(decisionSection(result));
            if (i == 3) sections.append(landedCostSection(result));
            sections.append("<table><thead><tr><th>结论</th><th>数据性质</th><th>公式 / 适用日期</th><th>证据</th></tr></thead><tbody>");
            for (ResearchTypes.Claim claim : result.claims) {
                sections.append("<tr><td>").append(esc(claim.text)).append("</td><td>").append(esc(claim.kind))
                        .append("</td><td>").append(esc(claim.formula)).append(" ").append(esc(claim.applicableDate))
                        .append(" ").append(esc(claim.sourceUrl)).append("</td><td>");
                for (String id : claim.evidenceIds) {
                    citedIds.add(id);
                    sections.append("<a href=\"#e-").append(esc(id)).append("\">").append(esc(id)).append("</a><br>");
                }
                sections.append("</td></tr>");
            }
            sections.append("</tbody></table></section>");
        }
        collectCitedIds(run, citedIds);
        Map<String, ResearchBudget.ProviderUsage> budgetEntries = new java.util.LinkedHashMap<>();
        for (ResearchTypes.CallRecord call : run.calls) {
            if (!call.paid || "NOT_SENT".equals(call.dispatch)) continue;
            ResearchBudget.ProviderUsage usage = budgetEntries.computeIfAbsent(call.provider, k -> new ResearchBudget.ProviderUsage());
            usage.calls++;
            if ("UNKNOWN".equals(call.billing)) usage.unknownBillingCalls++;
        }
        StringBuilder chart = new StringBuilder();
        chart.append("<svg viewBox=\"0 0 600 ").append(Math.max(60, budgetEntries.size() * 45))
                .append("\" role=\"img\" aria-label=\"各数据源预算单位使用与上限\">");
        double maximum = 1;
        for (ResearchTypes.ToolPolicy p : run.policies.values()) maximum = Math.max(maximum, p.maxCalls);
        for (Map.Entry<String, Integer> e : run.limits.entrySet()) maximum = Math.max(maximum, e.getValue());
        int i = 0;
        for (Map.Entry<String, ResearchBudget.ProviderUsage> e : budgetEntries.entrySet()) {
            int limit = run.limits.getOrDefault(e.getKey(), 0);
            int committed = e.getValue().calls;
            chart.append("<text x=\"0\" y=\"").append(i * 45 + 20).append("\" font-size=\"12\">").append(esc(e.getKey())).append("</text>")
                    .append("<rect x=\"140\" y=\"").append(i * 45 + 5).append("\" width=\"")
                    .append(limit / maximum * 340).append("\" height=\"20\" fill=\"#e2e8f0\"/>")
                    .append("<rect x=\"140\" y=\"").append(i * 45 + 5).append("\" width=\"")
                    .append(Math.min(committed / maximum * 340, 340)).append("\" height=\"20\" fill=\"#2563eb\"/>")
                    .append("<text x=\"490\" y=\"").append(i * 45 + 20).append("\" font-size=\"12\">")
                    .append(committed).append(" / ").append(limit).append("</text>");
            i++;
        }
        chart.append("</svg>");
        String budgetBlock = "uncapped".equals(run.budgetMode)
                ? "<p>任务调用次数不限额；规划约18–30次。研究积分1积分=¥0.01；未核实费用不计入确认实耗，商品到岸成本单独测算。</p>"
                : "mock_unlimited".equals(run.budgetMode)
                    ? "<p class=\"warning\">" + esc(ResearchBudget.preview(run).message) + "</p>"
                    : chart.toString();
        StringBuilder nav = new StringBuilder();
        for (int j = 0; j < LABELS.size(); j++)
            nav.append("<a href=\"#stage-").append(j + 1).append("\">").append(j + 1).append(". ").append(LABELS.get(j)).append("</a>");
        nav.append("<a href=\"#evidence\">原始证据</a><a href=\"#audit\">审批与预算</a>");
        String warning = "fixture".equals(run.mode)
                ? "<p class=\"warning\">离线测试报告：所有市场记录均为测试样本，不代表真实行情，不能用于采购或投资决策。</p>" : "";
        String asin = run.asin == null ? "" : " · 目标 ASIN：" + esc(run.asin);
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'\">"
                + "<title>AI 选品研究报告</title><style>body{max-width:1200px;margin:auto;padding:24px;color:#172033;font:15px/1.6 system-ui,sans-serif}"
                + "nav{display:flex;flex-wrap:wrap;gap:16px;padding:12px;background:#eef2f7}table{width:100%;border-collapse:collapse;margin:16px 0}"
                + "td,th{padding:10px;border:1px solid #cbd5e1;text-align:left;vertical-align:top;overflow-wrap:anywhere}th{background:#eef2f7}"
                + "section{margin:32px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}details{border:1px solid #cbd5e1;padding:12px;margin:8px 0}"
                + "svg{width:100%;max-width:700px}a{color:#1d4ed8}.warning{padding:16px;background:#fff4cf}"
                + "@media print{body{max-width:none;padding:0}nav{display:none}h2{break-after:avoid}details{break-inside:avoid}a{color:inherit}table{font-size:11px}}</style></head><body>"
                + "<h1>AI 选品研究报告</h1><p>审批定稿时间：" + esc(approvedAt) + " · 查询市场：" + esc(run.marketplace) + asin
                + " · 项目：" + esc(run.id) + "</p><p>报告由本地选品研究引擎依据保存证据整理，AI 结论须结合原始记录审阅。</p>"
                + warning + "<p>" + esc(run.requirement) + "</p><nav>" + nav + "</nav>" + sections
                + "<section id=\"audit\"><h2>调用与审批</h2>" + creditSection(run)
                + "<p>图表按各服务政策的 unit 展示已占用次数或积分 / 获批上限，不是费用金额。calls 仅表示请求次数；单次金额未知时必须查看扣费回执，不同服务的单位不能相加。使用浏览器打印功能可保存 PDF。</p>"
                + budgetBlock + "<pre>" + esc(json(auditJson(run))) + "</pre></section>"
                + evidenceSection(run, citedIds) + "</body></html>";
    }

    public static String renderResearchReport(ResearchTypes.ResearchRun run) {
        return renderResearchReport(run, List.of());
    }

    /** 阶段审阅稿：只覆盖已完成阶段，不代表最终审批或上架授权。 */
    public static String renderResearchStageReport(ResearchTypes.ResearchRun run, int stage) {
        if (stage < 1 || stage > 5 || run.results.size() < stage || run.results.get(stage - 1) == null)
            throw new ResearchError("RESEARCH_STAGE_REPORT_UNAVAILABLE");
        ResearchSchema.rejectCredentials(run);
        ResearchTypes.StageResult result = run.results.get(stage - 1);
        Set<String> citedIds = new LinkedHashSet<>(LandedCost.landedCostEvidenceIds(result.landedCostScenarios));
        for (ResearchTypes.Claim claim : result.claims) citedIds.addAll(claim.evidenceIds);
        if (result.reviewEvidence != null)
            for (ReviewEvidenceModel.ReviewEvidence row : result.reviewEvidence) citedIds.add(row.evidenceId);
        if (result.decisionAnalysis != null) {
            for (Economics.ResearchEconomicsScenario scenario : result.decisionAnalysis.scenarios)
                for (Economics.CostBasis basis : scenario.basis.values()) if (basis != null) citedIds.addAll(basis.evidenceIds);
            for (Economics.ProductScorecard card : result.decisionAnalysis.scorecards)
                for (Economics.Dimension d : card.dimensions) citedIds.addAll(d.evidenceIds);
        }
        StringBuilder claims = new StringBuilder();
        for (ResearchTypes.Claim claim : result.claims) {
            claims.append("<article><p>").append(esc(claim.kind)).append(" · ").append(esc(claim.text)).append("</p><p>")
                    .append(esc(claim.formula)).append(" ").append(esc(claim.applicableDate)).append(" ")
                    .append(esc(claim.sourceUrl)).append("</p>");
            for (String id : claim.evidenceIds)
                claims.append("<a href=\"#e-").append(esc(id)).append("\">").append(esc(id)).append("</a> ");
            claims.append("</article>");
        }
        StringBuilder candidates = new StringBuilder();
        if (result.candidates != null) {
            candidates.append("<h2>候选方向</h2><ul>");
            for (ResearchTypes.Candidate row : result.candidates)
                candidates.append("<li>").append(esc(row.name)).append(" · ").append(esc(row.id)).append("</li>");
            candidates.append("</ul>");
        }
        String snapshot = "fixture".equals(run.mode) ? "隔离样本，不代表真实市场。" : "数据不足之处保留待验证。";
        String skillName = run.skills.size() >= stage ? run.skills.get(stage - 1).name : null;
        String skillSha = run.skills.size() >= stage ? run.skills.get(stage - 1).sha256 : null;
        Map<String, Object> budgetJson = Map.of("preview", ResearchBudget.preview(run),
                "usage", ResearchBudget.researchUsage(run), "researchCredits", ResearchBudget.researchCreditUsage(run),
                "creditAdjustments", run.creditAdjustments == null ? List.of() : run.creditAdjustments,
                "skill", Map.of("name", skillName == null ? "" : skillName, "sha256", skillSha == null ? "" : skillSha));
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
                + "<title>第" + stage + "阶段研究报告</title><style>body{max-width:1100px;margin:auto;padding:24px;font:15px/1.6 system-ui;color:#172033}"
                + "pre{white-space:pre-wrap;overflow-wrap:anywhere}article,details{padding:12px;border:1px solid #cbd5e1;margin:12px 0}.warning{background:#fff4cf;padding:12px}</style></head><body>"
                + "<h1>第" + stage + "阶段研究报告 · 待人工审阅</h1><p class=\"warning\">本报告仅覆盖已完成阶段，不代表最终审批、采购或上架授权。"
                + snapshot + "</p><p>项目：" + esc(run.id) + " · 市场：" + esc(run.marketplace) + " · 保存版本：" + run.revision
                + "</p><p>" + esc(run.requirement) + "</p><p>" + esc(result.summary) + "</p>" + candidates
                + "<h2>结论与证据</h2>" + claims + reviewSection(result) + decisionSection(result) + landedCostSection(result)
                + evidenceSection(run, citedIds) + "<h2>阶段预算与调用用量</h2>" + creditSection(run)
                + "<pre>" + esc(json(budgetJson)) + "</pre></body></html>";
    }

    private static Map<String, Object> auditJson(ResearchTypes.ResearchRun run) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("decisions", run.decisions);
        out.put("creditAdjustments", run.creditAdjustments == null ? List.of() : run.creditAdjustments);
        out.put("paidCallAuthorization", run.paidCallAuthorization);
        return out;
    }

    private static void collectCitedIds(ResearchTypes.ResearchRun run, Set<String> citedIds) {
        for (ResearchTypes.StageResult result : run.results) {
            if (result.reviewEvidence != null)
                for (ReviewEvidenceModel.ReviewEvidence row : result.reviewEvidence) citedIds.add(row.evidenceId);
            if (result.landedCostScenarios != null) citedIds.addAll(LandedCost.landedCostEvidenceIds(result.landedCostScenarios));
            if (result.decisionAnalysis != null) {
                for (Economics.ResearchEconomicsScenario s : result.decisionAnalysis.scenarios)
                    for (Economics.CostBasis b : s.basis.values()) if (b != null) citedIds.addAll(b.evidenceIds);
                for (Economics.ProductScorecard c : result.decisionAnalysis.scorecards)
                    for (Economics.Dimension d : c.dimensions) citedIds.addAll(d.evidenceIds);
            }
        }
    }

    private static boolean startsWith(byte[] bytes, int[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 0xff) != prefix[i]) return false;
        return true;
    }

    private static String ascii(byte[] bytes, int from, int to) {
        if (bytes.length < to) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append((char) (bytes[i] & 0x7f));
        return sb.toString();
    }
}

package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 评论证据整理与改良问题排序（移植自 src/lib/selection-research/review-evidence.ts）。
 * 只映射适配器保留的真实评论：不做情感/类别推断，空文、无关、不适用、重复评论一律排除。
 */
public final class ReviewEvidenceModel {

    public static final Set<String> SCOPES = Set.of("current_child", "parent_history", "unknown");

    public static final class ReviewEvidence {
        public String evidenceId;
        public String asin;
        public String reviewId;
        public Integer stars;
        public String date;
        public String variant;
        public String body;
        public String scope;
        public Boolean relevant;
        public Boolean applicable;

        public ReviewEvidence() {}
    }

    public static final class ReviewProblem {
        public String category;
        public List<String> reviewKeys = new ArrayList<>();
        public int severity;
        public int improvementValue;
        public String suggestion;

        public ReviewProblem() {}
    }

    public static final class RetainedReview {
        public final ReviewEvidence row;
        public final String key;

        public RetainedReview(ReviewEvidence row, String key) {
            this.row = row;
            this.key = key;
        }
    }

    public static final class ExcludedReview {
        public final ReviewEvidence row;
        public final String reason;

        public ExcludedReview(ReviewEvidence row, String reason) {
            this.row = row;
            this.reason = reason;
        }
    }

    public static final class PreparedReviews {
        public final List<RetainedReview> retained;
        public final List<ExcludedReview> excluded;
        public final int currentChild;
        public final int parentHistory;
        public final int unknownScope;
        public final List<String> limitations;

        public PreparedReviews(List<RetainedReview> retained, List<ExcludedReview> excluded,
                               int currentChild, int parentHistory, int unknownScope, List<String> limitations) {
            this.retained = retained;
            this.excluded = excluded;
            this.currentChild = currentChild;
            this.parentHistory = parentHistory;
            this.unknownScope = unknownScope;
            this.limitations = limitations;
        }
    }

    public static final class RankedProblem {
        public final String category;
        public final List<String> reviewKeys;
        public final int severity;
        public final int improvementValue;
        public final String suggestion;
        public final int count;
        public final int currentChildCount;
        public final List<String> evidenceIds;

        public RankedProblem(ReviewProblem problem, int count, int currentChildCount, List<String> evidenceIds) {
            this.category = problem.category;
            this.reviewKeys = problem.reviewKeys;
            this.severity = problem.severity;
            this.improvementValue = problem.improvementValue;
            this.suggestion = problem.suggestion;
            this.count = count;
            this.currentChildCount = currentChildCount;
            this.evidenceIds = evidenceIds;
        }
    }

    private ReviewEvidenceModel() {}

    private static String bodyKey(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.US).replaceAll("\\s+", " ").strip();
    }

    /** 低星（1–3）样本整理：保留原始正文，逐条给出排除原因与样本局限。 */
    public static PreparedReviews prepareLowStarReviews(List<ReviewEvidence> rows) {
        List<RetainedReview> retained = new ArrayList<>();
        List<ExcludedReview> excluded = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenBodies = new HashSet<>();
        for (ReviewEvidence row : rows) {
            if (row == null || !SCOPES.contains(row.scope)
                    || row.relevant != null && !(row.relevant instanceof Boolean)
                    || row.applicable != null && !(row.applicable instanceof Boolean))
                throw new ResearchError("INVALID_REVIEW_EVIDENCE");
            String body = row.body == null ? null : row.body.strip();
            String id = row.reviewId;
            String normalized = body != null && !body.isEmpty() ? row.asin + ":" + bodyKey(body) : null;
            if (row.evidenceId == null || row.evidenceId.isEmpty() || row.asin == null || row.asin.isEmpty())
                throw new ResearchError("REVIEW_SOURCE_REQUIRED");
            String reason = null;
            if (body == null || body.isEmpty()) reason = "EMPTY_BODY";
            else if (row.stars < 1 || row.stars > 3) reason = "NOT_LOW_STAR";
            else if (Boolean.FALSE.equals(row.relevant)) reason = "UNRELATED_PRODUCT";
            else if (Boolean.FALSE.equals(row.applicable)) reason = "NOT_APPLICABLE";
            else if (id != null && seenIds.contains(id) || normalized != null && seenBodies.contains(normalized)) reason = "DUPLICATE";
            if (reason != null) {
                excluded.add(new ExcludedReview(row, reason));
                continue;
            }
            if (id != null) seenIds.add(id);
            if (normalized != null) seenBodies.add(normalized);
            retained.add(new RetainedReview(row, "review-" + (retained.size() + 1)));
        }
        int currentChild = 0, parentHistory = 0, unknown = 0;
        for (RetainedReview r : retained) {
            if ("current_child".equals(r.row.scope)) currentChild++;
            else if ("parent_history".equals(r.row.scope)) parentHistory++;
            else unknown++;
        }
        List<String> limitations = new ArrayList<>();
        limitations.add("评论样本不是总体问题发生率");
        if (currentChild < 20) limitations.add("当前子体低星样本少于20条；问题频率置信度低（本地研究阈值）");
        boolean incomplete = retained.stream().anyMatch(r -> r.row.date == null || r.row.variant == null
                || r.row.relevant == null || r.row.applicable == null);
        if (incomplete) limitations.add("部分日期、变体、相关性或适用性待确认");
        return new PreparedReviews(retained, excluded, currentChild, parentHistory, unknown, limitations);
    }

    /** 每个问题必须引用已保留评论键；按严重度→改良价值→样本数排序。 */
    public static List<RankedProblem> rankReviewProblems(PreparedReviews prepared, List<ReviewProblem> problems) {
        java.util.Map<String, RetainedReview> byKey = new java.util.HashMap<>();
        for (RetainedReview r : prepared.retained) byKey.put(r.key, r);
        List<RankedProblem> ranked = new ArrayList<>();
        for (ReviewProblem problem : problems) {
            if (problem.category == null || problem.category.strip().isEmpty()
                    || problem.suggestion == null || problem.suggestion.strip().isEmpty()
                    || problem.reviewKeys == null || problem.reviewKeys.isEmpty()
                    || problem.severity < 1 || problem.severity > 5
                    || problem.improvementValue < 1 || problem.improvementValue > 5)
                throw new ResearchError("INVALID_REVIEW_PROBLEM");
            List<String> keys = new ArrayList<>(new LinkedHashSet<>(problem.reviewKeys));
            List<RetainedReview> evidence = new ArrayList<>();
            for (String key : keys) {
                RetainedReview row = byKey.get(key);
                if (row == null) throw new ResearchError("REVIEW_EVIDENCE_MISSING");
                evidence.add(row);
            }
            int childCount = 0;
            Set<String> ids = new LinkedHashSet<>();
            for (RetainedReview r : evidence) {
                if ("current_child".equals(r.row.scope)) childCount++;
                ids.add(r.row.evidenceId);
            }
            ranked.add(new RankedProblem(problem, keys.size(), childCount, new ArrayList<>(ids)));
        }
        ranked.sort(Comparator.comparingInt((RankedProblem p) -> p.severity).reversed()
                .thenComparing(Comparator.comparingInt((RankedProblem p) -> p.improvementValue).reversed())
                .thenComparing(Comparator.comparingInt((RankedProblem p) -> p.count).reversed()));
        return ranked;
    }

    /** 在已保存的原始响应（含转义 JSON 文本块）中检查评论正文是否真实存在。 */
    public static boolean containsReviewBody(Object raw, String body) {
        if (raw == null) return false;
        if (raw instanceof String) {
            String text = (String) raw;
            if (text.equals(body)) return true;
            String lead = text.stripLeading();
            if (lead.startsWith("[") || lead.startsWith("{")) {
                try {
                    return containsReviewBody(ResearchSchema.MAPPER.readTree(text), body);
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        }
        if (raw instanceof JsonNode) {
            JsonNode node = (JsonNode) raw;
            if (node.isTextual()) return containsReviewBody(node.asText(), body);
            for (JsonNode child : node) if (containsReviewBody(child, body)) return true;
            return false;
        }
        if (raw instanceof Iterable) {
            for (Object o : (Iterable<?>) raw) if (containsReviewBody(o, body)) return true;
            return false;
        }
        if (raw instanceof java.util.Map) {
            for (Object v : ((java.util.Map<?, ?>) raw).values()) if (containsReviewBody(v, body)) return true;
            return false;
        }
        return false;
    }
}

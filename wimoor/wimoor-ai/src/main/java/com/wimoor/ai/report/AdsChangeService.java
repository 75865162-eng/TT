package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code amazon-ads/change-service.ts}.
 *
 * <p>Query/mutate boundary for ADS business-change drafts. Patch bodies are validated
 * with {@link AdsBusinessRules#parsePatch}, and every status transition is checked
 * with two-person separation (submit/cancel by the creator, approve/reject by someone
 * else). Drafts are ERP-side targets only; no Amazon HTTP request is implied.
 */
public final class AdsChangeService {

    private AdsChangeService() {}

    public static final class Draft {
        public String id;
        public String entityType;
        public String entityId;
        public String status = "DRAFT";
        public String creator;
        public Map<String, Object> patch;
        public String fingerprint;
    }

    /** In-memory draft store replacing Prisma. */
    public static final class Store {
        private final Map<String, Draft> drafts = new LinkedHashMap<>();

        public Draft find(String id) { return drafts.get(id); }

        public Draft save(Draft draft) {
            drafts.put(draft.id, draft);
            return draft;
        }

        public List<Draft> list() { return new ArrayList<>(drafts.values()); }
    }

    public static List<Draft> query(Store store, String entityType, String state) {
        List<Draft> result = new ArrayList<>();
        for (Draft draft : store.list()) {
            if (entityType != null && !entityType.equals(draft.entityType)) continue;
            if (state != null && !state.equals(draft.status)) continue;
            result.add(draft);
        }
        return result;
    }

    /** Creates or advances a draft; returns the resulting status. */
    public static String mutate(Store store, String id, String entityType, String entityId,
                                String actor, String action, Object patch, String fingerprint) {
        Draft draft = id == null ? null : store.find(id);
        if (draft == null) {
            if (!"submit".equals(action)) throw new AdsQueryError("广告草稿状态不允许此操作。", 409);
            draft = new Draft();
            draft.id = id == null ? "draft-" + AdsCanonical.digest(java.util.Arrays.asList(entityType, entityId, actor, patch)) : id;
            draft.entityType = entityType;
            draft.entityId = entityId;
            draft.creator = actor;
            draft.patch = AdsBusinessRules.parsePatch(entityType, patch);
            draft.fingerprint = fingerprint == null ? AdsBusinessRules.fingerprint(draft.patch) : fingerprint;
            draft.status = "DRAFT";
        }
        String next = AdsBusinessRules.assertDraftTransition(draft.status, action, actor, draft.creator);
        if (!"submit".equals(action)) {
            if (fingerprint != null && !fingerprint.equals(draft.fingerprint)) {
                throw new AdsQueryError("广告草稿内容已变化，请刷新后重试。", 409);
            }
        }
        draft.status = next;
        store.save(draft);
        return next;
    }
}

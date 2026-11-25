package com.wimoor.ai.research;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 付费调用一次性同意（移植自 src/lib/selection-research/paid-call-consent.ts）。
 * 仅可信运维在用户明确同意后调用；绝不暴露为模型/MCP 命令。授权只免重复页级审批，任务上限与业务门保留。
 */
public final class PaidCallConsent {

    public static final String PROVIDER = "lili-research-paid-consent-v1";

    public static final class Consent {
        public final String id;
        public final String approvedAt;
        public final String approvedByUserId;
        public boolean allowUncapped;

        public Consent(String id, String approvedAt, String approvedByUserId) {
            this.id = id;
            this.approvedAt = approvedAt;
            this.approvedByUserId = approvedByUserId;
        }

        public Consent withAllowUncapped(boolean allow) {
            this.allowUncapped = allow;
            return this;
        }
    }

    /** 磁盘/DB 设置适配器；key 由 scope + provider 组成，凭证不进入本模块。 */
    public interface SettingStore {
        Setting read(ResearchTypes.Scope scope, String provider);
        void upsert(ResearchTypes.Scope scope, String provider, boolean enabled, Map<String, Object> config);
    }

    public static final class Setting {
        public boolean enabled;
        public Map<String, Object> config;
    }

    /** 受信任的操作审批；对应源端 assertAmazonAction(scope, ownerId, "approve", tx, "agents")。 */
    public interface ActionAuthorizer {
        void assertApprove(ResearchTypes.Scope scope, String actorId);
    }

    public static final class Service {
        private final SettingStore store;
        private final List<Map<String, Object>> auditLog = new ArrayList<>();

        public Service(SettingStore store) { this.store = store; }

        public List<Map<String, Object>> auditLog() { return auditLog; }

        /** 只有 enabled、version=1、审批人等于当前 owner 且字段完整时才视为有效同意。 */
        public Consent read(ResearchTypes.Scope scope) {
            Setting row = store.read(scope, PROVIDER);
            if (row == null || !row.enabled || row.config == null) return null;
            Map<String, Object> config = row.config;
            if (!Integer.valueOf(1).equals(config.get("version"))
                    || !scope.ownerId.equals(config.get("approvedByUserId"))
                    || !(config.get("id") instanceof String) || !(config.get("approvedAt") instanceof String)) return null;
            return new Consent((String) config.get("id"), (String) config.get("approvedAt"), scope.ownerId);
        }

        public Consent save(ResearchTypes.Scope scope, boolean enabled, ActionAuthorizer authorizer) {
            authorizer.assertApprove(scope, scope.ownerId);
            Map<String, Object> consent = new LinkedHashMap<>();
            consent.put("version", 1);
            consent.put("id", UUID.randomUUID().toString());
            consent.put("approvedAt", Instant.now().toString());
            consent.put("approvedByUserId", scope.ownerId);
            store.upsert(scope, PROVIDER, enabled, consent);
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("action", "lili.research.paid_consent.saved");
            audit.put("userId", scope.ownerId);
            audit.put("workspaceId", scope.workspaceId);
            audit.put("enabled", enabled);
            audit.put("consentId", consent.get("id"));
            audit.put("purpose", "Human explicitly authorized paid research without repeated page approval; task limits and business gates retained.");
            auditLog.add(audit);
            return new Consent((String) consent.get("id"), (String) consent.get("approvedAt"), scope.ownerId);
        }
    }

    /** 进程内设置存储，供隔离测试使用。 */
    public static final class InMemorySettingStore implements SettingStore {
        private final Map<String, Setting> rows = new LinkedHashMap<>();

        private static String key(ResearchTypes.Scope scope, String provider) {
            return scope.organizationId + "|" + scope.workspaceId + "|" + scope.ownerId + "|" + provider;
        }

        @Override
        public Setting read(ResearchTypes.Scope scope, String provider) { return rows.get(key(scope, provider)); }

        @Override
        public void upsert(ResearchTypes.Scope scope, String provider, boolean enabled, Map<String, Object> config) {
            Setting s = new Setting();
            s.enabled = enabled;
            s.config = new LinkedHashMap<>(config);
            rows.put(key(scope, provider), s);
        }
    }

    private PaidCallConsent() {}
}

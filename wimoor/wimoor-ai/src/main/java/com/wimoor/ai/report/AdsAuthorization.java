package com.wimoor.ai.report;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code amazon-ads/authorization.ts}.
 *
 * <p>OAuth lifecycle and binding rules. The callback URI is validated strictly
 * (exact path, https outside local/test), authorization attempts are single-use and
 * expire, and an advertiser-account binding requires an internal profile ID plus the
 * currently observed value/revision (optimistic concurrency). Binding evidence is
 * recorded as a digest only; user-entered advertiser IDs are never treated as a
 * verified identity.
 */
public final class AdsAuthorization {

    private static final String CALLBACK_PATH = "/api/integrations/amazon/ads/oauth/callback";
    private static final Set<String> REGIONS = new HashSet<>(Arrays.asList("NA", "EU", "FE"));

    private AdsAuthorization() {}

    public static final class Scope {
        public String organizationId;
        public String workspaceId;

        public Scope() {}

        public Scope(String organizationId, String workspaceId) {
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
        }
    }

    public static final class OAuthSettings {
        public String clientId;
        public String clientSecret;
        public String redirectUri;
        public boolean local;
    }

    public static void assertSettings(OAuthSettings settings) {
        if (settings.clientId == null || settings.clientId.trim().isEmpty()
                || settings.clientSecret == null || settings.clientSecret.trim().isEmpty()
                || settings.redirectUri == null || settings.redirectUri.trim().isEmpty()) {
            throw new IllegalStateException("Amazon Ads OAuth 服务端配置未完成。");
        }
        URI url;
        try {
            url = URI.create(settings.redirectUri);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Amazon Ads OAuth 回调地址无效。");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase();
        boolean valid = (!settings.local && "https".equals(scheme))
                && url.getUserInfo() == null
                && (url.getRawQuery() == null || url.getRawQuery().isEmpty())
                && (url.getFragment() == null || url.getFragment().isEmpty())
                && CALLBACK_PATH.equals(url.getPath());
        if (!valid) throw new IllegalStateException("Amazon Ads OAuth 回调地址无效。");
    }

    public static final class Attempt {
        public String id;
        public String userId;
        public String organizationId;
        public String workspaceId;
        public String region;
        public String stateHash;
        public String redirectUri;
        public String status = "PENDING";
        public long expiresAt;
    }

    /** In-memory attempt store replacing Prisma. */
    public static final class Store {
        private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

        public void save(Attempt attempt) { attempts.put(attempt.id, attempt); }

        public Attempt find(String id) { return attempts.get(id); }
    }

    /** Creates a single-use OAuth attempt after validating region and settings. */
    public static Attempt start(Store store, Scope scope, String actor, String region,
                                OAuthSettings settings, String rawState, String stateHash,
                                long nowMillis, long ttlMs) {
        assertSettings(settings);
        if (!REGIONS.contains(region)) throw new IllegalStateException("Amazon Ads 登录区域无效。");
        for (Attempt attempt : store.attempts.values()) {
            if (actor.equals(attempt.userId) && "PENDING".equals(attempt.status)
                    && attempt.expiresAt <= nowMillis) attempt.status = "EXPIRED";
        }
        Attempt attempt = new Attempt();
        attempt.id = "oauth-" + AdsCanonical.digest(Arrays.asList(scope.organizationId,
                scope.workspaceId, actor, nowMillis));
        attempt.userId = actor;
        attempt.organizationId = scope.organizationId;
        attempt.workspaceId = scope.workspaceId;
        attempt.region = region;
        attempt.stateHash = stateHash == null ? AdsCanonical.digest(rawState) : stateHash;
        attempt.redirectUri = settings.redirectUri;
        attempt.expiresAt = nowMillis + ttlMs;
        store.save(attempt);
        return attempt;
    }

    /** Validates the OAuth callback before a token exchange. */
    public static void assertCallback(Attempt attempt, Scope scope, String actor,
                                      String state, String error, String code,
                                      OAuthSettings settings, long nowMillis) {
        if (attempt == null || !actor.equals(attempt.userId)
                || !scope.organizationId.equals(attempt.organizationId)
                || !scope.workspaceId.equals(attempt.workspaceId)
                || !"PENDING".equals(attempt.status) || attempt.expiresAt <= nowMillis) {
            throw new IllegalStateException("Amazon Ads 授权会话无效或已过期。");
        }
        if (error != null && !error.isEmpty()) {
            attempt.status = "FAILED";
            throw new IllegalStateException("Amazon Ads 授权未完成。请在 Amazon 授权页面确认访问权限后重试。");
        }
        if (code == null || code.length() > 4096) throw new IllegalStateException("Amazon Ads 授权响应无效。");
        if (!AdsCanonical.digest(state).equals(attempt.stateHash)) {
            throw new IllegalStateException("Amazon Ads 授权 state 校验失败。");
        }
        if (!settings.redirectUri.equals(attempt.redirectUri)) {
            throw new IllegalStateException("Amazon Ads OAuth 配置已变化，请重新开始授权。");
        }
        attempt.status = "EXCHANGING";
    }

    /** Advertiser Account ID validation; user-entered values are unverified evidence. */
    public static void assertAdvertiserAccountId(String value) {
        if (value == null) return;
        if (value.isEmpty() || value.length() > 512 || !value.trim().equals(value)) {
            throw new IllegalStateException("Amazon Ads Advertiser Account ID 无效。");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1f || c == 0x7f) throw new IllegalStateException("Amazon Ads Advertiser Account ID 无效。");
        }
    }

    /** Optimistic-concurrency check for advertiser binding. */
    public static void assertBindingPreconditions(String advertiserAccountId, String profileRecordId,
                                                  String expectedAdvertiserAccountId,
                                                  Long expectedRevision) {
        if (advertiserAccountId == null) return;
        if (profileRecordId == null || expectedAdvertiserAccountId == null || expectedRevision == null
                || expectedRevision < 0) {
            throw new IllegalStateException("账户绑定需要内部 Profile ID 和当前绑定值，请刷新后重试。");
        }
    }

    public static String stateHash(String value) { return AdsCanonical.digest(value); }

    /** Disconnect bookkeeping: active tasks cancelled, plan paused, domain disconnected. */
    public static void disconnect(List<AdsSyncService.Task> tasks, List<AdsSyncService.Plan> plans) {
        for (AdsSyncService.Task task : tasks) {
            if (Arrays.asList("WAITING", "QUEUED", "RUNNING", "RETRY_WAIT").contains(task.status)) {
                task.status = "CANCELLED";
                task.dispatchVersion++;
                task.errorCode = "ADS_AUTH_DISCONNECTED";
            }
        }
        for (AdsSyncService.Plan plan : plans) {
            plan.status = "PAUSED";
            plan.revision++;
            plan.lastErrorCode = "ADS_AUTH_DISCONNECTED";
        }
    }
}

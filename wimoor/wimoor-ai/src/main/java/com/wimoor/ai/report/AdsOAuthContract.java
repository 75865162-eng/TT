package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/oauth-contract.ts}.
 *
 * <p>Region-specific hosts, strict LWA token/profile parsing and an injectable OAuth
 * transport. Errors expose only locally constructed metadata — never an upstream body,
 * URL or token. Profile numeric IDs beyond safe-integer precision are rejected rather
 * than saved with a distorted identity, and duplicate profile IDs are refused.
 */
public final class AdsOAuthContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdsOAuthContract() {}

    public static final Map<String, String> ADS_REGION_HOSTS;
    public static final Map<String, String> LWA_TOKEN_HOSTS;
    public static final Map<String, String> LWA_CONSENT_HOSTS;
    static {
        ADS_REGION_HOSTS = hosts("https://advertising-api.amazon.com",
                "https://advertising-api-eu.amazon.com", "https://advertising-api-fe.amazon.com");
        LWA_TOKEN_HOSTS = hosts("https://api.amazon.com/auth/o2/token",
                "https://api.amazon.co.uk/auth/o2/token", "https://api.amazon.co.jp/auth/o2/token");
        LWA_CONSENT_HOSTS = hosts("https://www.amazon.com/ap/oa",
                "https://eu.account.amazon.com/ap/oa", "https://apac.account.amazon.com/ap/oa");
    }

    private static Map<String, String> hosts(String na, String eu, String fe) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("NA", na);
        map.put("EU", eu);
        map.put("FE", fe);
        return Collections.unmodifiableMap(map);
    }

    public static final class ConnectionError extends AdsSyncError {
        public final String operation;
        public final String region;
        public final Integer httpStatus;

        public ConnectionError(String operation, String region, String reason, Integer httpStatus, long retryAfterMs) {
            super("ADS_CONNECTION_" + operation + "_" + reason,
                    "NETWORK".equals(reason) || "TIMEOUT".equals(reason) || (httpStatus != null && (httpStatus == 429 || httpStatus >= 500)),
                    retryAfterMs);
            this.operation = operation;
            this.region = region;
            this.httpStatus = httpStatus;
        }
    }

    public static final class ProfilePayload {
        public String profileId;
        public String countryCode;
        public String currencyCode;
        public String timezone;
        public final Map<String, Object> accountInfo = new LinkedHashMap<>();
    }

    public interface OAuthTransport {
        Map<String, String> exchangeAuthorizationCode(String region, String code, String redirectUri,
                                                      String clientId, String clientSecret);

        String refreshAccessToken(String region, String refreshToken, String clientId, String clientSecret);

        List<ProfilePayload> listProfiles(String region, String clientId, String accessToken);
    }

    /** Strict profile response parser. */
    public static List<ProfilePayload> parseProfiles(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() > 5000) {
            throw new IllegalArgumentException("Amazon Ads Profile 响应无效。");
        }
        List<ProfilePayload> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : (List<?>) value) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Amazon Ads Profile 响应无效。");
            Map<?, ?> row = (Map<?, ?>) raw;
            Object rawProfileId = row.get("profileId");
            if (rawProfileId instanceof Number) {
                double d = ((Number) rawProfileId).doubleValue();
                if (d != Math.floor(d) || d < 0 || d > 9_007_199_254_740_991L) {
                    throw new IllegalArgumentException("Amazon Ads Profile 数字 ID 超出安全精度，不能保存可能失真的身份。");
                }
            }
            String profileId = String.valueOf(rawProfileId);
            Object countryCode = row.get("countryCode");
            if (!profileId.matches("\\d{1,30}") || !(countryCode instanceof String)
                    || !((String) countryCode).matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("Amazon Ads Profile 缺少有效身份字段。");
            }
            for (String field : Arrays.asList("currencyCode", "timezone")) {
                Object item = row.get(field);
                if (item != null && !(item instanceof String)) throw new IllegalArgumentException("Amazon Ads Profile 字段无效。");
            }
            Map<?, ?> accountInfo = row.get("accountInfo") instanceof Map
                    ? (Map<?, ?>) row.get("accountInfo") : Collections.emptyMap();
            for (String field : Arrays.asList("marketplaceStringId", "id", "type", "name")) {
                Object item = accountInfo.get(field);
                if (item != null && !(item instanceof String)) throw new IllegalArgumentException("Amazon Ads 账户信息无效。");
            }
            ProfilePayload profile = new ProfilePayload();
            profile.profileId = profileId;
            profile.countryCode = (String) countryCode;
            if (row.get("currencyCode") instanceof String) profile.currencyCode = (String) row.get("currencyCode");
            if (row.get("timezone") instanceof String) profile.timezone = (String) row.get("timezone");
            for (String field : Arrays.asList("marketplaceStringId", "id", "type", "name")) {
                if (accountInfo.get(field) instanceof String) profile.accountInfo.put(field, accountInfo.get(field));
            }
            if (accountInfo.get("validPaymentMethod") instanceof Boolean) {
                profile.accountInfo.put("validPaymentMethod", accountInfo.get("validPaymentMethod"));
            }
            if (!ids.add(profileId)) throw new IllegalArgumentException("Amazon Ads Profile 响应包含重复 ID。");
            rows.add(profile);
        }
        return rows;
    }

    /** Reuses the shared bounded retry-after parsing from the sync adapter. */
    public static long retryAfter(String value, long nowMillis) {
        if (value == null || value.isEmpty()) return 0L;
        long delay;
        if (value.trim().matches("\\d+")) {
            try {
                delay = Long.parseLong(value.trim()) * 1000L;
            } catch (NumberFormatException e) {
                return 0L;
            }
        } else {
            try {
                delay = java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() - nowMillis;
            } catch (RuntimeException e) {
                return 0L;
            }
        }
        return delay > 0 ? delay : 0L;
    }

    /** Fixture transport: deterministic tokens/profiles, no HTTP. */
    public static final class FixtureTransport implements OAuthTransport {
        private final Map<String, String> tokens = new LinkedHashMap<>();
        private final Map<String, List<ProfilePayload>> profiles = new LinkedHashMap<>();

        public FixtureTransport token(String region, String accessToken) {
            tokens.put(region, accessToken);
            return this;
        }

        public FixtureTransport profiles(String region, List<ProfilePayload> rows) {
            profiles.put(region, rows);
            return this;
        }

        @Override
        public Map<String, String> exchangeAuthorizationCode(String region, String code, String redirectUri,
                                                             String clientId, String clientSecret) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("refreshToken", "refresh-" + region);
            result.put("accessToken", tokens.getOrDefault(region, "access-" + region));
            return result;
        }

        @Override
        public String refreshAccessToken(String region, String refreshToken, String clientId, String clientSecret) {
            return tokens.getOrDefault(region, "access-" + region);
        }

        @Override
        public List<ProfilePayload> listProfiles(String region, String clientId, String accessToken) {
            return profiles.getOrDefault(region, Collections.emptyList());
        }
    }
}

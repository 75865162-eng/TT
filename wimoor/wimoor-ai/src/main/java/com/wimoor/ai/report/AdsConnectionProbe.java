package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code amazon-ads/connection-probe.ts}.
 *
 * <p>First-connection acceptance orchestrator, separate from the durable worker.
 * Calls are charged BEFORE dispatch (including failures), at most 4 requests per probe.
 * No implicit retry, follow-up page or persistence occurs — even a fully successful
 * probe reports {@code persisted: false}. Unknown errors are reduced to fixed
 * diagnostics so upstream bodies, tokens or signed URLs can never leak.
 */
public final class AdsConnectionProbe {

    private AdsConnectionProbe() {}

    public static final class Result {
        public String status = "BLOCKED";
        public final boolean persisted = false;
        public int apiRequests;
        public final List<Map<String, Object>> profiles = new ArrayList<>();
        public String selectedProfileId;
        public final List<Map<String, Object>> reads = new ArrayList<>();
        public Map<String, Object> failure;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("persisted", persisted);
            map.put("apiRequests", apiRequests);
            map.put("profiles", profiles);
            map.put("selectedProfileId", selectedProfileId);
            map.put("reads", reads);
            if (failure != null) map.put("failure", failure);
            return map;
        }
    }

    public interface Authorize {
        void authorize(String stage, String region, String profileId);
    }

    public interface Ports {
        String refresh(String region, String clientId, String clientSecret, String refreshToken);

        List<AdsOAuthContract.ProfilePayload> profiles(String region, String clientId, String accessToken);

        SyncAdapter.Page readPage(String stage, String profileId, String region,
                                  String accessToken, String clientId);
    }

    public static Result probe(String region, String profileId, String clientId,
                               String clientSecret, String refreshToken, int maxRequests,
                               Ports ports, Authorize authorize) {
        Result result = new Result();
        if (!Arrays.asList("NA", "EU", "FE").contains(region) || maxRequests < 1 || maxRequests > 4
                || (profileId != null && !profileId.matches("\\d{1,30}"))
                || hasBadText(clientId) || hasBadText(clientSecret) || hasBadText(refreshToken)) {
            throw new AdsSyncError("ADS_CONNECTION_PROBE_INPUT_INVALID");
        }
        String[] stage = {"refresh"};
        java.util.function.Supplier<Boolean> authorizeRequest = () -> {
            if (result.apiRequests >= maxRequests) throw new AdsSyncError("ADS_CONNECTION_PROBE_BUDGET_EXHAUSTED");
            authorize.authorize(stage[0], region, result.selectedProfileId);
            result.apiRequests++; // charge before dispatch, including failures
            return true;
        };
        try {
            authorizeRequest.get();
            String accessToken = ports.refresh(region, clientId, clientSecret, refreshToken);
            stage[0] = "profiles";
            authorizeRequest.get();
            List<AdsOAuthContract.ProfilePayload> discovered =
                    AdsOAuthContract.parseProfiles(ports.profiles(region, clientId, accessToken));
            for (AdsOAuthContract.ProfilePayload profile : discovered) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("profileId", profile.profileId);
                row.put("countryCode", profile.countryCode);
                row.put("marketplaceId", profile.accountInfo.get("marketplaceStringId"));
                row.put("currencyCode", profile.currencyCode);
                row.put("timezone", profile.timezone);
                result.profiles.add(row);
            }
            if (profileId != null && result.profiles.stream()
                    .noneMatch(p -> profileId.equals(p.get("profileId")))) {
                throw new AdsSyncError("ADS_CONNECTION_PROBE_PROFILE_NOT_DISCOVERED");
            }
            if (result.profiles.isEmpty()) throw new AdsSyncError("ADS_CONNECTION_PROBE_NO_PROFILES");
            if (profileId == null && result.profiles.size() != 1) {
                result.status = "PROFILE_SELECTION_REQUIRED";
                return result;
            }
            result.selectedProfileId = profileId == null
                    ? (String) result.profiles.get(0).get("profileId") : profileId;
            for (String structure : Arrays.asList("campaigns", "ad-groups")) {
                stage[0] = structure;
                authorizeRequest.get();
                SyncAdapter.Page page = SyncAdapter.validate(
                        ports.readPage(structure, result.selectedProfileId, region, accessToken, clientId), null);
                String expected = "campaigns".equals(structure) ? "SP:campaign" : "SP:ad-group";
                if (!page.reportFacts.isEmpty()
                        || page.entities.stream().anyMatch(e -> !expected.equals(e.entityType))) {
                    throw new AdsSyncError("ADS_CONNECTION_PROBE_ENTITY_TYPE_INVALID");
                }
                Map<String, Object> read = new LinkedHashMap<>();
                read.put("stage", structure);
                read.put("recordCount", page.entities.size());
                List<String> ids = new ArrayList<>();
                for (SyncAdapter.Entity entity : page.entities) ids.add(entity.externalId);
                read.put("externalIds", ids);
                read.put("hasMore", page.nextCursor != null);
                result.reads.add(read);
            }
            result.status = result.reads.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("hasMore")))
                    ? "READ_PARTIAL" : "READ_COMPLETE";
        } catch (RuntimeException error) {
            boolean known = error instanceof AdsSyncError;
            long retryAfter = error instanceof AdsOAuthContract.ConnectionError
                    ? ((AdsSyncError) error).getRetryAfterMs() : 0L;
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("stage", stage[0]);
            failure.put("code", known ? ((AdsSyncError) error).getCode() : "ADS_CONNECTION_PROBE_FAILED");
            failure.put("retryable", known && ((AdsSyncError) error).isRetryable());
            failure.put("retryAfterMs", retryAfter);
            result.failure = failure;
        }
        return result;
    }

    private static boolean hasBadText(String value) {
        if (value == null || value.trim().isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') return true;
        }
        return false;
    }
}

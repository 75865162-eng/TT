package com.wimoor.ai.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/sync-adapter.ts}.
 *
 * <p>Read-adapter contract for structure entities. An adapter must explicitly attest
 * complete/validated page semantics ({@code complete}); an unavailable domain must
 * fail BEFORE token exchange rather than return demo rows or claim success. The SP
 * adapter supports structure stages only — reports are deliberately unsupported.
 */
public final class SyncAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SyncAdapter() {}

    public static final class Entity {
        public String entityType;
        public String externalId;
        public String parentExternalId;
        public String state;
        public Map<String, Object> payload;
        public String sourceUpdatedAt;
    }

    public static final class ReportFactInput {
        public String reportType;
        public String adProduct;
        public String reportDate;
        public String factKey;
        public String campaignId;
        public String adGroupId;
        public String targetingId;
        public String advertisedAsin;
        public String searchTerm;
        public String placement;
        public long impressions;
        public long clicks;
        public java.math.BigDecimal spend;
        public long orders;
        public java.math.BigDecimal sales;
        public long units;
        public String attributionWindow;
        public String apiVersion;
        public Map<String, Object> rawDimensions;
    }

    public static final class Page {
        public final List<Entity> entities = new ArrayList<>();
        public final List<ReportFactInput> reportFacts = new ArrayList<>();
        public String nextCursor;
        public boolean complete;

        public Page() {}

        public Page(List<Entity> entities, String nextCursor, boolean complete) {
            this.entities.addAll(entities);
            this.nextCursor = nextCursor;
            this.complete = complete;
        }
    }

    public interface AdsReadAdapter {
        void assertImplemented();

        Page readPage(AdsSyncPolicy.Stage stage, String cursor, String profileId,
                      String region, String accessToken, String clientId);
    }

    public static boolean isAdsRegion(String value) {
        return "NA".equals(value) || "EU".equals(value) || "FE".equals(value);
    }

    public static boolean isDateOnly(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Validates a page before publication; any violation fails the whole page. */
    public static Page validate(Page page, String previousCursor) {
        if (page == null || !page.complete || page.entities.size() > 1000
                || (page.nextCursor != null && (page.nextCursor.isEmpty()
                || page.nextCursor.length() > 16384 || page.nextCursor.equals(previousCursor)))) {
            throw new AdsSyncError("ADS_PAGE_INVALID");
        }
        Set<String> keys = new HashSet<>();
        for (Entity row : page.entities) {
            if (row == null || row.entityType == null
                    || !row.entityType.matches("(SP|SB|SD):[a-z-]+")
                    || row.externalId == null || !row.externalId.matches("\\d{1,40}")
                    || row.payload == null
                    || (row.sourceUpdatedAt != null && !isFiniteDate(row.sourceUpdatedAt))) {
                throw new AdsSyncError("ADS_ENTITY_INVALID");
            }
            String key = json(Arrays.asList(row.entityType, row.externalId));
            if (!keys.add(key)) throw new AdsSyncError("ADS_DUPLICATE_ENTITY");
        }
        if (page.reportFacts.size() > 5000) throw new AdsSyncError("ADS_REPORT_PAGE_INVALID");
        for (ReportFactInput fact : page.reportFacts) {
            if (fact == null || fact.reportType == null || fact.reportType.isEmpty() || fact.reportType.length() > 100
                    || fact.adProduct == null || fact.adProduct.isEmpty()
                    || !isDateOnly(fact.reportDate) || fact.factKey == null
                    || !fact.factKey.matches("[a-f0-9]{64}")
                    || fact.apiVersion == null || fact.apiVersion.isEmpty() || fact.rawDimensions == null
                    || fact.impressions < 0 || fact.clicks < 0 || fact.orders < 0 || fact.units < 0
                    || fact.spend == null || fact.spend.signum() < 0
                    || fact.sales == null || fact.sales.signum() < 0) {
                throw new AdsSyncError("ADS_REPORT_FACT_INVALID");
            }
        }
        return page;
    }

    /** Stages for which a verified read adapter exists. */
    public static final Set<String> VERIFIED_STAGES = new HashSet<>(Arrays.asList(
            "campaigns", "ad-groups", "keywords", "targets", "negatives", "product-ads"));

    public static boolean adapterReady(List<String> requiredStages) {
        List<String> stages = requiredStages == null || requiredStages.isEmpty()
                ? Arrays.asList("campaigns", "ad-groups", "keywords", "targets", "negatives", "product-ads")
                : requiredStages;
        return !stages.isEmpty() && stages.stream().allMatch(VERIFIED_STAGES::contains);
    }

    /** Explicit stub: no live entity/report contract is verified; never returns demo rows. */
    public static final AdsReadAdapter PENDING_ADAPTER = new AdsReadAdapter() {
        @Override
        public void assertImplemented() { throw new AdsSyncError("ADS_READ_ADAPTER_NOT_IMPLEMENTED"); }

        @Override
        public Page readPage(AdsSyncPolicy.Stage stage, String cursor, String profileId,
                             String region, String accessToken, String clientId) {
            throw new AdsSyncError("ADS_READ_ADAPTER_NOT_IMPLEMENTED");
        }
    };

    /** Encodes the four-collection negative cursor; keeps ordering across restarts. */
    public static String nextNegativeCursor(int index, String token, int collectionCount) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("v", 1);
        state.put("index", index);
        state.put("token", token);
        String encoded = json(state);
        if (encoded.length() > 16384) throw new AdsSyncError("ADS_NEGATIVE_CHECKPOINT_INVALID");
        return encoded;
    }

    public static int[] parseNegativeCursor(String cursor, int collectionCount) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = MAPPER.readValue(cursor, Map.class);
            Object index = value.get("index");
            Object token = value.get("token");
            if (!(value.get("v") instanceof Integer) || (Integer) value.get("v") != 1
                    || !(index instanceof Integer) || (Integer) index < 0 || (Integer) index >= collectionCount
                    || (token != null && (!(token instanceof String) || ((String) token).isEmpty()
                    || ((String) token).length() > 16384))) {
                throw new AdsSyncError("ADS_NEGATIVE_CHECKPOINT_INVALID");
            }
            return new int[]{(Integer) index};
        } catch (JsonProcessingException e) {
            throw new AdsSyncError("ADS_NEGATIVE_CHECKPOINT_INVALID");
        }
    }

    /** Parses a Retry-After header into bounded milliseconds. */
    public static long parseRetryAfter(String value, long nowMillis) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.min(3_600_000L, Math.max(0L, seconds * 1000L));
        } catch (NumberFormatException e) {
            try {
                long delay = java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() - nowMillis;
                return Math.min(3_600_000L, Math.max(0L, delay));
            } catch (RuntimeException e2) {
                return 0L;
            }
        }
    }

    private static boolean isFiniteDate(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

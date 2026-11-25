package com.wimoor.ai.report;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic checks for the frozen-scope, deadline, freshness, retry and body rules. */
class ReportGateRulesTest {

    private static final long NOW = Instant.parse("2026-11-25T00:00:00.000Z").toEpochMilli();

    @Test
    void scopeDigestIsStableAcrossManifestKeyOrderAndRejectsFrozenScopeChange() {
        ReportScope.Task task = new ReportScope.Task();
        task.id = "task-1";
        task.organizationId = "org-1";
        task.workspaceId = "ws-1";
        task.requestedByUserId = "user-1";
        task.planId = "plan-1";
        task.planRevision = 3;
        task.profileId = "profile-1";
        task.requestedFrom = "2026-11-01";
        task.requestedTo = "2026-11-07";

        ReportScope.Profile profile = new ReportScope.Profile();
        profile.id = "profile-1";
        profile.authorizationId = "auth-1";
        profile.profileId = "123456789";
        profile.region = "NA";
        profile.sellerAccountId = "seller-1";
        profile.marketplaceId = "ATVPDKIKX0DER";
        profile.currencyCode = "USD";
        profile.timezone = "America/Los_Angeles";

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("apiVersion", "reporting-v3");
        first.put("reportType", "campaign");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("reportType", "campaign");
        second.put("apiVersion", "reporting-v3");

        String digest = ReportScope.digest(task, profile, 9, first);
        assertEquals(64, digest.length());
        assertEquals(digest, ReportScope.digest(task, profile, 9, second),
                "manifest key order must not change the digest");
        assertNotEquals(digest, ReportScope.digest(task, profile, 10, first),
                "authorization revision is part of the frozen scope");
        assertDoesNotThrow(() -> ReportScope.assertDigest(digest, digest));
        AdsQueryError error = assertThrows(AdsQueryError.class,
                () -> ReportScope.assertDigest(digest, "changed"));
        assertEquals("ADS_REPORT_FROZEN_SCOPE_CHANGED", error.getCode());
        assertEquals(409, error.getStatus());
    }

    @Test
    void deadlineMustParseAndBeStrictlyInTheFuture() {
        String future = Instant.ofEpochMilli(NOW + 60_000L).toString();
        assertDoesNotThrow(() -> ReportDeadline.assertDeadline(future, NOW));
        AdsQueryError expired = assertThrows(AdsQueryError.class,
                () -> ReportDeadline.assertDeadline(Instant.ofEpochMilli(NOW - 1).toString(), NOW));
        assertEquals("ADS_REPORT_DEADLINE_EXCEEDED", expired.getCode());
        assertThrows(AdsQueryError.class, () -> ReportDeadline.assertDeadline("not-a-date", NOW));
        assertThrows(AdsQueryError.class, () -> ReportDeadline.assertDeadline(null, NOW));
    }

    @Test
    void retryBackoffUsesConsumedCallCountAndCapsAtSixtySeconds() {
        assertEquals(NOW + 1_000, ReportRetry.retryAt(1, NOW));
        assertEquals(NOW + 2_000, ReportRetry.retryAt(2, NOW));
        assertEquals(NOW + 4_000, ReportRetry.retryAt(3, NOW));
        assertEquals(NOW + 32_000, ReportRetry.retryAt(6, NOW));
        assertEquals(NOW + 60_000, ReportRetry.retryAt(7, NOW));
        assertEquals(NOW + 60_000, ReportRetry.retryAt(100, NOW), "exponent clamp at 6");
        assertEquals(NOW + 5_000, ReportRetry.retryAt(1, "5", NOW));
        assertEquals(NOW + 120_000, ReportRetry.retryAt(1, "120", NOW),
                "upstream Retry-After may exceed the local backoff cap");
        assertEquals(ReportRetry.INFINITE, ReportRetry.retryAt(1, "999999999999999999999", NOW),
                "unrepresentable upstream wait must block forever, not shorten");
    }

    @Test
    void freshnessNeverClaimsCurrentWithoutAReportSla() {
        assertEquals("FAILED", ReportFreshness.publishedReportFreshness(true, "BLOCKED").name());
        assertEquals("FAILED", ReportFreshness.publishedReportFreshness(true, "CANCELLED").name());
        assertEquals("SYNCING", ReportFreshness.publishedReportFreshness(true, "RETRY_WAIT").name());
        assertEquals("MISSING", ReportFreshness.publishedReportFreshness(false, "CURRENT").name());
        assertEquals("STALE", ReportFreshness.publishedReportFreshness(true, "STALE").name());
        assertEquals("OBSERVED_PUBLISHED", ReportFreshness.publishedReportFreshness(true, null).name());

        List<ReportFreshness.DomainState> states = new ArrayList<>();
        states.add(state("p1", "", "OBSERVED_PUBLISHED", NOW - 1_000));
        states.add(state("p1", "campaign", "FAILED", NOW - 5_000));
        states.add(state("p1", "targeting", "STALE", NOW));
        ReportFreshness.DomainState selected = ReportFreshness.select(states, "p1", "campaign");
        assertEquals("campaign", selected.reportType, "exact domain beats wildcard");
        assertEquals("FAILED", selected.status);
        assertNull(ReportFreshness.select(states, "missing", null));

        assertEquals("FAILED", ReportFreshness.summarize(Arrays.asList("MISSING", "FAILED", "STALE")));
        assertEquals("PARTIAL", ReportFreshness.summarize(Arrays.asList("MISSING", "OBSERVED_PUBLISHED")));
        assertEquals("MISSING", ReportFreshness.summarize(Arrays.asList("MISSING", "MISSING")));
    }

    @Test
    void boundedBodyRejectsAbortRedirectLimitAndSizeMismatch() {
        assertEquals("ok", new String(ReportBody.readBounded("ok".getBytes(), false, 200,
                false, "2", 1024)));
        assertEquals("ADS_REPORT_OPERATION_TIMEOUT", assertThrows(AdsQueryError.class,
                () -> ReportBody.readBounded(new byte[0], true, 200, false, null, 10)).getCode());
        assertEquals("ADS_REPORT_HTTP_OR_REDIRECT_REJECTED", assertThrows(AdsQueryError.class,
                () -> ReportBody.readBounded(new byte[0], false, 302, true, null, 10)).getCode());
        assertEquals("ADS_REPORT_BODY_LIMIT", assertThrows(AdsQueryError.class,
                () -> ReportBody.readBounded(new byte[11], false, 200, false, null, 10)).getCode());
        assertEquals("ADS_REPORT_BODY_SIZE_MISMATCH", assertThrows(AdsQueryError.class,
                () -> ReportBody.readBounded(new byte[2], false, 200, false, "3", 10)).getCode());
    }

    @Test
    void unverifiedReportingV1DispatchIsAlwaysRejected() {
        assertFalse(ReportDispatch.LIVE_ENABLED);
        assertEquals(4, ReportDispatch.BLOCKERS.size());
        assertEquals("ADS_REPORT_V1_LIVE_DISPATCH_DISABLED", assertThrows(AdsQueryError.class,
                ReportDispatch::rejectUnverified).getCode());
    }

    private static ReportFreshness.DomainState state(String profileId, String reportType,
                                                      String status, long lastAttempt) {
        ReportFreshness.DomainState state = new ReportFreshness.DomainState();
        state.profileId = profileId;
        state.reportType = reportType;
        state.status = status;
        state.lastAttemptAt = lastAttempt;
        return state;
    }
}

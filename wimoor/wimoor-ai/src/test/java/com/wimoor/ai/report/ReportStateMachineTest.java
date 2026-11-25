package com.wimoor.ai.report;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic state-machine tests: checkpoints, budgets, phase advances and gates. */
class ReportStateMachineTest {

    private static final long NOW = Instant.parse("2026-11-25T00:00:00Z").toEpochMilli();
    private static final String DEADLINE = Instant.ofEpochMilli(NOW + 900_000L).toString();

    @Test
    void checkpointValidationNeverRepairsCountersOrReservations() {
        String valid = "{\"version\":1,\"phase\":\"POLL\",\"calls\":{\"create\":1,\"poll\":2,\"download\":0},"
                + "\"evidenceFileIds\":[\"f1\",\"f2\"],\"nextPollAt\":\"" + DEADLINE + "\"}";
        ReportAcquisition.AcquisitionState state = ReportCheckpoint.read(valid);
        assertEquals(ReportAcquisition.Phase.POLL, state.phase);
        assertEquals(2, state.calls.get(ReportAcquisition.Operation.poll).intValue());

        assertEquals("ADS_REPORT_CHECKPOINT_INVALID", assertThrows(AdsQueryError.class, () ->
                ReportCheckpoint.read("{\"version\":1,\"phase\":\"POLL\",\"calls\":{\"create\":2,\"poll\":0,\"download\":0},"
                        + "\"evidenceFileIds\":[]}")).getCode());
        assertThrows(AdsQueryError.class, () -> ReportCheckpoint.read(
                "{\"version\":1,\"phase\":\"POLL\",\"calls\":{\"create\":1,\"poll\":11,\"download\":0},\"evidenceFileIds\":[]}"));
        assertThrows(AdsQueryError.class, () -> ReportCheckpoint.read(
                "{\"version\":1,\"phase\":\"POLL\",\"calls\":{\"create\":1,\"poll\":1,\"download\":0},"
                        + "\"reserved\":{\"operation\":\"create\",\"fence\":1,\"owner\":\"w\"},\"evidenceFileIds\":[]}"));
        assertThrows(AdsQueryError.class, () -> ReportCheckpoint.read(
                "{\"version\":1,\"phase\":\"POLL\",\"calls\":{\"create\":1,\"poll\":1,\"download\":0},"
                        + "\"evidenceFileIds\":[\"f1\",\"f1\"]}"));
    }

    @Test
    void acquisitionReserveConsumesBudgetAndProtectsUnknownCreate() {
        ReportAcquisition.AcquisitionState state = new ReportAcquisition.AcquisitionState();
        state.deadline = DEADLINE;
        ReportAcquisition.Operation op = ReportAcquisition.reserve(state, 1, "worker-1", NOW);
        assertEquals(ReportAcquisition.Operation.create, op);
        assertEquals(1, state.calls.get(ReportAcquisition.Operation.create).intValue());
        assertEquals("ADS_REPORT_CREATE_RESULT_UNKNOWN", assertThrows(AdsQueryError.class,
                () -> ReportAcquisition.reserve(state, 1, "worker-1", NOW)).getCode(),
                "an interrupted create is never reissued, even by the same fence");
        assertEquals("ADS_REPORT_CREATE_RESULT_UNKNOWN", assertThrows(AdsQueryError.class,
                () -> ReportAcquisition.reserve(state, 2, "worker-2", NOW)).getCode());

        state.phase = ReportAcquisition.Phase.POLL;
        state.calls.put(ReportAcquisition.Operation.poll, 1);
        state.reserved = new ReportAcquisition.Reservation(
                ReportAcquisition.Operation.poll, 1, "worker-1");
        assertEquals("ADS_REPORT_OPERATION_IN_FLIGHT", assertThrows(AdsQueryError.class,
                () -> ReportAcquisition.reserve(state, 1, "worker-1", NOW)).getCode());
        assertEquals(ReportAcquisition.Operation.poll,
                ReportAcquisition.reserve(state, 2, "worker-2", NOW),
                "a replacement fence may retry read-only poll");

        state.calls.put(ReportAcquisition.Operation.poll, 10);
        state.phase = ReportAcquisition.Phase.POLL;
        state.reserved = null;
        assertEquals("ADS_REPORT_REQUEST_BUDGET_EXCEEDED", assertThrows(AdsQueryError.class,
                () -> ReportAcquisition.reserve(state, 2, "worker-2", NOW)).getCode());
    }

    @Test
    void acquisitionPhasesAdvanceThroughReceipts() {
        ReportAcquisition.AcquisitionState state = new ReportAcquisition.AcquisitionState();
        state.deadline = DEADLINE;
        assertEquals(ReportAcquisition.Operation.create, ReportAcquisition.reserve(state, 1, "w", NOW));
        state.phase = ReportAcquisition.Phase.POLL;
        state.reserved = null;
        assertEquals(ReportAcquisition.Operation.poll, ReportAcquisition.reserve(state, 2, "w", NOW));
        ReportingV1Transport.Result pending = new ReportingV1Transport.Result();
        pending.status = "PENDING";
        pending.reportId = "report-1";
        ReportingV1Acquisition.State v1State = new ReportingV1Acquisition.State();
        v1State.deadline = DEADLINE;
        assertEquals(ReportingV1Acquisition.Phase.POLL,
                ReportingV1Acquisition.applyReceipt(v1State, pending,
                        Collections.<ReportingV1Transport.Part>emptyList(), NOW));
        assertEquals(NOW + 1000L, ReportDeadline.parse(v1State.nextPollAt),
                "a pending report is polled again after 1s");

        assertEquals(ReportAcquisition.Phase.DOWNLOAD, ReportAcquisition.phaseAfterEnvelope("COMPLETED"));
        assertEquals(ReportAcquisition.Phase.POLL, ReportAcquisition.phaseAfterEnvelope("PROCESSING"));
        assertEquals("ADS_REPORT_UPSTREAM_FAILED", assertThrows(AdsQueryError.class,
                () -> ReportAcquisition.phaseAfterEnvelope("FAILED")).getCode());
    }

    @Test
    void runtimeDecisionBlocksRetriesBeyondDeadline() {
        ReportAcquisition.AcquisitionState acquisition = new ReportAcquisition.AcquisitionState();
        acquisition.deadline = DEADLINE;
        acquisition.calls.put(ReportAcquisition.Operation.poll, 1);
        ReportRuntime.Decision retry = ReportRuntime.decide("RUNNING", "POLL",
                "ADS_REPORT_THROTTLED", acquisition, NOW);
        assertEquals("RETRY_WAIT", retry.status);
        assertFalse(retry.blocked);

        acquisition.deadline = Instant.ofEpochMilli(NOW + 100L).toString();
        ReportRuntime.Decision beyond = ReportRuntime.decide("RUNNING", "POLL",
                "ADS_REPORT_THROTTLED", acquisition, NOW);
        assertEquals("BLOCKED", beyond.status);
        assertEquals("ADS_REPORT_RETRY_AFTER_DEADLINE", beyond.effectiveErrorCode);

        assertEquals("BLOCKED", ReportRuntime.decide("RUNNING", "CREATE",
                "ADS_REPORT_UPSTREAM_FAILED", acquisition, NOW).status);
        assertEquals("SUCCEEDED", ReportRuntime.decide("SUCCEEDED", "POLL",
                null, acquisition, NOW).status);
    }

    @Test
    void controlActionsRespectTerminalAndTwoPersonRules() {
        assertEquals("PAUSED", ReportRuntime.control("WAITING", "pause", "u", "u"));
        assertEquals("CANCELLED", ReportRuntime.control("PAUSED", "cancel", "u", "u"));
        assertEquals("WAITING", ReportRuntime.control("PAUSED", "resume", "u", "u"));
        assertEquals("ADS_REPORT_TASK_TERMINAL", assertThrows(AdsQueryError.class,
                () -> ReportRuntime.control("SUCCEEDED", "pause", "u", "u")).getCode());
        assertEquals("ADS_REPORT_TASK_NOT_PAUSED", assertThrows(AdsQueryError.class,
                () -> ReportRuntime.control("WAITING", "resume", "u", "u")).getCode());
        assertEquals("ADS_REPORT_PROFILE_FORBIDDEN", assertThrows(AdsQueryError.class,
                () -> ReportRuntime.control("WAITING", "pause", "other", "u")).getCode());
    }

    @Test
    void reportServicePhaseMachineDegradesColumnsAndFinalisesStatus() {
        ReportService.Checkpoint checkpoint = new ReportService.Checkpoint();
        checkpoint.type = "campaign";
        checkpoint.from = "2026-11-01";
        checkpoint.to = "2026-11-03";
        checkpoint.request = SpReportCatalog.request("campaign", checkpoint.from, checkpoint.to);
        ReportService.StepOutcome created = ReportService.runCreate(checkpoint,
                new ReportingV3Transport.Receipt("r1", "PENDING", null), NOW);
        assertEquals(ReportService.Phase.POLL, created.phase);
        checkpoint.phase = created.phase;
        assertEquals("r1", checkpoint.reportId);
        ReportService.StepOutcome processing = ReportService.runPoll(checkpoint,
                new ReportingV3Transport.Receipt("r1", "PROCESSING", null), NOW);
        assertEquals(ReportService.Phase.POLL, processing.phase);
        checkpoint.phase = processing.phase;
        assertEquals(1, checkpoint.calls.get(ReportService.Operation.poll).intValue(),
                "a single PROCESSING poll is counted once");
        ReportService.StepOutcome completed = ReportService.runPoll(checkpoint,
                new ReportingV3Transport.Receipt("r1", "COMPLETED", "https://x.invalid/a"), NOW);
        assertEquals(ReportService.Phase.DOWNLOAD, completed.phase);
        checkpoint.phase = completed.phase;
        assertEquals(2, checkpoint.calls.get(ReportService.Operation.poll).intValue(),
                "both poll attempts are counted before the envelope completes");
        assertNotNull(checkpoint.urlCiphertext);
        assertTrue(ReportService.degradeToCore(checkpoint, "ADS_REPORT_COLUMNS_REJECTED") == false,
                "degrade only applies to CREATE/full");
        checkpoint.phase = ReportService.Phase.CREATE;
        assertTrue(ReportService.degradeToCore(checkpoint, "ADS_REPORT_COLUMNS_REJECTED"));
        assertEquals("core", checkpoint.columns);
        assertTrue(((List<?>) ((Map<?, ?>) checkpoint.request.get("configuration")).get("columns")).size() <
                SpReportCatalog.definition("campaign").columns.size());

        assertEquals("BLOCKED", ReportService.finalizeStatus(null, "ADS_REPORT_UPSTREAM_FAILED", false));
        assertEquals("SUCCEEDED", ReportService.finalizeStatus(
                new ReportService.StepOutcome(ReportService.Phase.DONE, NOW, 0), null, true));
        assertEquals("WAITING", ReportService.finalizeStatus(
                new ReportService.StepOutcome(ReportService.Phase.POLL, NOW, 0), null, true));
        assertFalse(ReportService.pipelineEnabled(Collections.singletonMap("AMAZON_ADS_REPORTING_MODE", "OFF")));
        assertTrue(ReportService.pipelineEnabled(Collections.emptyMap()));
    }

    @Test
    void queueNamesAndCapacityAreDeterministic() {
        String q1 = ReportWorker.queueName("org", "ws");
        assertEquals(q1, ReportWorker.queueName("org", "ws"));
        assertNotEquals(q1, ReportWorker.queueName("org", "ws2"));
        assertTrue(ReportWorker.jobId(new ReportRuntime.Dispatch("task", 1)).startsWith("report-"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("wait", 97);
        counts.put("active", 2);
        assertEquals(1, ReportWorker.available(counts));
        counts.put("delayed", 1);
        assertEquals(0, ReportWorker.available(counts));
    }

    @Test
    void schedulerGatesStopBeforeAnyPlanScan() {
        ReportScheduler.TickResult off = ReportScheduler.tick(false, true, true, null, null, NOW);
        assertEquals("REPORTING_OFF", off.reason);
        assertEquals("skipped", off.outcome);
        assertEquals("CLOUD_EXECUTION_REQUIRED",
                ReportScheduler.tick(true, false, true, null, null, NOW).reason);
        assertEquals("QUEUE_NOT_CONFIGURED",
                ReportScheduler.tick(true, true, false, null, null, NOW).reason);
    }

    @Test
    void publicationRejectsPartialBatchesAndRowCountDrift() {
        ReportPublication.Manifest manifest = new ReportPublication.Manifest();
        manifest.reportType = "campaign";
        manifest.adProduct = "SPONSORED_PRODUCTS";
        manifest.apiVersion = "reporting-v3";
        manifest.currencyCode = "USD";
        manifest.attributionWindow = "7d";
        manifest.timezone = "UTC";
        manifest.from = "2026-11-01";
        manifest.to = "2026-11-07";
        manifest.dimensions.add("campaignId");
        manifest.metrics.addAll(Arrays.asList("impressions", "clicks", "orders", "units", "spend", "sales"));

        ReportPublication.Fact fact = new ReportPublication.Fact();
        fact.reportDate = "2026-11-02";
        fact.dimensions.put("campaignId", "123");
        fact.impressions = "100";
        fact.clicks = "10";
        fact.orders = "2";
        fact.units = "3";
        fact.spend = "1.500000";
        fact.sales = "20.000000";

        List<String> keys = ReportPublication.stage(manifest, Collections.singletonList(fact));
        assertEquals(1, keys.size());
        assertEquals(64, keys.get(0).length());
        assertEquals(keys.get(0), ReportPublication.factKey(manifest, fact), "fact key is stable");
        assertEquals(BigInteger.TEN, ReportPublication.numeric(fact).get("clicks"));

        ReportPublication.Fact bad = new ReportPublication.Fact();
        bad.reportDate = "2026-11-02";
        bad.dimensions.put("campaignId", "123");
        bad.spend = "not-a-number";
        assertThrows(AdsQueryError.class, () -> ReportPublication.stage(manifest, Arrays.asList(fact, bad)));

        ReportPublication.InMemoryStore store = new ReportPublication.InMemoryStore();
        store.stage("task-1", "version-1", Collections.singletonList(fact));
        assertThrows(AdsQueryError.class, () -> ReportPublication.publish(manifest, "USD", "UTC",
                "2026-11-01", "2026-11-07", store, "task-1", "version-1", 2));
        assertDoesNotThrow(() -> ReportPublication.publish(manifest, "USD", "UTC",
                "2026-11-01", "2026-11-07", store, "task-1", "version-1", 1));
    }
}

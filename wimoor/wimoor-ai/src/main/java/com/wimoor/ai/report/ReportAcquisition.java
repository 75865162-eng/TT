package com.wimoor.ai.report;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code amazon-ads/report-acquisition.ts}.
 *
 * <p>One bounded remote operation per dispatch for the isolated campaign-report
 * pipeline. The phase machine is CREATE -> POLL -> DOWNLOAD -> STAGE -> PUBLISH ->
 * COMPLETE. Counters are committed BEFORE the transport call and survive restarts;
 * an interrupted create is never reissued ({@code ADS_REPORT_CREATE_RESULT_UNKNOWN}).
 * DB/Prisma plumbing is represented by {@link ReportStore}; the real reserve,
 * budget, phase-transition and retry decisions below are preserved verbatim.
 */
public final class ReportAcquisition {

    private ReportAcquisition() {}

    public enum Phase { CREATE, POLL, DOWNLOAD, STAGE, PUBLISH, COMPLETE }

    public enum Operation { create, poll, download }

    /** Exact per-operation call budgets: create=1, poll=10, download=3. */
    public static final Map<Operation, Integer> BUDGETS;
    static {
        Map<Operation, Integer> budgets = new EnumMap<>(Operation.class);
        budgets.put(Operation.create, 1);
        budgets.put(Operation.poll, 10);
        budgets.put(Operation.download, 3);
        BUDGETS = Collections.unmodifiableMap(budgets);
    }

    /** Reservation committed before an operation is invoked. */
    public static final class Reservation {
        public Operation operation;
        public int fence;
        public String owner;

        public Reservation() {}

        public Reservation(Operation operation, int fence, String owner) {
            this.operation = operation;
            this.fence = fence;
            this.owner = owner;
        }
    }

    /** Persisted acquisition checkpoint; never repaired from untrusted storage. */
    public static final class AcquisitionState {
        public int version = 1;
        public Phase phase = Phase.CREATE;
        public CampaignReportContract.Spec spec;
        public String deadline;
        public String scopeDigest;
        public final Map<Operation, Integer> calls = new EnumMap<>(Operation.class);
        public final List<String> evidenceFileIds = new java.util.ArrayList<>();
        public String nextPollAt;
        public Reservation reserved;
        public String urlCiphertext;
        public Integer expectedRows;

        public AcquisitionState() {
            for (Operation op : Operation.values()) calls.put(op, 0);
        }

        public AcquisitionState copy() {
            AcquisitionState copy = new AcquisitionState();
            copy.version = version;
            copy.phase = phase;
            copy.spec = spec;
            copy.deadline = deadline;
            copy.scopeDigest = scopeDigest;
            copy.calls.putAll(calls);
            copy.evidenceFileIds.addAll(evidenceFileIds);
            copy.nextPollAt = nextPollAt;
            copy.reserved = reserved == null ? null
                    : new Reservation(reserved.operation, reserved.fence, reserved.owner);
            copy.urlCiphertext = urlCiphertext;
            copy.expectedRows = expectedRows;
            return copy;
        }

        public int totalCalls() {
            return calls.get(Operation.create) + calls.get(Operation.poll) + calls.get(Operation.download);
        }
    }

    /** Transport response; status/redirect/headers drive bounded-body and retry logic. */
    public static final class HttpResponse {
        public final int status;
        public final boolean redirected;
        public final Map<String, String> headers;
        public final byte[] body;

        public HttpResponse(int status, boolean redirected, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.redirected = redirected;
            this.headers = headers == null ? Collections.emptyMap() : headers;
            this.body = body == null ? new byte[0] : body;
        }
    }

    /** No default HTTP implementation: this entry point cannot send a live request. */
    public interface IsolatedReportTransport {
        String evidence();

        HttpResponse create(Map<String, Object> request);

        HttpResponse poll(String reportId);

        HttpResponse download(String url);
    }

    /** Persistence boundary replacing Prisma calls; a deterministic in-memory implementation follows. */
    public interface ReportStore {
        AcquisitionState load(String taskId);

        void save(String taskId, AcquisitionState state);
    }

    public static final class InMemoryReportStore implements ReportStore {
        private final Map<String, AcquisitionState> states = new ConcurrentHashMap<>();

        @Override
        public AcquisitionState load(String taskId) {
            AcquisitionState state = states.get(taskId);
            return state == null ? null : state.copy();
        }

        @Override
        public void save(String taskId, AcquisitionState state) {
            states.put(taskId, state.copy());
        }
    }

    /** Environment guard; the offline fixture flag is mandatory. */
    public static void assertIsolatedReportEnvironment(IsolatedReportTransport transport, Map<String, String> env) {
        String database = env.getOrDefault("DATABASE_URL", "http://fixture.invalid");
        String root = env.get("UPLOAD_DIR");
        boolean bad = !"1".equals(env.get("AMAZON_SYNC_OFFLINE_FIXTURE"))
                || !isLocalTestDatabase(database)
                || !"local".equals(env.get("STORAGE_DRIVER"))
                || root == null || !root.startsWith("/")
                || (transport != null && !"ISOLATED_FIXTURE_ONLY".equals(transport.evidence()));
        if (bad) throw new AdsQueryError("ADS_REPORT_ACQUISITION_OFFLINE_ONLY", 403);
    }

    private static boolean isLocalTestDatabase(String url) {
        try {
            URI uri = URI.create(url);
            return "127.0.0.1".equals(uri.getHost()) && "/amazon_sync_test".equals(uri.getPath());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Configuration re-check for create/poll envelopes; key count and exact values must match. */
    public static boolean sameConfiguration(Object value, CampaignReportContract.Spec spec) {
        if (!(value instanceof Map)) return false;
        Map<?, ?> actual = (Map<?, ?>) value;
        @SuppressWarnings("unchecked")
        Map<String, Object> expected = (Map<String, Object>) CampaignReportContract.request(spec).get("configuration");
        if (actual.size() != expected.size()) return false;
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object stored = actual.get(entry.getKey());
            if (!json(stored).equals(json(entry.getValue()))) return false;
        }
        return true;
    }

    /** Only .invalid HTTPS URLs without credentials/port/fragment are accepted. */
    public static String fixtureUrl(String value) {
        if (value == null) throw new AdsQueryError("ADS_REPORT_DOWNLOAD_URL_REJECTED");
        try {
            URI url = URI.create(value);
            if (!"https".equalsIgnoreCase(url.getScheme())
                    || url.getHost() == null || !url.getHost().endsWith(".invalid")
                    || url.getUserInfo() != null || url.getPort() != -1 || url.getFragment() != null) {
                throw new AdsQueryError("ADS_REPORT_DOWNLOAD_URL_REJECTED");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new AdsQueryError("ADS_REPORT_DOWNLOAD_URL_REJECTED");
        }
    }

    /** Reserves an operation, consuming one budget call. Returns null for local phases. */
    public static Operation reserve(AcquisitionState state, int fenceVersion, String leaseOwner, long nowMillis) {
        ReportDeadline.assertDeadline(state.deadline, nowMillis);
        if (state.reserved != null) {
            if (state.reserved.operation == Operation.create) {
                throw new AdsQueryError("ADS_REPORT_CREATE_RESULT_UNKNOWN", 409);
            }
            if (state.reserved.fence == fenceVersion && state.reserved.owner.equals(leaseOwner)) {
                throw new AdsQueryError("ADS_REPORT_OPERATION_IN_FLIGHT", 409);
            }
            state.reserved = null; // a replacement fence may retry only read-only poll/download
        }
        if (state.nextPollAt != null && ReportDeadline.parse(state.nextPollAt) > nowMillis) {
            throw new AdsQueryError("ADS_REPORT_POLL_NOT_DUE", 409);
        }
        Operation operation;
        switch (state.phase) {
            case CREATE: operation = Operation.create; break;
            case POLL: operation = Operation.poll; break;
            case DOWNLOAD: operation = Operation.download; break;
            default: return null;
        }
        int used = state.calls.get(operation);
        if (used >= BUDGETS.get(operation)) throw new AdsQueryError("ADS_REPORT_REQUEST_BUDGET_EXCEEDED");
        state.calls.put(operation, used + 1);
        state.reserved = new Reservation(operation, fenceVersion, leaseOwner);
        return operation;
    }

    /** Maps a create/poll envelope status to the next acquisition phase. */
    public static Phase phaseAfterEnvelope(String status) {
        if ("COMPLETED".equals(status)) return Phase.DOWNLOAD;
        if ("PENDING".equals(status) || "PROCESSING".equals(status)) return Phase.POLL;
        if ("FAILED".equals(status)) throw new AdsQueryError("ADS_REPORT_UPSTREAM_FAILED");
        throw new AdsQueryError("ADS_REPORT_ENVELOPE_CONTRACT_MISMATCH");
    }

    /** Clear the reservation on a failed read; an unknown create keeps its reservation. */
    public static void clearReservationAfterFailure(AcquisitionState state, Operation operation) {
        if (operation != Operation.create) state.reserved = null;
    }

    /** Returns true when a read retry is still possible within the deadline. */
    public static boolean isReadRetry(Phase phase, String errorCode, boolean throttledOrTimeout,
                                      int callsInPhase, boolean retryBeyondDeadline) {
        boolean retryablePhase = phase == Phase.POLL || phase == Phase.DOWNLOAD;
        boolean retryableError = throttledOrTimeout || "ADS_REPORT_OPERATION_TIMEOUT".equals(errorCode)
                || "ADS_REPORT_ACQUISITION_FAILED".equals(errorCode);
        int limit = phase == Phase.POLL ? BUDGETS.get(Operation.poll) : BUDGETS.get(Operation.download);
        return retryablePhase && retryableError && callsInPhase < limit && !retryBeyondDeadline;
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}

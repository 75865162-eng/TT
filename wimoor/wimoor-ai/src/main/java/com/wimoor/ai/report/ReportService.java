package com.wimoor.ai.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code amazon-ads/report-service.ts}.
 *
 * <p>Five SP report tasks per plan are created idempotently and advanced one bounded
 * step per dispatch: CREATE -> POLL -> DOWNLOAD -> PUBLISH -> DONE. Worst-case budgets
 * are 3 creates, 30 polls and 3 downloads. A profile runs at most one report at a
 * time (official 425 avoidance); a busy profile is deferred with a 60s cooldown.
 * When optional columns are rejected, the request is degraded to core columns once.
 * DB/queue/storage calls are represented by {@link TaskStore} and the injected
 * transport; the phase machine, retry and terminal decisions are preserved.
 */
public final class ReportService {

    private ReportService() {}

    public static final String TRIGGER = "sp-report";
    public static final String API_VERSION = "reporting-v3";
    public static final String AD_PRODUCT = "SPONSORED_PRODUCTS";
    public static final String ATTRIBUTION_WINDOW = "7d";
    public static final long LEASE_MS = 180_000L;
    public static final int MAX_POLLS = 30;
    public static final int MAX_CREATE_ATTEMPTS = 3;
    public static final List<String> REPORT_TYPE_IDS = Collections.unmodifiableList(Arrays.asList(
            "campaign", "advertised-product", "search-term", "placement", "targeting"));

    public enum Phase { CREATE, POLL, DOWNLOAD, PUBLISH, DONE }
    public enum Operation { create, poll, download }

    /** Persisted checkpoint, v1. */
    public static final class Checkpoint {
        public int v = 1;
        public String type;
        public String from;
        public String to;
        public String columns = "full";
        public Phase phase = Phase.CREATE;
        public Map<String, Object> request;
        public final Map<Operation, Integer> calls = new EnumMap<>(Operation.class);
        public String reportId;
        public String urlCiphertext;
        public String nextAttemptAt;
        public Integer rowCount;
        public String versionId;
        public Integer mergedRows;
        public List<String> ignoredColumns;
        public List<String> warnings;

        public Checkpoint() {
            for (Operation op : Operation.values()) calls.put(op, 0);
        }

        public Checkpoint copy() {
            Checkpoint copy = new Checkpoint();
            copy.v = v;
            copy.type = type;
            copy.from = from;
            copy.to = to;
            copy.columns = columns;
            copy.phase = phase;
            copy.request = request;
            copy.calls.putAll(calls);
            copy.reportId = reportId;
            copy.urlCiphertext = urlCiphertext;
            copy.nextAttemptAt = nextAttemptAt;
            copy.rowCount = rowCount;
            copy.versionId = versionId;
            copy.mergedRows = mergedRows;
            copy.ignoredColumns = ignoredColumns == null ? null : new ArrayList<>(ignoredColumns);
            copy.warnings = warnings == null ? null : new ArrayList<>(warnings);
            return copy;
        }
    }

    public static void readCheckpoint(Checkpoint state) {
        if (state == null || state.v != 1 || !SpReportCatalog.isSpReportType(state.type)
                || !SpReportCatalog.isDateOnly(state.from) || !SpReportCatalog.isDateOnly(state.to)
                || state.from.compareTo(state.to) > 0 || state.phase == null || state.request == null
                || state.calls.get(Operation.create) < 0 || state.calls.get(Operation.create) > MAX_CREATE_ATTEMPTS
                || state.calls.get(Operation.poll) < 0 || state.calls.get(Operation.poll) > MAX_POLLS
                || state.calls.get(Operation.download) < 0 || state.calls.get(Operation.download) > 3) {
            throw new AdsQueryError("ADS_REPORT_CHECKPOINT_INVALID");
        }
        SpReportCatalog.request(state.type, state.from, state.to, state.columns);
    }

    public static boolean pipelineEnabled(Map<String, String> env) {
        String mode = env.getOrDefault("AMAZON_ADS_REPORTING_MODE", "live");
        return !"off".equalsIgnoreCase(mode);
    }

    /** In-memory task row replacing Prisma's AmazonAdsSyncTask. */
    public static final class Task {
        public String id;
        public String organizationId;
        public String workspaceId;
        public String profileId;
        public String planId;
        public long planRevision;
        public String requestedByUserId;
        public String trigger = TRIGGER;
        public String stage = "REPORT_CREATE";
        public String status = "WAITING";
        public int dispatchVersion = 1;
        public int fenceVersion;
        public String leaseOwner;
        public Long leaseExpiresAt;
        public String idempotencyKey;
        public String from;
        public String to;
        public String errorCode;
        public Long nextAttemptAt;
        public Checkpoint checkpoint;
    }

    /** Persistence boundary; deterministic in-memory implementation below. */
    public interface TaskStore {
        Task findById(String taskId);
        Task findByIdempotencyKey(String key);
        Task create(Task task);
        void save(Task task);
        Task findBusyForProfile(String profileId, long nowMillis);
        List<Task> dueForProfile(String profileId, long nowMillis);
    }

    public static final class InMemoryTaskStore implements TaskStore {
        private final Map<String, Task> byId = new ConcurrentHashMap<>();
        private final Map<String, String> byKey = new ConcurrentHashMap<>();

        @Override
        public Task findById(String taskId) { return copy(byId.get(taskId)); }

        @Override
        public Task findByIdempotencyKey(String key) {
            String id = byKey.get(key);
            return id == null ? null : copy(byId.get(id));
        }

        @Override
        public Task create(Task task) {
            byId.put(task.id, copy(task));
            byKey.put(task.idempotencyKey, task.id);
            return copy(task);
        }

        @Override
        public void save(Task task) { byId.put(task.id, copy(task)); }

        @Override
        public Task findBusyForProfile(String profileId, long nowMillis) {
            for (Task task : byId.values()) {
                if (profileId.equals(task.profileId) && TRIGGER.equals(task.trigger)
                        && "RUNNING".equals(task.status) && task.leaseExpiresAt != null
                        && task.leaseExpiresAt > nowMillis) return copy(task);
            }
            return null;
        }

        @Override
        public List<Task> dueForProfile(String profileId, long nowMillis) {
            List<Task> result = new ArrayList<>();
            for (Task task : byId.values()) {
                if (profileId.equals(task.profileId) && TRIGGER.equals(task.trigger)
                        && ("WAITING".equals(task.status) || "RETRY_WAIT".equals(task.status))
                        && (task.nextAttemptAt == null || task.nextAttemptAt <= nowMillis)) result.add(copy(task));
            }
            result.sort((a, b) -> Long.compare(a.nextAttemptAt == null ? 0 : a.nextAttemptAt,
                    b.nextAttemptAt == null ? 0 : b.nextAttemptAt));
            return result;
        }

        private static Task copy(Task task) {
            if (task == null) return null;
            Task copy = new Task();
            copy.id = task.id;
            copy.organizationId = task.organizationId;
            copy.workspaceId = task.workspaceId;
            copy.profileId = task.profileId;
            copy.planId = task.planId;
            copy.planRevision = task.planRevision;
            copy.requestedByUserId = task.requestedByUserId;
            copy.trigger = task.trigger;
            copy.stage = task.stage;
            copy.status = task.status;
            copy.dispatchVersion = task.dispatchVersion;
            copy.fenceVersion = task.fenceVersion;
            copy.leaseOwner = task.leaseOwner;
            copy.leaseExpiresAt = task.leaseExpiresAt;
            copy.idempotencyKey = task.idempotencyKey;
            copy.from = task.from;
            copy.to = task.to;
            copy.errorCode = task.errorCode;
            copy.nextAttemptAt = task.nextAttemptAt;
            copy.checkpoint = task.checkpoint == null ? null : task.checkpoint.copy();
            return copy;
        }
    }

    /** Idempotency key: sp-report:{plan}:{revision}:{type}:{from}:{to}. */
    public static String idempotencyKey(String planId, long revision, String type, String from, String to) {
        return "sp-report:" + planId + ":" + revision + ":" + type + ":" + from + ":" + to;
    }

    /** Creates or reuses a task; never activates a plan or changes authorization. */
    public static Map<String, Object> ensureTask(TaskStore store, String planId, long revision,
                                                 String actor, String type, String from, String to, String taskId) {
        if (!SpReportCatalog.isDateOnly(from) || !SpReportCatalog.isDateOnly(to) || from.compareTo(to) > 0) {
            throw new AdsQueryError("ADS_REPORT_DATE_RANGE_INVALID");
        }
        String key = idempotencyKey(planId, revision, type, from, to);
        Task found = store.findByIdempotencyKey(key);
        if (found != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", found.id);
            result.put("created", false);
            result.put("status", found.status);
            return result;
        }
        Task task = new Task();
        task.id = taskId;
        task.planId = planId;
        task.planRevision = revision;
        task.profileId = planId;
        task.requestedByUserId = actor;
        task.idempotencyKey = key;
        task.from = from;
        task.to = to;
        task.stage = "REPORT_CREATE";
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.type = type;
        checkpoint.from = from;
        checkpoint.to = to;
        checkpoint.request = SpReportCatalog.request(type, from, to, "full");
        task.checkpoint = checkpoint;
        store.create(task);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.id);
        result.put("created", true);
        result.put("status", task.status);
        return result;
    }

    /** Outcome of a single bounded step. */
    public static final class StepOutcome {
        public final Phase phase;
        public final long nextAttemptAt;
        public final Integer rowCount;

        public StepOutcome(Phase phase, long nextAttemptAt, Integer rowCount) {
            this.phase = phase;
            this.nextAttemptAt = nextAttemptAt;
            this.rowCount = rowCount;
        }

        public static StepOutcome next(Phase phase, long nowMillis) {
            return new StepOutcome(phase, nowMillis + 60_000L, null);
        }
    }

    public static StepOutcome runCreate(Checkpoint state, ReportingV3Transport.Receipt receipt, long nowMillis) {
        if (state.calls.get(Operation.create) >= MAX_CREATE_ATTEMPTS) {
            throw new AdsQueryError("ADS_REPORT_CREATE_BUDGET_EXCEEDED");
        }
        state.calls.put(Operation.create, state.calls.get(Operation.create) + 1);
        state.reportId = receipt.reportId;
        return StepOutcome.next(Phase.POLL, nowMillis);
    }

    public static StepOutcome runPoll(Checkpoint state, ReportingV3Transport.Receipt receipt, long nowMillis) {
        if (state.reportId == null) throw new AdsQueryError("ADS_REPORT_CHECKPOINT_INVALID");
        if (state.calls.get(Operation.poll) >= MAX_POLLS) throw new AdsQueryError("ADS_REPORT_POLL_BUDGET_EXCEEDED");
        state.calls.put(Operation.poll, state.calls.get(Operation.poll) + 1);
        if (receipt.reportId != null && !state.reportId.equals(receipt.reportId)) {
            throw new AdsQueryError("ADS_REPORT_ID_MISMATCH");
        }
        if ("FAILED".equals(receipt.status)) throw new AdsQueryError("ADS_REPORT_UPSTREAM_FAILED");
        if ("COMPLETED".equals(receipt.status)) {
            if (receipt.url == null || receipt.url.isEmpty()) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
            state.urlCiphertext = receipt.url;
            return new StepOutcome(Phase.DOWNLOAD, nowMillis, null);
        }
        return StepOutcome.next(Phase.POLL, nowMillis);
    }

    public static StepOutcome runDownload(Checkpoint state, SpReportParser.Result parsed, long nowMillis) {
        if (state.urlCiphertext == null) throw new AdsQueryError("ADS_REPORT_CHECKPOINT_INVALID");
        if (state.calls.get(Operation.download) >= 3) throw new AdsQueryError("ADS_REPORT_DOWNLOAD_BUDGET_EXCEEDED");
        state.calls.put(Operation.download, state.calls.get(Operation.download) + 1);
        state.rowCount = parsed.facts.size();
        state.mergedRows = parsed.mergedRows;
        state.ignoredColumns = parsed.ignoredColumns;
        state.warnings = parsed.facts.isEmpty()
                ? new ArrayList<>(Collections.singletonList("ADS_REPORT_EMPTY")) : new ArrayList<>();
        return new StepOutcome(Phase.PUBLISH, nowMillis, parsed.facts.size());
    }

    /** Columns-rejected degrade: full -> core, retried once. */
    public static boolean degradeToCore(Checkpoint state, String code) {
        if (!"ADS_REPORT_COLUMNS_REJECTED".equals(code)
                || !"full".equals(state.columns) || state.phase != Phase.CREATE) return false;
        state.columns = "core";
        state.request = SpReportCatalog.request(state.type, state.from, state.to, "core");
        return true;
    }

    /** Terminal/retry status selection used on finalising a dispatch. */
    public static String finalizeStatus(StepOutcome next, String errorCode, boolean retryable) {
        boolean terminal = !retryable || (next != null && next.phase == Phase.DONE);
        if (terminal && errorCode != null) return "BLOCKED";
        if (next != null && next.phase == Phase.DONE) return "SUCCEEDED";
        return "WAITING";
    }

    public static Map<String, String> window(String type, long nowMillis) {
        return SpReportCatalog.window(type, nowMillis, 7);
    }
}

package com.wimoor.ai.report;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ported from {@code amazon-ads/report-runtime.ts}.
 *
 * <p>Explicit queue integration for initialized fixture tasks. The DB is authoritative:
 * a failed enqueue leaves a due task for the next tick. One bounded step per message;
 * a waiting remote report holds no worker lease. Retry decisions are deterministic
 * so a locked/poison task cannot monopolize a scan.
 */
public final class ReportRuntime {

    private ReportRuntime() {}

    public static final String TRIGGER = "isolated-report";
    public static final List<String> TERMINAL = Collections.unmodifiableList(Arrays.asList(
            "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED"));

    public static final class Dispatch {
        public String taskId;
        public int dispatchVersion;

        public Dispatch() {}

        public Dispatch(String taskId, int dispatchVersion) {
            this.taskId = taskId;
            this.dispatchVersion = dispatchVersion;
        }
    }

    public static boolean validDispatch(Object value) {
        if (!(value instanceof Dispatch)) return false;
        Dispatch job = (Dispatch) value;
        return job.taskId != null && !job.taskId.isEmpty() && job.taskId.length() <= 200
                && job.dispatchVersion > 0;
    }

    /** Expired RUNNING leases are moved to RETRY_WAIT with a fresh fence. */
    public static boolean leaseExpired(String status, Long leaseExpiresAt, long nowMillis) {
        return "RUNNING".equals(status) && (leaseExpiresAt == null || leaseExpiresAt <= nowMillis);
    }

    /** Read-only retry is allowed only for POLL/DOWNLOAD and specific retryable errors. */
    public static boolean isReadRetry(String phase, String errorCode,
                                      ReportAcquisition.AcquisitionState acquisition) {
        boolean retryableError = errorCode != null && Arrays.asList(
                "ADS_REPORT_OPERATION_TIMEOUT", "ADS_REPORT_ACQUISITION_FAILED",
                "ADS_REPORT_THROTTLED", "ADS_REPORT_UPSTREAM_UNAVAILABLE").contains(errorCode);
        if (!retryableError || acquisition == null
                || (!"POLL".equals(phase) && !"DOWNLOAD".equals(phase))) return false;
        ReportAcquisition.Operation op = "POLL".equals(phase)
                ? ReportAcquisition.Operation.poll : ReportAcquisition.Operation.download;
        return acquisition.calls.get(op) < ReportAcquisition.BUDGETS.get(op);
    }

    /** Final status decision after an isolated dispatch attempt. */
    public static final class Decision {
        public final String status;
        public final long retryAt;
        public final boolean blocked;
        public final String effectiveErrorCode;

        Decision(String status, long retryAt, boolean blocked, String effectiveErrorCode) {
            this.status = status;
            this.retryAt = retryAt;
            this.blocked = blocked;
            this.effectiveErrorCode = effectiveErrorCode;
        }
    }

    public static Decision decide(String taskStatus, String phase, String errorCode,
                                  ReportAcquisition.AcquisitionState acquisition, long nowMillis) {
        boolean readRetry = isReadRetry(phase, errorCode, acquisition);
        boolean dueRetry = readRetry && acquisition.nextPollAt != null;
        long base = readRetry
                ? ReportRetry.retryAt(acquisition.calls.get(
                "POLL".equals(phase) ? ReportAcquisition.Operation.poll : ReportAcquisition.Operation.download), nowMillis)
                : nowMillis;
        long due = dueRetry ? ReportDeadline.parse(acquisition.nextPollAt) : 0L;
        long retryAt = Math.max(base, due);
        boolean beyondDeadline = acquisition != null && readRetry && retryAt >= ReportDeadline.parse(acquisition.deadline);
        boolean blocked = acquisition == null
                || (errorCode != null && !readRetry)
                || beyondDeadline
                || (acquisition != null && !ReportDeadline.isFuture(acquisition.deadline, nowMillis));
        String effectiveCode = errorCode;
        if (beyondDeadline) effectiveCode = "ADS_REPORT_RETRY_AFTER_DEADLINE";
        String status;
        if ("SUCCEEDED".equals(taskStatus)) status = "SUCCEEDED";
        else if (blocked) status = "BLOCKED";
        else if (readRetry) status = "RETRY_WAIT";
        else status = "WAITING";
        return new Decision(status, retryAt, blocked, effectiveCode);
    }

    /** Control actions: terminal tasks are immutable; resume requires PAUSED. */
    public static String control(String status, String action, String actor, String creator) {
        if (!Arrays.asList("pause", "resume", "cancel").contains(action)) {
            throw new AdsQueryError("ADS_REPORT_CONTROL_INVALID");
        }
        if (TERMINAL.contains(status)) throw new AdsQueryError("ADS_REPORT_TASK_TERMINAL", 409);
        if ("resume".equals(action) && !"PAUSED".equals(status)) {
            throw new AdsQueryError("ADS_REPORT_TASK_NOT_PAUSED", 409);
        }
        if (!actor.equals(creator)) throw new AdsQueryError("ADS_REPORT_PROFILE_FORBIDDEN", 403);
        return "pause".equals(action) ? "PAUSED" : "cancel".equals(action) ? "CANCELLED" : "WAITING";
    }
}

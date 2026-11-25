package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code amazon-ads/report-scheduler.ts}.
 *
 * <p>Scheduling gates, in order: reporting pipeline on, cloud execution enabled,
 * database URL and Redis queue configured. Plans are limited to 200 active rows;
 * each plan gets five tasks per day, and each profile receives at most one queued
 * job because the official contract allows only one concurrent report per advertiser.
 * A failed enqueue leaves the task WAITING for the next tick.
 */
public final class ReportScheduler {

    private ReportScheduler() {}

    public static final class TickResult {
        public String outcome;
        public String reason;
        public int ensuredPlans;
        public int enqueued;
        public int deferred;
    }

    /** Functional boundary replacing the Redis queue. */
    public interface Enqueue {
        void add(String name, Map<String, Object> payload, String jobId) throws Exception;
    }

    /** Functional boundary replacing the task repository for one tick. */
    public interface SchedulerStore {
        List<Map<String, Object>> activePlans();
        boolean scopeEnabled(Map<String, Object> scope);
        int todayTaskCount(Map<String, Object> scope, String planId, long dayStartMillis);
        void ensureTasks(Map<String, Object> scope, String planId, long nowMillis);
        Map<String, Object> nextForProfile(Map<String, Object> scope, String profileId, long nowMillis);
        void markQueued(Map<String, Object> scope, String taskId, int dispatchVersion);
    }

    public static TickResult tick(boolean pipelineEnabled, boolean cloudEnabled,
                                  boolean queueConfigured, SchedulerStore store,
                                  Enqueue enqueue, long nowMillis) {
        TickResult result = new TickResult();
        if (!pipelineEnabled) {
            result.outcome = "skipped";
            result.reason = "REPORTING_OFF";
            return result;
        }
        if (!cloudEnabled) {
            result.outcome = "skipped";
            result.reason = "CLOUD_EXECUTION_REQUIRED";
            return result;
        }
        if (!queueConfigured) {
            result.outcome = "skipped";
            result.reason = "QUEUE_NOT_CONFIGURED";
            return result;
        }
        result.outcome = "scanned";
        long dayStart = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis),
                java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Map<String, Object>> plans = store.activePlans();
        for (Map<String, Object> plan : plans) {
            Map<String, Object> scope = scopeOf(plan);
            if (!store.scopeEnabled(scope)) continue;
            try {
                String planId = String.valueOf(plan.get("id"));
                if (store.todayTaskCount(scope, planId, dayStart) < 5) {
                    store.ensureTasks(scope, planId, nowMillis);
                    result.ensuredPlans++;
                }
                Map<String, Object> next = store.nextForProfile(scope,
                        String.valueOf(plan.get("profileId")), nowMillis);
                if (next == null) {
                    result.deferred++;
                    continue;
                }
                String taskId = String.valueOf(next.get("id"));
                int dispatchVersion = ((Number) next.get("dispatchVersion")).intValue();
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("runId", taskId);
                payload.put("adsReportTaskId", taskId);
                payload.put("adsReportDispatchVersion", dispatchVersion);
                enqueue.add("process-amazon-ads-report-task", payload,
                        "ads-report-" + taskId + "-" + dispatchVersion);
                store.markQueued(scope, taskId, dispatchVersion);
                result.enqueued++;
            } catch (Exception e) {
                result.deferred++;
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scopeOf(Map<String, Object> plan) {
        Map<String, Object> scope = new java.util.LinkedHashMap<>();
        scope.put("organizationId", plan.get("organizationId"));
        scope.put("workspaceId", plan.get("workspaceId"));
        return scope;
    }
}

package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code sync-service.ts}, {@code sync-processor.ts} and
 * {@code sync-scheduler.ts}.
 *
 * <p>Structure-sync lifecycle: per-profile advisory lock, plan ensure/control, task
 * cancellation, a bounded processor that advances one stage per dispatch, and a
 * scheduler tick with explicit gates. Unknown triggers/stages are never treated as
 * structure merely because they share a plan; domain failure status distinguishes
 * adapter readiness, retryable exhaustion and hard blocks.
 */
public final class AdsSyncService {

    private AdsSyncService() {}

    public static final class Plan {
        public String id;
        public String profileId;
        public String actorUserId;
        public String status = "PAUSED";
        public int revision = 1;
        public int reportLookbackDays = 7;
        public String lastErrorCode;
    }

    public static final class Task {
        public String id;
        public String planId;
        public String profileId;
        public String trigger;
        public String stage;
        public String status = "WAITING";
        public int dispatchVersion = 1;
        public String errorCode;
    }

    public static final class Store {
        private final Map<String, Plan> plans = new ConcurrentHashMap<>();
        private final Map<String, Task> tasks = new ConcurrentHashMap<>();
        private final Set<String> locks = ConcurrentHashMap.newKeySet();

        public Plan findPlan(String id) { return plans.get(id); }

        public Plan savePlan(Plan plan) { plans.put(plan.id, plan); return plan; }

        public Task findTask(String id) { return tasks.get(id); }

        public Task saveTask(Task task) { tasks.put(task.id, task); return task; }

        public boolean tryLock(String profileId) { return locks.add(profileId); }

        public void unlock(String profileId) { locks.remove(profileId); }
    }

    /** Ensures a paused plan exists for a profile; never activates it implicitly. */
    public static Plan ensurePlan(Store store, String profileId, String actor) {
        for (Plan plan : store.plans.values()) {
            if (profileId.equals(plan.profileId)) return plan;
        }
        Plan plan = new Plan();
        plan.id = "ads-plan-" + AdsCanonical.digest(Arrays.asList(profileId, actor));
        plan.profileId = profileId;
        plan.actorUserId = actor;
        return store.savePlan(plan);
    }

    /** Control a plan; only an active plan can be retried or run. */
    public static String controlPlan(Store store, String planId, String action) {
        Plan plan = store.findPlan(planId);
        if (plan == null) throw new AdsQueryError("ADS_SYNC_PLAN_NOT_FOUND", 404);
        if (!Arrays.asList("pause", "resume", "retry").contains(action)) {
            throw new AdsQueryError("ADS_SYNC_PLAN_CONTROL_INVALID");
        }
        if (("resume".equals(action) || "retry".equals(action)) && "BLOCKED".equals(plan.status)) {
            throw new AdsQueryError("ADS_SYNC_PLAN_BLOCKED", 409);
        }
        plan.status = "pause".equals(action) ? "PAUSED" : "ACTIVE";
        if ("retry".equals(action)) plan.lastErrorCode = null;
        store.savePlan(plan);
        return plan.status;
    }

    /** Cancels an in-flight task with a fresh fence (no in-flight work survives). */
    public static String cancelTask(Store store, String taskId) {
        Task task = store.findTask(taskId);
        if (task == null) throw new AdsQueryError("ADS_SYNC_TASK_NOT_FOUND", 404);
        if (Arrays.asList("SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED").contains(task.status)) {
            throw new AdsQueryError("ADS_SYNC_TASK_TERMINAL", 409);
        }
        task.status = "CANCELLED";
        task.dispatchVersion++;
        task.errorCode = "ADS_SYNC_CANCELLED";
        store.saveTask(task);
        return task.status;
    }

    /** One bounded processor step: advance stage or finish. */
    public static String processStep(Store store, String taskId) {
        Task task = store.findTask(taskId);
        if (task == null) throw new AdsQueryError("ADS_SYNC_TASK_NOT_FOUND", 404);
        if (!Arrays.asList("WAITING", "QUEUED", "RETRY_WAIT", "RUNNING").contains(task.status)) {
            return task.status;
        }
        task.status = "RUNNING";
        int index = AdsSyncPolicy.ADS_SYNC_STAGES.indexOf(task.stage);
        if (index < 0 || !AdsRules.isStructureTask(structureRouting(task))) {
            task.status = "BLOCKED";
            task.errorCode = "ADS_STAGE_NOT_IMPLEMENTED";
        } else if (index == AdsSyncPolicy.ADS_SYNC_STAGES.size() - 1) {
            task.status = "SUCCEEDED";
        } else {
            task.stage = AdsSyncPolicy.ADS_SYNC_STAGES.get(index + 1);
            task.status = "WAITING";
            task.dispatchVersion++;
        }
        store.saveTask(task);
        return task.status;
    }

    private static AdsRules.RoutingTask structureRouting(Task task) {
        AdsRules.RoutingTask routing = new AdsRules.RoutingTask();
        routing.trigger = task.trigger;
        routing.stage = task.stage;
        return routing;
    }

    /** Scheduler gates, mirroring tickAmazonAds. */
    public static final class TickResult {
        public String outcome;
        public String reason;
        public int enqueued;
        public int deferred;
    }

    public static TickResult tick(boolean pipelineEnabled, boolean cloudEnabled,
                                  boolean queueConfigured) {
        TickResult result = new TickResult();
        if (!pipelineEnabled) {
            result.outcome = "skipped";
            result.reason = "REPORTING_OFF";
        } else if (!cloudEnabled) {
            result.outcome = "skipped";
            result.reason = "CLOUD_EXECUTION_REQUIRED";
        } else if (!queueConfigured) {
            result.outcome = "skipped";
            result.reason = "QUEUE_NOT_CONFIGURED";
        } else {
            result.outcome = "scanned";
        }
        return result;
    }
}

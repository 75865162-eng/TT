package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code amazon-ads/report-worker.ts}.
 *
 * <p>Explicit opt-in worker: importing/constructing starts no timer, queue or network
 * call. The queue name and job ID are deterministic hashes of the scope and dispatch,
 * so all instances for a profile share one queue. A single in-flight scan is guarded
 * by a Redis lock; expired locks cannot admit further jobs. Only disposable local
 * Redis is accepted (127.0.0.1) and the interval is bounded to 100..60000 ms.
 */
public final class ReportWorker {

    private ReportWorker() {}

    public static final int DEFAULT_INTERVAL_MS = 30_000;
    public static final int QUEUE_CEILING = 100;

    public static final class TickResult {
        public String outcome;
        public int enqueued;
        public int enqueueFailures;
        public int rowFailures;
        public int blocked;
    }

    public static String queueName(String organizationId, String workspaceId) {
        return "ads-report-isolated-" + ReportScope.sha256(
                ReportScope.toJson(orgWorkspace(organizationId, workspaceId))).substring(0, 32);
    }

    public static String jobId(ReportRuntime.Dispatch job) {
        return "report-" + ReportScope.sha256(ReportScope.toJson(
                Arrays.asList(job.taskId, job.dispatchVersion)));
    }

    private static List<String> orgWorkspace(String org, String ws) {
        return Arrays.asList(org, ws);
    }

    /** Validates the disposable Redis URL and interval used by the worker. */
    public static void assertEnvironment(Map<String, String> env, Integer intervalMs) {
        String redis = env.getOrDefault("REDIS_URL", "http://fixture.invalid");
        boolean disposable = "1".equals(env.get("AMAZON_SYNC_TEST_REDIS_DISPOSABLE"));
        boolean validUrl;
        try {
            java.net.URI uri = java.net.URI.create(redis);
            validUrl = "redis".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() > 0 && uri.getUserInfo() == null;
        } catch (RuntimeException e) {
            validUrl = false;
        }
        if (!disposable || !validUrl) throw new AdsQueryError("ADS_REPORT_WORKER_DISPOSABLE_REDIS_REQUIRED", 403);
        int interval = intervalMs == null ? DEFAULT_INTERVAL_MS : intervalMs;
        if (interval < 100 || interval > 60_000) throw new AdsQueryError("ADS_REPORT_WORKER_INTERVAL_INVALID");
    }

    /** Queue-wide capacity check: available = 100 - sum(all job counts). */
    public static int available(Map<String, Integer> jobCounts) {
        int total = 0;
        for (Integer count : jobCounts.values()) total += count == null ? 0 : count;
        return Math.max(0, QUEUE_CEILING - total);
    }
}

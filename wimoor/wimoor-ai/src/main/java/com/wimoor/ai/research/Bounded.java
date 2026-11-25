package com.wimoor.ai.research;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 对同步外部调用施加超时边界（对应 engine.ts 的 bounded / Promise.race）。
 * 超时后取消任务，且只抛出主机自有错误码。
 */
public final class Bounded {

    private static final ThreadFactory DAEMON = r -> {
        Thread t = new Thread(r, "research-bounded");
        t.setDaemon(true);
        return t;
    };

    private Bounded() {}

    public static <T> T call(Callable<T> work, long timeoutMs, String timeoutCode) {
        ExecutorService executor = Executors.newSingleThreadExecutor(DAEMON);
        Future<T> future = executor.submit(work);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ResearchError(timeoutCode, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ResearchError) throw (ResearchError) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new ResearchError("RESEARCH_STEP_FAILED_REVIEW_CONTEXT", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResearchError("RESEARCH_STEP_FAILED_REVIEW_CONTEXT", e);
        } finally {
            executor.shutdownNow();
        }
    }

    public static <T> T call(Callable<T> work, long timeoutMs) {
        return call(work, timeoutMs, "RESEARCH_ACTION_TIMEOUT");
    }
}

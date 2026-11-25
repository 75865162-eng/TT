package com.wimoor.ai.report;

/**
 * Ported from {@code amazon-ads/sync-policy.ts} ({@code AdsSyncError}), also
 * covering the {@code SyncInputError} used by the replica/change modules.
 *
 * <p>Carries the exact code, whether a failure is retryable, and the suggested
 * delay in milliseconds. These fields drive domain-state decisions such as
 * {@code RETRY_WAIT} vs {@code BLOCKED} ({@link AdsSyncPolicy#failureStatus}).
 */
public class AdsSyncError extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final long retryAfterMs;

    public AdsSyncError(String code) {
        this(code, false, 0L);
    }

    public AdsSyncError(String code, boolean retryable, long retryAfterMs) {
        super(code);
        this.code = code;
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    /** True when the code is a stable machine error code, as used by runtime handlers. */
    public boolean isAdsCode() {
        return code != null && code.matches("ADS_[A-Z0-9_]+");
    }
}

package com.wimoor.ai.report;

/**
 * Ported from {@code amazon-sync/policy.ts} ({@code SyncInputError}), used by the
 * replica/change/query modules for user-facing validation failures. Unlike
 * {@link AdsQueryError} it carries a human-readable Chinese message and an HTTP
 * status, matching the TypeScript class exactly.
 */
public class SyncInputError extends RuntimeException {

    private final int status;

    public SyncInputError(String message) {
        this(message, 400);
    }

    public SyncInputError(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}

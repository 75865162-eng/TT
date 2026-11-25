package com.wimoor.ai.report;

/**
 * Ported from {@code amazon-ads/query-contract.ts} ({@code AdsQueryError}).
 *
 * <p>Ads domain error carrying a stable machine code and an HTTP-like status.
 * The TypeScript original stores the code in {@code message}; Java keeps the
 * same value in {@link #getCode()} and also returns it from {@link #getMessage()}
 * so callers can branch on {@code ^ADS_[A-Z0-9_]+$} exactly as before.
 * Localised Chinese validation messages are preserved verbatim where the source
 * used them (see {@link AdsQueryContract}).
 */
public class AdsQueryError extends RuntimeException {

    private final String code;
    private final int status;

    public AdsQueryError(String code) {
        this(code, 400);
    }

    public AdsQueryError(String code, int status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return status;
    }
}

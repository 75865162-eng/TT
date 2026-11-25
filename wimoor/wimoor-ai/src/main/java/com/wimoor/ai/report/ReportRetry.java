package com.wimoor.ai.report;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Ported from {@code amazon-ads/report-retry.ts}.
 *
 * <p>Deterministic read-retry backoff. Rules preserved:
 * <ul>
 *   <li>backoff = min(60s, 1000 * 2^clamp(attempt-1, 0, 6));</li>
 *   <li>consumed call counts survive restarts — the exponent is NEVER reset per dispatch;</li>
 *   <li>Retry-After accepts a delta-seconds value or an RFC1123 GMT date;</li>
 *   <li>an unrepresentable upstream wait blocks forever ({@link Long#MAX_VALUE})
 *       rather than silently shortening the wait;</li>
 *   <li>the result is max(now + backoff, upstream).</li>
 * </ul>
 */
public final class ReportRetry {

    private ReportRetry() {}

    /** Sentinel standing in for JavaScript {@code Infinity}. */
    public static final long INFINITE = Long.MAX_VALUE;

    private static final long MAX_DATE_MS = 8_640_000_000_000_000L;
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    public static long retryAt(int attempt, String retryAfter, long nowMillis) {
        int exponent = Math.max(0, Math.min(attempt - 1, 6));
        long backoff = Math.min(60_000L, 1000L * (1L << exponent));
        long upstream = 0L;
        if (retryAfter != null) {
            String value = retryAfter.trim();
            if (value.matches("\\d+")) {
                long seconds;
                try {
                    seconds = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    seconds = Long.MAX_VALUE;
                }
                // Safe-integer check and an explicit representability guard: an upstream
                // wait that cannot be represented must block, never shorten the wait.
                boolean safe = seconds <= 9_007_199_254_740_991L;
                upstream = (safe && seconds <= (MAX_DATE_MS - nowMillis) / 1000L)
                        ? nowMillis + seconds * 1000L
                        : INFINITE;
            } else if (value.matches("[A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT")) {
                try {
                    long parsed = java.util.Date.from(
                            Instant.from(HTTP_DATE.parse(value))).getTime();
                    upstream = parsed;
                } catch (RuntimeException ignored) {
                    upstream = 0L;
                }
            }
        }
        return Math.max(nowMillis + backoff, upstream);
    }

    public static long retryAt(int attempt, long nowMillis) {
        return retryAt(attempt, null, nowMillis);
    }

    /** Error thrown for throttling/upstream failures, carrying the computed next attempt. */
    public static final class Error extends AdsQueryError {
        private final long retryAt;

        public Error(String code, long retryAt) {
            super(code);
            this.retryAt = retryAt;
        }

        public long getRetryAt() {
            return retryAt;
        }

        public boolean beyondDeadline(String deadline) {
            long end = ReportDeadline.parse(deadline);
            return retryAt == INFINITE || end == Long.MIN_VALUE || retryAt >= end;
        }
    }
}

package com.wimoor.ai.report;

/**
 * Ported from {@code amazon-ads/report-body.ts}.
 *
 * <p>Bounded download-body reader. In Java the HTTP body is delivered as a byte
 * array by the transport, so the streaming/abort mechanics collapse into a pure
 * validator; the error priorities and code set are preserved exactly:
 * <ol>
 *   <li>an aborted signal wins over everything (OPERATION_TIMEOUT);</li>
 *   <li>non-200, non-ok or a redirect is rejected before reading;</li>
 *   <li>a malformed or over-limit Content-Length is rejected before reading;</li>
 *   <li>the accumulated byte count may never exceed the limit;</li>
 *   <li>when Content-Length is present it must equal the exact read size.</li>
 * </ol>
 * A broken cancel hook is intentionally a no-op: it must never mask the original
 * error or hold a worker.
 */
public final class ReportBody {

    private ReportBody() {}

    /** Java has no cancellable stream here; retained for source fidelity. */
    public static void cancelQuietly() {
        // no-op: closing the connection is the transport's responsibility
    }

    public static byte[] readBounded(byte[] body, boolean aborted, int status, boolean redirected,
                                     String contentLength, long limit) {
        if (aborted) throw new AdsQueryError("ADS_REPORT_OPERATION_TIMEOUT");
        if (status != 200 || redirected) throw new AdsQueryError("ADS_REPORT_HTTP_OR_REDIRECT_REJECTED");
        if (contentLength != null) {
            if (!contentLength.matches("\\d+")) throw new AdsQueryError("ADS_REPORT_BODY_LIMIT");
            long announced;
            try {
                announced = Long.parseLong(contentLength);
            } catch (NumberFormatException e) {
                throw new AdsQueryError("ADS_REPORT_BODY_LIMIT");
            }
            if (announced > limit) throw new AdsQueryError("ADS_REPORT_BODY_LIMIT");
        }
        if (body == null) throw new AdsQueryError("ADS_REPORT_BODY_MISSING");
        if (body.length > limit) throw new AdsQueryError("ADS_REPORT_BODY_LIMIT");
        if (contentLength != null && body.length != Long.parseLong(contentLength)) {
            throw new AdsQueryError("ADS_REPORT_BODY_SIZE_MISMATCH");
        }
        return body;
    }
}

package com.wimoor.ai.report;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ported from {@code amazon-ads/report-dispatch.ts}.
 *
 * <p>The Reporting V1 live queue is reserved but disabled: an environment flag
 * or a manually entered account ID can never enable it. The guard runs before
 * any token refresh, DB mutation, HTTP request or fallback processor.
 */
public final class ReportDispatch {

    private ReportDispatch() {}

    public static final boolean LIVE_ENABLED = false;

    public static final List<String> BLOCKERS = Collections.unmodifiableList(Arrays.asList(
            "DOWNLOADED_JSON_ROW_ENCODING_UNVERIFIED",
            "ACCOUNT_BINDING_UNVERIFIED",
            "DOWNLOAD_HOSTS_UNVERIFIED",
            "PERSISTENT_V1_RUNTIME_NOT_IMPLEMENTED"));

    /** Always throws; kept as an explicit gate rather than a boolean check at call sites. */
    public static void rejectUnverified() {
        throw new AdsQueryError("ADS_REPORT_V1_LIVE_DISPATCH_DISABLED", 403);
    }
}

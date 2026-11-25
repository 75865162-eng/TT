package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/reporting-v3-transport.ts}.
 *
 * <p>Reporting v3 receipts: COMPLETED must carry a URL, FAILED may carry a reason.
 * {@code parseReceipt} accepts either the official 200 body ({@code reportId} +
 * {@code status}) or the {@code report}/{@code reports[]} wrappers, so an upstream
 * version difference cannot cause a false FAILED. Download hosts are restricted to
 * amazonaws.com / amazon.com plus an explicit extra allowlist.
 */
public final class ReportingV3Transport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> STATUSES = new HashSet<>(java.util.Arrays.asList(
            "PENDING", "PROCESSING", "COMPLETED", "FAILED"));

    private ReportingV3Transport() {}

    public static final class Receipt {
        public String reportId;
        public String status;
        public String url;
        public String failureReason;

        public Receipt() {}

        public Receipt(String reportId, String status, String url) {
            this.reportId = reportId;
            this.status = status;
            this.url = url;
        }
    }

    public interface SpReportTransport {
        Receipt create(String region, String clientId, String accessToken, String profileId,
                       String advertiserAccountId, Map<String, Object> request);

        Receipt retrieve(String region, String clientId, String accessToken, String profileId, String reportId);

        byte[] download(String url, long maxBytes);
    }

    /** Deterministic fixture: queued receipts and a URL->bytes download map. */
    public static final class FixtureTransport implements SpReportTransport {
        private final Queue<Receipt> receipts = new ArrayDeque<>();
        private final java.util.Map<String, byte[]> downloads = new java.util.HashMap<>();

        public FixtureTransport enqueue(Receipt receipt) { receipts.add(receipt); return this; }

        public FixtureTransport addDownload(String url, byte[] bytes) { downloads.put(url, bytes); return this; }

        @Override
        public Receipt create(String region, String clientId, String accessToken, String profileId,
                              String advertiserAccountId, Map<String, Object> request) {
            if (receipts.isEmpty()) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
            return receipts.poll();
        }

        @Override
        public Receipt retrieve(String region, String clientId, String accessToken, String profileId, String reportId) {
            if (receipts.isEmpty()) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
            Receipt receipt = receipts.poll();
            if (receipt != null && !reportId.equals(receipt.reportId)) throw new AdsQueryError("ADS_REPORT_ID_MISMATCH");
            return receipt;
        }

        @Override
        public byte[] download(String url, long maxBytes) {
            byte[] bytes = downloads.get(url);
            if (bytes == null || bytes.length > maxBytes) throw new AdsQueryError("ADS_REPORT_DOWNLOAD_FAILED");
            return bytes.clone();
        }
    }

    public static Receipt parseReceipt(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
        }
        if (root == null || !root.isObject()) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
        JsonNode candidate = root.has("report") && root.get("report").isObject()
                ? root.get("report")
                : (root.has("reports") && root.get("reports").isArray() && root.get("reports").size() > 0
                && root.get("reports").get(0).isObject()
                ? root.get("reports").get(0) : root);
        JsonNode reportId = candidate.get("reportId");
        JsonNode statusNode = candidate.get("status");
        String status = statusNode != null && statusNode.isTextual() ? statusNode.asText().toUpperCase() : "";
        if (!safeId(reportId) || !STATUSES.contains(status)) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
        JsonNode url = candidate.get("url");
        JsonNode reason = candidate.get("failureReason");
        String urlText = url != null && url.isTextual() && !url.asText().isEmpty() ? url.asText() : null;
        String reasonText = reason != null && reason.isTextual() ? reason.asText() : null;
        if (reasonText != null && reasonText.length() > 300) reasonText = reasonText.substring(0, 300);
        if ("COMPLETED".equals(status) && urlText == null) throw new AdsQueryError("ADS_REPORT_ENVELOPE_INVALID");
        Receipt receipt = new Receipt();
        receipt.reportId = reportId.asText();
        receipt.status = status;
        receipt.url = urlText;
        receipt.failureReason = reasonText;
        return receipt;
    }

    public static boolean acceptedHost(String host, List<String> extra) {
        String value = host == null ? "" : host.toLowerCase();
        for (String item : extra) if (value.equals(item.toLowerCase())) return true;
        return value.equals("amazonaws.com") || value.endsWith(".amazonaws.com")
                || value.equals("amazon.com") || value.endsWith(".amazon.com");
    }

    private static boolean safeId(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isEmpty()
                && value.asText().length() <= 512 && value.asText().trim().equals(value.asText());
    }
}

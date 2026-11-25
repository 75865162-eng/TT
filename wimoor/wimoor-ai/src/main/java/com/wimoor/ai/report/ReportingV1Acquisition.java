package com.wimoor.ai.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ported from {@code amazon-ads/reporting-v1-acquisition.ts}.
 *
 * <p>Isolated protocol acquisition only — no production factory, queue hook or fact
 * publication. The V2 checkpoint is validated strictly (counters, reservation, part
 * arrays, per-part attempt counts and their sum). One create/poll or one encrypted
 * raw part is handled per dispatch; a reservation survives a crash and a replacement
 * fence may only retry read-only operations.
 */
public final class ReportingV1Acquisition {

    private ReportingV1Acquisition() {}

    public enum Phase { CREATE, POLL, ACQUIRED, DOWNLOADING, RAW_STORED }
    public enum Operation { create, poll, download }

    public static final long MAX_PART_BYTES = 8L * 1024 * 1024;
    public static final long MAX_REPORT_BYTES = 32L * 1024 * 1024;

    public static final Map<Operation, Integer> BUDGETS;
    static {
        Map<Operation, Integer> budgets = new EnumMap<>(Operation.class);
        budgets.put(Operation.create, 1);
        budgets.put(Operation.poll, 10);
        budgets.put(Operation.download, 300);
        BUDGETS = java.util.Collections.unmodifiableMap(budgets);
    }

    public static final class Reservation {
        public Operation operation;
        public Integer partIndex;
        public int fence;
        public String owner;
        public String id;

        public Reservation() {}

        public Reservation(Operation operation, int fence, String owner, String id) {
            this.operation = operation;
            this.fence = fence;
            this.owner = owner;
            this.id = id;
        }
    }

    public static final class Part {
        public String url;
        public long sizeInBytes;
        public String urlExpirationDateTime;

        public Part() {}

        public Part(String url, long sizeInBytes, String urlExpirationDateTime) {
            this.url = url;
            this.sizeInBytes = sizeInBytes;
            this.urlExpirationDateTime = urlExpirationDateTime;
        }
    }

    public static final class DownloadedPart {
        public int partIndex;
        public String fileId;
        public String contentSha256;
        public long byteSize;
    }

    public static final class State {
        public int version = 2;
        public Phase phase = Phase.CREATE;
        public String scopeDigest;
        public String clientIdHash;
        public ReportingV1Transport.CreateReport report;
        public String deadline;
        public final Map<Operation, Integer> calls = new EnumMap<>(Operation.class);
        public Reservation reserved;
        public String reportId;
        public String nextPollAt;
        public final List<Part> parts = new ArrayList<>();
        public final List<DownloadedPart> downloadedParts = new ArrayList<>();
        public final List<Integer> downloadAttempts = new ArrayList<>();

        public State() {
            for (Operation op : Operation.values()) calls.put(op, 0);
        }

        public State copy() {
            State copy = new State();
            copy.version = version;
            copy.phase = phase;
            copy.scopeDigest = scopeDigest;
            copy.clientIdHash = clientIdHash;
            copy.report = report;
            copy.deadline = deadline;
            copy.calls.putAll(calls);
            copy.reportId = reportId;
            copy.nextPollAt = nextPollAt;
            if (reserved != null) {
                copy.reserved = new Reservation(reserved.operation, reserved.fence, reserved.owner, reserved.id);
                copy.reserved.partIndex = reserved.partIndex;
            }
            for (Part part : parts) copy.parts.add(new Part(part.url, part.sizeInBytes, part.urlExpirationDateTime));
            for (DownloadedPart part : downloadedParts) {
                DownloadedPart d = new DownloadedPart();
                d.partIndex = part.partIndex;
                d.fileId = part.fileId;
                d.contentSha256 = part.contentSha256;
                d.byteSize = part.byteSize;
                copy.downloadedParts.add(d);
            }
            copy.downloadAttempts.addAll(downloadAttempts);
            return copy;
        }

        public int totalCalls() {
            return calls.get(Operation.create) + calls.get(Operation.poll) + calls.get(Operation.download);
        }
    }

    public interface Store {
        State load(String taskId);
        void save(String taskId, State state);
    }

    public static final class InMemoryStore implements Store {
        private final Map<String, State> states = new ConcurrentHashMap<>();

        @Override
        public State load(String taskId) {
            State state = states.get(taskId);
            return state == null ? null : state.copy();
        }

        @Override
        public void save(String taskId, State state) { states.put(taskId, state.copy()); }
    }

    private static AdsQueryError invalid() { return new AdsQueryError("ADS_REPORT_V1_CHECKPOINT_INVALID", 409); }

    /** Strict checkpoint validation. */
    public static void readState(State value, long nowMillis) {
        if (value == null || value.version != 2 || value.phase == null
                || value.scopeDigest == null || !value.scopeDigest.matches("[a-f0-9]{64}")
                || value.clientIdHash == null || !value.clientIdHash.matches("[a-f0-9]{64}")) throw invalid();
        if (value.calls.get(Operation.create) < 0 || value.calls.get(Operation.create) > 1
                || value.calls.get(Operation.poll) < 0 || value.calls.get(Operation.poll) > 10
                || value.calls.get(Operation.download) < 0 || value.calls.get(Operation.download) > 300) throw invalid();
        if (value.phase != Phase.CREATE && (value.reportId == null || value.reportId.isEmpty()
                || value.calls.get(Operation.create) != 1)) throw invalid();
        if (value.nextPollAt != null && !ReportDeadline.isFutureOrParseable(value.nextPollAt)) throw invalid();
        ReportingV1Transport.snapshot(value.report);
        ReportDeadline.assertDeadline(value.deadline, nowMillis);
        if (value.reserved != null) {
            Reservation r = value.reserved;
            String expectedPhase = r.operation == Operation.download ? "DOWNLOADING" : r.operation.name().toUpperCase();
            if (!expectedPhase.equals(value.phase.name()) || value.calls.get(r.operation) < 1
                    || r.fence < 0 || r.owner == null || r.owner.isEmpty()
                    || r.id == null || r.id.isEmpty()) throw invalid();
        }
        if (value.parts.size() > 100) throw invalid();
        for (Part part : value.parts) {
            if (part.sizeInBytes < 0 || part.url == null || part.url.length() > 65536
                    || part.urlExpirationDateTime == null
                    || !ReportDeadline.isFutureOrParseable(part.urlExpirationDateTime)) throw invalid();
        }
        if (value.downloadedParts.size() > value.parts.size()) throw invalid();
        if (value.downloadAttempts.size() != value.parts.size()) throw invalid();
        int sum = 0;
        for (int attempt : value.downloadAttempts) {
            if (attempt < 0 || attempt > 3) throw invalid();
            sum += attempt;
        }
        if (sum != value.calls.get(Operation.download)) throw invalid();
        Set<Integer> downloaded = new HashSet<>();
        for (DownloadedPart part : value.downloadedParts) {
            if (part.partIndex < 0 || part.partIndex >= value.parts.size()
                    || part.contentSha256 == null || !part.contentSha256.matches("[a-f0-9]{64}")
                    || part.byteSize != value.parts.get(part.partIndex).sizeInBytes
                    || part.fileId == null || part.fileId.isEmpty()
                    || !downloaded.add(part.partIndex)) throw invalid();
            if (value.downloadAttempts.get(part.partIndex) < 1) throw invalid();
        }
        if (value.reserved != null && value.reserved.operation == Operation.download
                && (value.reserved.partIndex == null || value.reserved.partIndex < 0
                || value.reserved.partIndex >= value.parts.size())) throw invalid();
        if ((value.phase == Phase.CREATE || value.phase == Phase.POLL)
                && (!value.parts.isEmpty() || value.calls.get(Operation.download) > 0
                || !value.downloadedParts.isEmpty())) throw invalid();
        if (value.phase == Phase.RAW_STORED && value.downloadedParts.size() != value.parts.size()) throw invalid();
    }

    /** Selects the next undownloaded part, or returns a blocking error code. */
    public static String selectPart(State state, long nowMillis) {
        if (state.phase != Phase.ACQUIRED && state.phase != Phase.DOWNLOADING) {
            return "ADS_REPORT_V1_DOWNLOAD_NOT_READY";
        }
        if (state.nextPollAt != null && ReportDeadline.parse(state.nextPollAt) > nowMillis) {
            return "ADS_REPORT_POLL_NOT_DUE";
        }
        long total = 0;
        for (Part part : state.parts) total += part.sizeInBytes;
        if (total > MAX_REPORT_BYTES) return "ADS_REPORT_BODY_LIMIT";
        for (Part part : state.parts) if (part.sizeInBytes > MAX_PART_BYTES) return "ADS_REPORT_BODY_LIMIT";
        Set<Integer> saved = new HashSet<>();
        for (DownloadedPart part : state.downloadedParts) saved.add(part.partIndex);
        int partIndex = -1;
        for (int index = 0; index < state.parts.size(); index++) {
            if (!saved.contains(index)) { partIndex = index; break; }
        }
        if (partIndex < 0) return "ADS_REPORT_V1_EMPTY_PARTS_UNVERIFIED";
        if (state.downloadAttempts.get(partIndex) >= 3) return "ADS_REPORT_REQUEST_BUDGET_EXCEEDED";
        if (ReportDeadline.parse(state.parts.get(partIndex).urlExpirationDateTime) <= nowMillis) {
            return "ADS_REPORT_DOWNLOAD_EXPIRED";
        }
        return null;
    }

    /** Applies a successful create/retrieve receipt to the state. */
    public static Phase applyReceipt(State state, ReportingV1Transport.Result result, List<ReportingV1Transport.Part> parts,
                                     long nowMillis) {
        state.reserved = null;
        state.reportId = result.reportId;
        Phase next = "COMPLETED".equals(result.status) ? Phase.ACQUIRED : Phase.POLL;
        state.phase = next;
        if (next == Phase.ACQUIRED) {
            state.parts.clear();
            state.downloadAttempts.clear();
            for (ReportingV1Transport.Part part : parts) {
                state.parts.add(new Part(part.url, part.sizeInBytes, part.urlExpirationDateTime));
                state.downloadAttempts.add(0);
            }
            state.nextPollAt = null;
        } else {
            state.nextPollAt = java.time.Instant.ofEpochMilli(nowMillis + 1000).toString();
        }
        return next;
    }

    /** Poll retry decision preserving the consumed-count based backoff. */
    public static boolean shouldRetryPoll(State state, boolean retrySignal, boolean timeout, long retryAt) {
        return state.calls.get(Operation.poll) < 10
                && (retrySignal || timeout)
                && retryAt != ReportRetry.INFINITE
                && retryAt < ReportDeadline.parse(state.deadline);
    }

    /** Part download retry decision: storage writes are never blindly retried. */
    public static boolean shouldRetryDownload(State state, int partIndex, boolean storageAttempted,
                                              boolean retrySignal, boolean timeout, long retryAt, long nowMillis) {
        String expiry = state.parts.get(partIndex).urlExpirationDateTime;
        return !storageAttempted
                && (retrySignal || timeout)
                && state.downloadAttempts.get(partIndex) < 3
                && retryAt != ReportRetry.INFINITE
                && retryAt < Math.min(ReportDeadline.parse(state.deadline), ReportDeadline.parse(expiry));
    }
}

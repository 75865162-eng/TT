package com.wimoor.ai.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Ported from {@code amazon-ads/report-checkpoint.ts}.
 *
 * <p>Persisted JSON is untrusted: never repair counters and never drop a malformed
 * reservation. Validation is strict so a corrupt checkpoint blocks the task rather
 * than restarting collection from a guessed state. Invariants:
 * <ul>
 *   <li>version === 1 and phase in CREATE/POLL/DOWNLOAD/STAGE/PUBLISH/COMPLETE;</li>
 *   <li>create in [0,1], poll in [0,10], download in [0,3] (integer);</li>
 *   <li>nextPollAt, when present, must be a parseable date;</li>
 *   <li>a reservation must match the current phase, have at least one consumed call,
 *       a non-negative fence and a non-empty owner of at most 200 chars;</li>
 *   <li>1..11 unique non-empty evidence file IDs (each at most 200 chars);</li>
 *   <li>expectedRows, when present, must be an integer in [1,1000].</li>
 * </ul>
 */
public final class ReportCheckpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportCheckpoint() {}

    private static AdsQueryError invalid() {
        return new AdsQueryError("ADS_REPORT_CHECKPOINT_INVALID", 409);
    }

    /** Reads a persisted acquisition checkpoint from JSON, validating every field. */
    public static ReportAcquisition.AcquisitionState read(String json) {
        JsonNode value;
        try {
            value = MAPPER.readTree(json);
        } catch (Exception e) {
            throw invalid();
        }
        if (value == null || !value.isObject()) throw invalid();
        if (!value.path("version").canConvertToInt() || value.path("version").asInt() != 1) throw invalid();
        JsonNode phase = value.get("phase");
        if (phase == null || !phase.isTextual()) throw invalid();
        ReportAcquisition.Phase parsedPhase;
        try {
            parsedPhase = ReportAcquisition.Phase.valueOf(phase.asText());
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        JsonNode calls = value.get("calls");
        if (calls == null || !calls.isObject()) throw invalid();

        ReportAcquisition.AcquisitionState state = new ReportAcquisition.AcquisitionState();
        state.phase = parsedPhase;
        for (ReportAcquisition.Operation op : ReportAcquisition.Operation.values()) {
            JsonNode count = calls.get(op.name());
            if (count == null || !count.isIntegralNumber() || !count.canConvertToInt()) throw invalid();
            int n = count.asInt();
            if (n < 0 || n > ReportAcquisition.BUDGETS.get(op)) throw invalid();
            state.calls.put(op, n);
        }
        if (value.has("nextPollAt")) {
            JsonNode next = value.get("nextPollAt");
            if (!next.isTextual() || !isFiniteDate(next.asText())) throw invalid();
            state.nextPollAt = next.asText();
        }
        if (value.has("reserved")) {
            JsonNode reservation = value.get("reserved");
            if (reservation == null || !reservation.isObject()) throw invalid();
            JsonNode operation = reservation.get("operation");
            if (operation == null || !operation.isTextual()) throw invalid();
            ReportAcquisition.Operation op;
            try {
                op = ReportAcquisition.Operation.valueOf(operation.asText());
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            JsonNode fence = reservation.get("fence");
            JsonNode owner = reservation.get("owner");
            if (!parsedPhase.name().equals(op.name().toUpperCase())
                    || state.calls.get(op) < 1
                    || fence == null || !fence.isIntegralNumber() || !fence.canConvertToInt() || fence.asInt() < 0
                    || owner == null || !owner.isTextual() || owner.asText().isEmpty()
                    || owner.asText().length() > 200) {
                throw invalid();
            }
            state.reserved = new ReportAcquisition.Reservation(op, fence.asInt(), owner.asText());
        }
        JsonNode evidence = value.get("evidenceFileIds");
        if (evidence == null || !evidence.isArray() || evidence.size() > 11) throw invalid();
        Set<String> unique = new HashSet<>();
        for (JsonNode id : evidence) {
            if (!id.isTextual() || id.asText().isEmpty() || id.asText().length() > 200) throw invalid();
            if (!unique.add(id.asText())) throw invalid();
            state.evidenceFileIds.add(id.asText());
        }
        if (value.has("expectedRows")) {
            JsonNode rows = value.get("expectedRows");
            if (rows == null || !rows.isIntegralNumber() || !rows.canConvertToInt()) throw invalid();
            int n = rows.asInt();
            if (n < 1 || n > 1000) throw invalid();
            state.expectedRows = n;
        }
        return state;
    }

    /** Validates an already-typed state with the same rules as {@link #read(String)}. */
    public static void validate(ReportAcquisition.AcquisitionState state) {
        if (state == null || state.version != 1 || state.phase == null) throw invalid();
        for (ReportAcquisition.Operation op : ReportAcquisition.Operation.values()) {
            Integer count = state.calls.get(op);
            if (count == null || count < 0 || count > ReportAcquisition.BUDGETS.get(op)) throw invalid();
        }
        if (state.nextPollAt != null && !isFiniteDate(state.nextPollAt)) throw invalid();
        if (state.reserved != null) {
            ReportAcquisition.Reservation r = state.reserved;
            if (r.operation == null || !state.phase.name().equals(r.operation.name().toUpperCase())
                    || state.calls.get(r.operation) < 1 || r.fence < 0
                    || r.owner == null || r.owner.isEmpty() || r.owner.length() > 200) throw invalid();
        }
        if (state.evidenceFileIds.size() > 11) throw invalid();
        Set<String> unique = new HashSet<>();
        for (String id : state.evidenceFileIds) {
            if (id == null || id.isEmpty() || id.length() > 200 || !unique.add(id)) throw invalid();
        }
        if (state.expectedRows != null && (state.expectedRows < 1 || state.expectedRows > 1000)) throw invalid();
    }

    private static boolean isFiniteDate(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (Exception e) {
            try {
                java.time.OffsetDateTime.parse(value);
                return true;
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }
}

package com.wimoor.ai.lili;

/**
 * 移植自 src/lib/lili/call-quota.ts：ERP 与 Lili 共享的内部调用额度契约，
 * 供应商积分单独记账。这里只定义类型、端口与错误码，实现见 CallQuotaServer。
 */
public final class CallQuota {

    private CallQuota() {}

    public enum MarketProvider {
        SELLERSPRITE("sellersprite"), XIYOU("xiyou");
        private final String wire;
        MarketProvider(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        public static MarketProvider fromWire(String value) {
            for (MarketProvider p : values()) if (p.wire.equals(value)) return p;
            throw new IllegalArgumentException("UNKNOWN_MARKET_PROVIDER");
        }
    }

    public enum MarketCallOutcome {
        COMPLETED, FAILED, UNKNOWN, REFUNDED;
        public static MarketCallOutcome fromWire(String value) {
            if (value == null) return null;
            for (MarketCallOutcome o : values()) if (o.name().equals(value)) return o;
            return null;
        }
    }

    /** MarketCallReservation：callId 同时作为 requestKey 防重放。 */
    public static final class MarketCallReservation {
        public final String callId, runId, workspaceId, tool;
        public final MarketProvider provider;
        public MarketCallReservation(String callId, String runId, String workspaceId, MarketProvider provider, String tool) {
            this.callId = callId; this.runId = runId; this.workspaceId = workspaceId; this.provider = provider; this.tool = tool;
        }
    }

    /** MarketCallQuota 端口。 */
    public interface MarketCallQuota {
        void reserve(MarketCallReservation input);
        void settle(String callId, MarketCallOutcome outcome);
    }

    /** MarketQuotaError 的三个错误码。 */
    public static final class MarketQuotaException extends RuntimeException {
        public enum Code { MARKET_QUOTA_EXHAUSTED, MARKET_QUOTA_REPLAY_BLOCKED, MARKET_QUOTA_UNAVAILABLE }
        public final Code code;
        public MarketQuotaException(Code code) { super(code.name()); this.code = code; }
    }
}

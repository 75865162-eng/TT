package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wimoor.ai.lili.CallQuota.MarketCallOutcome;
import com.wimoor.ai.lili.CallQuota.MarketCallReservation;
import com.wimoor.ai.lili.CallQuota.MarketCallQuota;
import com.wimoor.ai.lili.CallQuota.MarketProvider;
import com.wimoor.ai.lili.CallQuota.MarketQuotaException;
import com.wimoor.ai.lili.CallQuotaServer.AccountScope;
import com.wimoor.ai.lili.CallQuotaServer.GrantInput;
import com.wimoor.ai.lili.CallQuotaServer.QuotaPage;

/** 配额计算与扣减的确定性测试（移植自 call-quota-server.ts）。 */
class CallQuotaServerTest {

    private static final AccountScope SCOPE = new AccountScope("org-1", "user-1");
    private CallQuotaServer server;

    @BeforeEach
    void setUp() {
        server = new CallQuotaServer();
        server.setMarketCallBalance(SCOPE, new GrantInput("owner", 3, 0, "grant-1", "初始额度"));
    }

    private static MarketCallReservation reservation(String callId) {
        return new MarketCallReservation(callId, "run-1", "ws-1", MarketProvider.XIYOU, "get_asin_info");
    }

    @Test
    void reserveDeductsExactlyOneAndSettleCompletedKeepsBalance() {
        MarketCallQuota quota = server.marketCallQuota(SCOPE);
        quota.reserve(reservation("call-1"));
        assertEquals(2, server.readMarketCallQuota(SCOPE, 1).balance);
        quota.settle("call-1", MarketCallOutcome.COMPLETED);
        assertEquals(2, server.readMarketCallQuota(SCOPE, 1).balance);
        assertEquals(1, server.readMarketCallQuota(SCOPE, 1).used);
        assertEquals(0, server.readMarketCallQuota(SCOPE, 1).pending);
    }

    @Test
    void refundRestoresOneAndRepeatedSettleCannotRefundTwice() {
        MarketCallQuota quota = server.marketCallQuota(SCOPE);
        quota.reserve(reservation("call-2"));
        quota.settle("call-2", MarketCallOutcome.REFUNDED);
        assertEquals(3, server.readMarketCallQuota(SCOPE, 1).balance);
        quota.settle("call-2", MarketCallOutcome.REFUNDED);
        assertEquals(3, server.readMarketCallQuota(SCOPE, 1).balance);
        assertEquals(0, server.readMarketCallQuota(SCOPE, 1).used);
    }

    @Test
    void replayIsBlockedByRequestKey() {
        MarketCallQuota quota = server.marketCallQuota(SCOPE);
        quota.reserve(reservation("call-1"));
        MarketQuotaException ex = assertThrows(MarketQuotaException.class, () -> quota.reserve(reservation("call-1")));
        assertEquals(MarketQuotaException.Code.MARKET_QUOTA_REPLAY_BLOCKED, ex.code);
        assertEquals(2, server.readMarketCallQuota(SCOPE, 1).balance);
    }

    @Test
    void exhaustedBalanceIsRejected() {
        MarketCallQuota quota = server.marketCallQuota(SCOPE);
        quota.reserve(reservation("a"));
        quota.reserve(reservation("b"));
        quota.reserve(reservation("c"));
        MarketQuotaException ex = assertThrows(MarketQuotaException.class, () -> quota.reserve(reservation("d")));
        assertEquals(MarketQuotaException.Code.MARKET_QUOTA_EXHAUSTED, ex.code);
    }

    @Test
    void grantValidationAndStaleBalance() {
        assertThrows(IllegalStateException.class, () -> server.setMarketCallBalance(SCOPE,
            new GrantInput("owner", 5, 3, "bad", "   ")));
        assertThrows(IllegalStateException.class, () -> server.setMarketCallBalance(SCOPE,
            new GrantInput("owner", 5, 2, "stale", "调整")));
        CallQuotaServer.GrantResult replay = server.setMarketCallBalance(SCOPE,
            new GrantInput("owner", 9, 3, "grant-1", "重复"));
        assertFalse(replay.applied);
        assertEquals(3, replay.balance);
    }

    @Test
    void paginationHasNextAndCreditConversion() {
        CallQuotaServer big = new CallQuotaServer();
        big.setMarketCallBalance(SCOPE, new GrantInput("owner", 35, 0, "g", "批量"));
        MarketCallQuota quota = big.marketCallQuota(SCOPE);
        for (int i = 0; i < 31; i++) quota.reserve(reservation("bulk-" + i));
        QuotaPage first = big.readMarketCallQuota(SCOPE, 1);
        QuotaPage second = big.readMarketCallQuota(SCOPE, 2);
        assertEquals(30, first.entries.size());
        assertTrue(first.hasNext);
        assertEquals(2, second.entries.size());
        assertFalse(second.hasNext);
        assertEquals(31, first.used);
        assertEquals(31, first.pending);
        assertEquals(0, new BigDecimal("0.30").compareTo(CallQuotaServer.creditsToCny(30)));
        assertThrows(IllegalStateException.class, () -> big.readMarketCallQuota(SCOPE, 0));
    }
}

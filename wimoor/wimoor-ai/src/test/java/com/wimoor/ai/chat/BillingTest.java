package com.wimoor.ai.chat;

import com.wimoor.ai.chat.Billing.InMemoryPointsLedger;
import com.wimoor.ai.chat.Billing.PointsMeter;
import com.wimoor.ai.chat.Billing.Settlement;
import com.wimoor.ai.chat.ChatTypes.ChatScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 覆盖 billing.ts：付费 provider 判定、选品研究单价、影子/强制模式、幂等与释放。 */
class BillingTest {

    private final ChatScope scope = new ChatScope("org", "ws", "user");

    @Test
    void onlySellerSpriteAndXiYouArePaid() {
        assertTrue(Billing.isPaidChatProvider("sellersprite"));
        assertTrue(Billing.isPaidChatProvider("xiyou"));
        assertFalse(Billing.isPaidChatProvider("amazon"));
        assertFalse(Billing.isPaidChatProvider("sellfox"));
        assertFalse(Billing.isPaidChatProvider(null));
        assertNull(Billing.startPaidSourceMeter(scope, "amazon", "r", 1, new InMemoryPointsLedger(BigDecimal.TEN, false)));
        assertNull(Billing.startPaidSourceMeter(scope, "sellfox", "r", 1, new InMemoryPointsLedger(BigDecimal.TEN, false)));
    }

    @Test
    void chargesSelectionResearchPrice() {
        InMemoryPointsLedger ledger = new InMemoryPointsLedger(new BigDecimal("10.0"), false);
        PointsMeter meter = Billing.startPaidSourceMeter(scope, "sellersprite", "run-1", 3, ledger);
        assertNotNull(meter);
        assertEquals("chat-paid:run-1:3:sellersprite", meter.getRequestKey());
        Settlement settled = meter.settle("run-1");
        assertTrue(settled.isCharged());
        assertFalse(settled.isBlocked());
        assertEquals(0, settled.getPoints().compareTo(new BigDecimal("2.0")));
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("8.0")));

        PointsMeter xiyou = Billing.startPaidSourceMeter(scope, "xiyou", "run-1", 4, ledger);
        assertEquals(0, xiyou.settle("run-1").getPoints().compareTo(new BigDecimal("1.5")));
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("6.5")));
    }

    @Test
    void shadowModeMetersButDoesNotBlockWhenBalanceLow() {
        InMemoryPointsLedger ledger = new InMemoryPointsLedger(new BigDecimal("1.0"), false);
        PointsMeter meter = Billing.startPaidSourceMeter(scope, "xiyou", "run", 1, ledger);
        Settlement settled = meter.settle("run");
        assertTrue(settled.isCharged());
        assertFalse(settled.isBlocked());
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("-0.5")));
    }

    @Test
    void enforceModeBlocksWhenBalanceLow() {
        InMemoryPointsLedger ledger = new InMemoryPointsLedger(new BigDecimal("1.0"), true);
        PointsMeter meter = Billing.startPaidSourceMeter(scope, "xiyou", "run", 1, ledger);
        Settlement settled = meter.settle("run");
        assertFalse(settled.isCharged());
        assertTrue(settled.isBlocked());
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("1.0")));
        assertTrue(ledger.entries().get(0).isBlocked());
    }

    @Test
    void replayNeverDoubleCharges() {
        InMemoryPointsLedger ledger = new InMemoryPointsLedger(new BigDecimal("10"), true);
        PointsMeter meter = Billing.startPaidSourceMeter(scope, "sellersprite", "run", 1, ledger);
        assertTrue(meter.settle("run").isCharged());
        Settlement replay = meter.settle("run");
        assertTrue(replay.isDuplicate());
        assertFalse(replay.isCharged());
        assertEquals(1, ledger.entries().size());
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("8")));
    }

    @Test
    void releaseRestoresReservedPoints() {
        InMemoryPointsLedger ledger = new InMemoryPointsLedger(new BigDecimal("10"), true);
        PointsMeter meter = Billing.startPaidSourceMeter(scope, "sellersprite", "run", 1, ledger);
        meter.settle("run");
        meter.release();
        assertEquals(0, ledger.getBalance().compareTo(new BigDecimal("10")));
        assertTrue(ledger.entries().get(0).isReleased());
    }
}

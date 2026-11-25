package com.wimoor.ai.chat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话里付费外部数据源的积分计量。移植自 {@code src/lib/ai-chat/billing.ts}。
 * 约定与系统既有积分层（影子模式）一致：
 * - 只有卖家精灵(sellersprite)与西柚(xiyou)的外部只读工具调用需要积分；Amazon / 赛狐 /
 *   Skill 发现与运行都不额外收费。
 * - 影子模式（默认 enforce=false）只计量、不阻断；enforce=true 时余额不足才拦截。
 *   计量异常被积分层吞掉，绝不成为对话的新故障源。
 * - requestKey = chat-paid:{runId}:{seq}:{provider}，重放不会重复扣分；
 *   结算单价走选品研究定价（agentId=market），金额一律 BigDecimal。
 */
public final class Billing {

    private Billing() {}

    public static final Set<String> PAID_CHAT_PROVIDERS = Set.of("sellersprite", "xiyou");

    /** 选品研究（SELECTION_RESEARCH / agentId=market）单次只读调用单价。 */
    public static final Map<String, BigDecimal> SELECTION_RESEARCH_PRICE = Map.of(
            "sellersprite", new BigDecimal("2.0"),
            "xiyou", new BigDecimal("1.5"));

    public static boolean isPaidChatProvider(String provider) {
        return provider != null && PAID_CHAT_PROVIDERS.contains(provider);
    }

    /** 结算结果：charged 表示实际扣分，blocked 表示强制模式下被拦截，duplicate 表示幂等重放。 */
    public static final class Settlement {
        private final boolean charged;
        private final boolean blocked;
        private final boolean duplicate;
        private final BigDecimal points;
        private final String requestKey;
        public Settlement(boolean charged, boolean blocked, boolean duplicate, BigDecimal points, String requestKey) {
            this.charged = charged; this.blocked = blocked; this.duplicate = duplicate;
            this.points = points; this.requestKey = requestKey;
        }
        public boolean isCharged() { return charged; }
        public boolean isBlocked() { return blocked; }
        public boolean isDuplicate() { return duplicate; }
        public BigDecimal getPoints() { return points; }
        public String getRequestKey() { return requestKey; }
    }

    /** 账本条目（可审计，含是否释放）。 */
    public static final class LedgerEntry {
        private final String requestKey;
        private final String provider;
        private final BigDecimal points;
        private final boolean charged;
        private final boolean blocked;
        private boolean released;
        public LedgerEntry(String requestKey, String provider, BigDecimal points, boolean charged, boolean blocked) {
            this.requestKey = requestKey; this.provider = provider; this.points = points;
            this.charged = charged; this.blocked = blocked;
        }
        public String getRequestKey() { return requestKey; }
        public String getProvider() { return provider; }
        public BigDecimal getPoints() { return points; }
        public boolean isCharged() { return charged; }
        public boolean isBlocked() { return blocked; }
        public boolean isReleased() { return released; }
        public void setReleased(boolean released) { this.released = released; }
    }

    /** 积分账本边界：由系统既有积分层实现；内存实现用于离线/测试。 */
    public interface PointsLedger {
        BigDecimal getBalance();
        boolean isEnforce();
        BigDecimal priceFor(String provider);
        /** 幂等结算；返回是否扣分、是否拦截、是否重复。 */
        Settlement settle(String requestKey, String provider, BigDecimal price);
        /** 工具调用失败/取消时释放预占。 */
        void release(String requestKey);
        List<LedgerEntry> entries();
    }

    /** 对话内单次工具调用的计量句柄，等价 PointsMeter。 */
    public interface PointsMeter {
        Settlement settle(String runId);
        void release();
        String getRequestKey();
    }

    /** 真实积分规则的内存实现：幂等、影子/强制双模式、单价按 provider。 */
    public static final class InMemoryPointsLedger implements PointsLedger {
        private BigDecimal balance;
        private final boolean enforce;
        private final Map<String, LedgerEntry> byRequestKey = new LinkedHashMap<>();

        public InMemoryPointsLedger(BigDecimal balance, boolean enforce) {
            this.balance = balance == null ? BigDecimal.ZERO : balance;
            this.enforce = enforce;
        }

        @Override public BigDecimal getBalance() { return balance; }
        @Override public boolean isEnforce() { return enforce; }

        @Override
        public BigDecimal priceFor(String provider) {
            BigDecimal price = SELECTION_RESEARCH_PRICE.get(provider);
            return price == null ? BigDecimal.ZERO : price;
        }

        @Override
        public synchronized Settlement settle(String requestKey, String provider, BigDecimal price) {
            LedgerEntry existing = byRequestKey.get(requestKey);
            if (existing != null) {
                // 重放：不重复扣分；异常绝不外抛。
                return new Settlement(false, false, true, BigDecimal.ZERO, requestKey);
            }
            BigDecimal cost = price == null ? BigDecimal.ZERO : price;
            boolean insufficient = balance.compareTo(cost) < 0;
            if (insufficient && enforce) {
                LedgerEntry entry = new LedgerEntry(requestKey, provider, cost, false, true);
                byRequestKey.put(requestKey, entry);
                return new Settlement(false, true, false, BigDecimal.ZERO, requestKey);
            }
            // 影子模式余额不足仍只计量不阻断；余额可短暂为负，由积分层后续对账。
            balance = balance.subtract(cost);
            LedgerEntry entry = new LedgerEntry(requestKey, provider, cost, true, false);
            byRequestKey.put(requestKey, entry);
            return new Settlement(true, false, false, cost, requestKey);
        }

        @Override
        public synchronized void release(String requestKey) {
            LedgerEntry entry = byRequestKey.get(requestKey);
            if (entry == null || entry.isReleased()) return;
            if (entry.isCharged()) balance = balance.add(entry.getPoints());
            entry.setReleased(true);
        }

        @Override
        public List<LedgerEntry> entries() {
            return Collections.unmodifiableList(new ArrayList<>(byRequestKey.values()));
        }
    }

    /**
     * 创建付费数据源计量句柄；非付费 provider 返回 null（Amazon/赛狐/Skill 免费）。
     * 与 startAgentMeter 的调用参数一致：organizationId/userId/agentId=market/requestKey。
     */
    public static PointsMeter startPaidSourceMeter(ChatTypes.ChatScope scope, String provider,
                                                   String runId, int seq, PointsLedger ledger) {
        if (!isPaidChatProvider(provider)) return null;
        if (ledger == null) return null;
        // 原样移植 requestKey 派生规则：runId + 工具调用序号 + provider；scope 身份只在会话层复验。
        final String requestKey = "chat-paid:" + runId + ":" + seq + ":" + provider;
        final BigDecimal price = ledger.priceFor(provider);
        return new PointsMeter() {
            @Override public String getRequestKey() { return requestKey; }
            @Override public Settlement settle(String ignoredRunId) {
                try {
                    return ledger.settle(requestKey, provider, price);
                } catch (RuntimeException ex) {
                    // 计量异常被积分层吞掉，永不成为对话故障源。
                    return new Settlement(false, false, false, BigDecimal.ZERO, requestKey);
                }
            }
            @Override public void release() {
                try { ledger.release(requestKey); } catch (RuntimeException ignored) { /* 吞掉，同上 */ }
            }
        };
    }
}

package com.wimoor.ai.lili;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.wimoor.ai.lili.CallQuota.MarketCallOutcome;
import com.wimoor.ai.lili.CallQuota.MarketCallQuota;
import com.wimoor.ai.lili.CallQuota.MarketCallReservation;
import com.wimoor.ai.lili.CallQuota.MarketQuotaException;

/**
 * 移植自 src/lib/lili/call-quota-server.ts。原实现由一行 MarketCallAccount 行锁
 * 串行化所有 provider/device/run/workspace 的预扣与结算；Java 侧用 ConcurrentHashMap +
 * 每账户 synchronized + AtomicInteger 余额实现等价的串行语义。
 * 预扣用 callId 作 requestKey 防重放；重复结算不再退款或扣款。
 */
public final class CallQuotaServer {

    public static final class AccountScope {
        public final String organizationId, userId;
        public AccountScope(String organizationId, String userId) {
            this.organizationId = organizationId; this.userId = userId;
        }
        String key() { return organizationId + "\u0000" + userId; }
    }

    /** QuotaEntry：delta/balanceAfter 使用 BigDecimal/BigInteger 保留精确数值语义。 */
    public static final class QuotaEntry {
        public final String id, kind, status, provider, tool, workspaceId, runId, callId, note;
        public final BigDecimal delta;
        public final BigInteger balanceAfter;
        public final long createdAt, updatedAt;
        QuotaEntry(String id, String kind, BigDecimal delta, String status, BigInteger balanceAfter,
                   String provider, String tool, String workspaceId, String runId, String callId,
                   String note, long createdAt, long updatedAt) {
            this.id = id; this.kind = kind; this.delta = delta; this.status = status;
            this.balanceAfter = balanceAfter; this.provider = provider; this.tool = tool;
            this.workspaceId = workspaceId; this.runId = runId; this.callId = callId; this.note = note;
            this.createdAt = createdAt; this.updatedAt = updatedAt;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id); m.put("kind", kind); m.put("delta", delta); m.put("status", status);
            m.put("balanceAfter", balanceAfter); m.put("provider", provider); m.put("tool", tool);
            m.put("workspaceId", workspaceId); m.put("runId", runId); m.put("callId", callId);
            m.put("note", note); m.put("createdAt", createdAt); m.put("updatedAt", updatedAt);
            return m;
        }
    }

    /** setMarketCallBalance 的输入。 */
    public static final class GrantInput {
        public final String actorUserId, requestKey, note;
        public final int balance, expectedBalance;
        public GrantInput(String actorUserId, int balance, int expectedBalance, String requestKey, String note) {
            this.actorUserId = actorUserId; this.balance = balance; this.expectedBalance = expectedBalance;
            this.requestKey = requestKey; this.note = note;
        }
    }

    public static final class GrantResult {
        public final int balance;
        public final boolean applied;
        GrantResult(int balance, boolean applied) { this.balance = balance; this.applied = applied; }
    }

    /** readMarketCallQuota 结果。 */
    public static final class QuotaPage {
        public final int balance, used, pending, page;
        public final List<QuotaEntry> entries;
        public final boolean hasNext;
        QuotaPage(int balance, int used, int pending, List<QuotaEntry> entries, int page, boolean hasNext) {
            this.balance = balance; this.used = used; this.pending = pending;
            this.entries = entries; this.page = page; this.hasNext = hasNext;
        }
    }

    /** 宿主注入的角色/状态判定（owner 或 database_admin 且账号 active）。 */
    public interface RoleChecker {
        boolean isActiveOwnerOrAdmin(AccountScope scope, String actorUserId);
        boolean isActive(AccountScope scope);
    }

    private static final class Account {
        final AtomicInteger balance = new AtomicInteger();
        final AtomicLong updatedAt = new AtomicLong();
    }

    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QuotaEntry> entries = new ConcurrentHashMap<>();
    private final RoleChecker roles;
    private final LongSupplier clock;
    private final AtomicLong sequence = new AtomicLong();

    public CallQuotaServer(RoleChecker roles, LongSupplier clock) {
        this.roles = roles;
        this.clock = clock;
    }

    /** 默认：所有成员均视为 active owner（宿主应注入真实判定）。 */
    public CallQuotaServer() {
        this(new RoleChecker() {
            @Override public boolean isActiveOwnerOrAdmin(AccountScope scope, String actor) { return actor != null && !actor.isEmpty(); }
            @Override public boolean isActive(AccountScope scope) { return true; }
        }, System::currentTimeMillis);
    }

    private String entryKey(AccountScope scope, String requestKey) {
        return scope.key() + "\u0000" + requestKey;
    }

    private Account account(AccountScope scope) {
        return accounts.computeIfAbsent(scope.key(), k -> new Account());
    }

    /** marketCallQuota(scope) 工厂：同一账户行串行化两个 provider。 */
    public MarketCallQuota marketCallQuota(AccountScope scope) {
        return new MarketCallQuota() {
            @Override
            public void reserve(MarketCallReservation input) {
                Account acct = account(scope);
                try {
                    synchronized (acct) {
                        if (acct.balance.get() < 1) throw new MarketQuotaException(MarketQuotaException.Code.MARKET_QUOTA_EXHAUSTED);
                        if (entries.containsKey(entryKey(scope, input.callId)))
                            throw new MarketQuotaException(MarketQuotaException.Code.MARKET_QUOTA_REPLAY_BLOCKED);
                        int balance = acct.balance.decrementAndGet();
                        long now = clock.getAsLong();
                        acct.updatedAt.set(now);
                        QuotaEntry entry = new QuotaEntry(CryptoSupport.uuid(), "CALL", BigDecimal.valueOf(-1), "RESERVED",
                            BigInteger.valueOf(balance), input.provider == null ? null : input.provider.wire(),
                            input.tool, input.workspaceId, input.runId, input.callId, "市场请求预扣 1 次", now, now);
                        entries.put(entryKey(scope, input.callId), entry);
                    }
                } catch (MarketQuotaException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new MarketQuotaException(MarketQuotaException.Code.MARKET_QUOTA_UNAVAILABLE);
                }
            }

            @Override
            public void settle(String callId, MarketCallOutcome outcome) {
                Account acct = account(scope);
                synchronized (acct) {
                    String key = entryKey(scope, callId);
                    QuotaEntry row = entries.get(key);
                    if (row == null || !"CALL".equals(row.kind) || !"RESERVED".equals(row.status)) return;
                    long now = clock.getAsLong();
                    if (outcome == MarketCallOutcome.REFUNDED) {
                        int balance = acct.balance.incrementAndGet();
                        acct.updatedAt.set(now);
                        entries.put(key, new QuotaEntry(row.id, "CALL", BigDecimal.ZERO, "REFUNDED", BigInteger.valueOf(balance),
                            row.provider, row.tool, row.workspaceId, row.runId, row.callId, "已确认未发送，退回 1 次", row.createdAt, now));
                    } else {
                        entries.put(key, new QuotaEntry(row.id, "CALL", row.delta, outcome.name(), row.balanceAfter,
                            row.provider, row.tool, row.workspaceId, row.runId, row.callId, row.note, row.createdAt, now));
                    }
                }
            }
        };
    }

    private static boolean safeInt(int value) { return value >= 0 && value <= 1_000_000; }

    /** setMarketCallBalance：仅已验证的 owner/database_admin 可授予额度。 */
    public GrantResult setMarketCallBalance(AccountScope scope, GrantInput input) {
        if (!safeInt(input.balance) || input.expectedBalance < 0
            || input.requestKey == null || input.requestKey.isEmpty() || input.requestKey.length() > 200
            || input.note == null || input.note.trim().isEmpty() || input.note.length() > 500)
            throw new IllegalStateException("INVALID_QUOTA_GRANT");
        if (!roles.isActiveOwnerOrAdmin(scope, input.actorUserId) || !roles.isActive(scope))
            throw new IllegalStateException("QUOTA_GRANT_FORBIDDEN");
        Account acct = account(scope);
        synchronized (acct) {
            String key = entryKey(scope, input.requestKey);
            if (entries.containsKey(key)) return new GrantResult(acct.balance.get(), false);
            if (acct.balance.get() != input.expectedBalance) throw new IllegalStateException("STALE_QUOTA_BALANCE");
            int previous = acct.balance.get();
            int delta = input.balance - previous;
            long now = clock.getAsLong();
            acct.balance.set(input.balance);
            acct.updatedAt.set(now);
            entries.put(key, new QuotaEntry(CryptoSupport.uuid(), "ADJUSTMENT", BigDecimal.valueOf(delta), "COMPLETED",
                BigInteger.valueOf(input.balance), null, null, null, null, input.requestKey, input.note, now, now));
            return new GrantResult(input.balance, true);
        }
    }

    /** readMarketCallQuota：pageSize 30，按 createdAt/id 倒序，统计 used/pending。 */
    public QuotaPage readMarketCallQuota(AccountScope scope, int page) {
        if (page < 1 || page > 10_000) throw new IllegalStateException("INVALID_QUOTA_PAGE");
        int pageSize = 30;
        final String prefix = scope.key() + "\u0000";
        List<QuotaEntry> sorted = new ArrayList<>();
        int used = 0, pending = 0;
        for (Map.Entry<String, QuotaEntry> e : entries.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            QuotaEntry row = e.getValue();
            if ("CALL".equals(row.kind)) {
                if (row.delta.compareTo(BigDecimal.valueOf(-1)) == 0) used++;
                if ("RESERVED".equals(row.status) || "UNKNOWN".equals(row.status)) pending++;
            }
            sorted.add(row);
        }
        sorted.sort(Comparator.comparingLong((QuotaEntry e) -> e.createdAt).thenComparing(e -> e.id).reversed());
        int from = Math.min((page - 1) * pageSize, sorted.size());
        int to = Math.min(from + pageSize, sorted.size());
        return new QuotaPage(account(scope).balance.get(), used, pending, new ArrayList<>(sorted.subList(from, to)), page, sorted.size() > to);
    }

    /** 1 research credit = CNY 0.01，精确换算供额度/成本展示使用。 */
    public static BigDecimal creditsToCny(long credits) {
        return BigDecimal.valueOf(credits).multiply(new BigDecimal("0.01"));
    }
}

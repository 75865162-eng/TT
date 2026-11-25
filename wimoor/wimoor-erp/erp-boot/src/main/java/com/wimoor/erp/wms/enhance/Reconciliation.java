package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 余额对账（对应源文件 reconciliation.ts）。
 * 移植了：五个数量桶的流水合计对账、预占剩余量（requested−released−dispatched，
 * 不减 picked）对账、MISMATCH/MATCH 判定。源端用可重复读事务内的 Prisma SQL；
 * 本类定义 ReconciliationRepository 接口与内存实现，真实实现聚合逻辑。
 */
public final class Reconciliation {

    private static final String[] BUCKETS = {"onHand", "pendingQc", "pendingPutaway", "rejected", "reserved"};

    private Reconciliation() {}

    /** 仓储接口：按余额 ID 获取流水合计与预占剩余。 */
    public interface ReconciliationRepository {
        Map<String, String> movementTotals(String organizationId, String workspaceId, List<String> balanceIds);
        Map<String, String> reservationRemaining(String organizationId, String workspaceId, List<String> balanceIds);
    }

    /** 内存实现。 */
    public static final class InMemoryReconciliationRepository implements ReconciliationRepository {
        private final Repository.WmsRepository balances;
        private final List<Repository.Movement> movements;
        private final List<ReservationQuery.ReservationRow> reservations;

        public InMemoryReconciliationRepository(Repository.WmsRepository balances,
                                                List<Repository.Movement> movements,
                                                List<ReservationQuery.ReservationRow> reservations) {
            this.balances = balances;
            this.movements = movements;
            this.reservations = reservations;
        }

        @Override
        public Map<String, String> movementTotals(String organizationId, String workspaceId,
                                                  List<String> balanceIds) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Repository.Movement movement : movements) {
                if (!balanceIds.contains(movement.getBalanceId())) continue;
                String key = movement.getBalanceId() + ":";
                Map<String, Long> totals = new LinkedHashMap<>();
                for (String bucket : BUCKETS) totals.put(bucket, 0L);
                // 仅聚合一次：按 balanceId 键累计
                String[] deltas = {String.valueOf(movement.getDeltaOnHand()),
                        String.valueOf(movement.getDeltaPendingQc()),
                        String.valueOf(movement.getDeltaPendingPutaway()),
                        String.valueOf(movement.getDeltaRejected()),
                        String.valueOf(movement.getDeltaReserved())};
                for (int i = 0; i < BUCKETS.length; i++) {
                    String mapKey = key + BUCKETS[i];
                    long current = Long.parseLong(out.getOrDefault(mapKey, "0"));
                    out.put(mapKey, String.valueOf(current + Long.parseLong(deltas[i])));
                }
            }
            return out;
        }

        @Override
        public Map<String, String> reservationRemaining(String organizationId, String workspaceId,
                                                        List<String> balanceIds) {
            Map<String, String> out = new LinkedHashMap<>();
            for (ReservationQuery.ReservationRow row : reservations) {
                if (!balanceIds.contains(row.getBalanceId())) continue;
                long current = Long.parseLong(out.getOrDefault(row.getBalanceId(), "0"));
                out.put(row.getBalanceId(), String.valueOf(current + row.remaining()));
            }
            return out;
        }
    }

    /** 对账行输入。 */
    public static final class BalanceRow {
        private final String id;
        private final int onHand;
        private final int pendingQc;
        private final int pendingPutaway;
        private final int rejected;
        private final int reserved;

        public BalanceRow(String id, int onHand, int pendingQc, int pendingPutaway, int rejected, int reserved) {
            this.id = id;
            this.onHand = onHand;
            this.pendingQc = pendingQc;
            this.pendingPutaway = pendingPutaway;
            this.rejected = rejected;
            this.reserved = reserved;
        }
    }

    /** reconcileBalances。 */
    public static List<Map<String, Object>> reconcileBalances(ReconciliationRepository repo,
                                                              String organizationId, String workspaceId,
                                                              List<BalanceRow> balances) {
        if (balances.isEmpty()) return new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (BalanceRow balance : balances) ids.add(balance.id);
        Map<String, String> totals = repo.movementTotals(organizationId, workspaceId, ids);
        Map<String, String> remaining = repo.reservationRemaining(organizationId, workspaceId, ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (BalanceRow balance : balances) {
            int[] recorded = {balance.onHand, balance.pendingQc, balance.pendingPutaway,
                    balance.rejected, balance.reserved};
            List<Map<String, Object>> differences = new ArrayList<>();
            for (int i = 0; i < BUCKETS.length; i++) {
                String movementTotal = totals.getOrDefault(balance.id + ":" + BUCKETS[i], "0");
                if (!BigInteger.valueOf(recorded[i]).equals(new BigInteger(movementTotal))) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("bucket", BUCKETS[i]);
                    diff.put("recorded", String.valueOf(recorded[i]));
                    diff.put("movementTotal", movementTotal);
                    differences.add(diff);
                }
            }
            String reservationRemaining = remaining.getOrDefault(balance.id, "0");
            Map<String, Object> reservationDifference = null;
            if (!BigInteger.valueOf(balance.reserved).equals(new BigInteger(reservationRemaining))) {
                reservationDifference = new LinkedHashMap<>();
                reservationDifference.put("recorded", String.valueOf(balance.reserved));
                reservationDifference.put("reservationRemaining", reservationRemaining);
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", balance.id);
            view.put("onHand", balance.onHand);
            view.put("pendingQc", balance.pendingQc);
            view.put("pendingPutaway", balance.pendingPutaway);
            view.put("rejected", balance.rejected);
            view.put("reserved", balance.reserved);
            view.put("reconciliationStatus", differences.isEmpty() && reservationDifference == null
                    ? "MATCH" : "MISMATCH");
            view.put("movementCount", totals.containsKey(balance.id + ":onHand") ? "1" : "0");
            view.put("differences", differences);
            view.put("reservationRemaining", reservationRemaining);
            view.put("reservationDifference", reservationDifference);
            out.add(view);
        }
        return out;
    }
}

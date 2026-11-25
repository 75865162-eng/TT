package com.wimoor.erp.wms.enhance;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存历史查询（对应源文件 inventory-history-query.ts）。
 * 移植了：inventoryHistoryRange 严格 UTC 时间校验（起止间隔 <=366 天、不得未来）、
 * presentInventoryHistory 的账实一致性判定与数量/体积数据状态输出。
 * 源端从 Prisma 聚合 WmsMovement；本类给出范围校验纯函数与基于 movement 列表的聚合。
 */
public final class InventoryHistoryQuery {

    /** 时间范围。 */
    public static final class Range {
        private final Instant from;
        private final Instant to;

        public Range(Instant from, Instant to) {
            this.from = from;
            this.to = to;
        }

        public Instant getFrom() { return from; }
        public Instant getTo() { return to; }
    }

    private InventoryHistoryQuery() {}

    /** inventoryHistoryRange：严格 UTC ISO 校验。 */
    public static Range inventoryHistoryRange(Object fromRaw, Object toRaw) {
        Instant start = parseInstant(fromRaw);
        Instant end = parseInstant(toRaw);
        if (!start.isBefore(end) || end.toEpochMilli() > System.currentTimeMillis() + 1000
                || end.toEpochMilli() - start.toEpochMilli() > 366L * 86400000L) {
            throw new WmsError("库存期间须为已发生的时间，起止间隔不超过 366 天。", 400);
        }
        return new Range(start, end);
    }

    private static Instant parseInstant(Object value) {
        if (!(value instanceof String)) throw new WmsError("库存期间须为明确 UTC 时间。", 400);
        String text = (String) value;
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z")) {
            throw new WmsError("库存期间须为明确 UTC 时间。", 400);
        }
        try {
            Instant parsed = Instant.parse(text.length() == 20 ? text.replace("Z", ".000Z") : text);
            String normalized = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC).format(parsed);
            String expected = text.length() == 20 ? text.replace("Z", ".000Z") : text;
            if (!normalized.equals(expected)) throw new WmsError("库存期间日期无效。", 400);
            return parsed;
        } catch (DateTimeParseException e) {
            throw new WmsError("库存期间日期无效。", 400);
        }
    }

    /** 余额输入。 */
    public static final class BalanceRow {
        private final String id;
        private final int onHand;
        private final int pendingQc;
        private final int pendingPutaway;
        private final int rejected;
        private final int reserved;
        private final Object stock;

        public BalanceRow(String id, int onHand, int pendingQc, int pendingPutaway, int rejected,
                          int reserved, Object stock) {
            this.id = id;
            this.onHand = onHand;
            this.pendingQc = pendingQc;
            this.pendingPutaway = pendingPutaway;
            this.rejected = rejected;
            this.reserved = reserved;
            this.stock = stock;
        }
    }

    /** 基于 movement 列表的历史展示（源 SQL 的内存等价聚合）。 */
    public static List<Map<String, Object>> presentInventoryHistory(List<BalanceRow> balances,
                                                                    Range range,
                                                                    List<Repository.Movement> movements) {
        if (balances.isEmpty()) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (BalanceRow balance : balances) {
            long openingQty = 0, incomingQty = 0, outgoingQty = 0, closingQty = 0;
            long closingReserved = 0, closingQc = 0, closingPutaway = 0, closingRejected = 0;
            long movementCount = 0;
            long ledgerOnHand = 0, ledgerQc = 0, ledgerPutaway = 0, ledgerRejected = 0, ledgerReserved = 0;
            boolean missingVolume = false;
            for (Repository.Movement movement : movements) {
                if (!movement.getBalanceId().equals(balance.id)) continue;
                Instant occurred = movement.getOccurredAt().toInstant();
                if (occurred.isBefore(range.getTo())) movementCount++;
                int d = movement.getDeltaOnHand();
                ledgerOnHand += d;
                ledgerQc += movement.getDeltaPendingQc();
                ledgerPutaway += movement.getDeltaPendingPutaway();
                ledgerRejected += movement.getDeltaRejected();
                ledgerReserved += movement.getDeltaReserved();
                if (d != 0 && movement.getDeltaVolumeCubicMeters() == null && occurred.isBefore(range.getTo())) {
                    missingVolume = true;
                }
                if (occurred.isBefore(range.getFrom())) {
                    openingQty += d;
                    closingReserved += movement.getDeltaReserved();
                    closingQc += movement.getDeltaPendingQc();
                    closingPutaway += movement.getDeltaPendingPutaway();
                    closingRejected += movement.getDeltaRejected();
                }
                if (!occurred.isBefore(range.getFrom()) && occurred.isBefore(range.getTo())) {
                    if (d > 0) incomingQty += d;
                    if (d < 0) outgoingQty -= d;
                    closingReserved += movement.getDeltaReserved();
                    closingQc += movement.getDeltaPendingQc();
                    closingPutaway += movement.getDeltaPendingPutaway();
                    closingRejected += movement.getDeltaRejected();
                } else if (occurred.isBefore(range.getTo())) {
                    closingReserved += movement.getDeltaReserved();
                    closingQc += movement.getDeltaPendingQc();
                    closingPutaway += movement.getDeltaPendingPutaway();
                    closingRejected += movement.getDeltaRejected();
                }
                if (occurred.isBefore(range.getTo())) closingQty += d;
            }
            boolean complete = balance.onHand == ledgerOnHand && balance.pendingQc == ledgerQc
                    && balance.pendingPutaway == ledgerPutaway && balance.rejected == ledgerRejected
                    && balance.reserved == ledgerReserved;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", balance.id);
            view.put("stock", balance.stock);
            view.put("from", range.getFrom().toString());
            view.put("to", range.getTo().toString());
            view.put("basis", "WMS_POSTED_MOVEMENTS");
            view.put("dataStatus", complete ? "COMPLETE" : "NEEDS_DATA");
            view.put("movementCount", String.valueOf(movementCount));
            view.put("volumeStatus", complete && !missingVolume ? "COMPLETE" : "NEEDS_DATA");
            String[] volKeys = {"openingVolume", "incomingVolume", "outgoingVolume", "closingVolume"};
            for (String key : volKeys) view.put(key, complete && !missingVolume ? "0.000000000" : null);
            view.put("openingQty", complete ? String.valueOf(openingQty) : null);
            view.put("incomingQty", complete ? String.valueOf(incomingQty) : null);
            view.put("outgoingQty", complete ? String.valueOf(outgoingQty) : null);
            view.put("closingQty", complete ? String.valueOf(closingQty) : null);
            view.put("closingReserved", complete ? String.valueOf(closingReserved) : null);
            view.put("closingPendingQc", complete ? String.valueOf(closingQc) : null);
            view.put("closingPendingPutaway", complete ? String.valueOf(closingPutaway) : null);
            view.put("closingRejected", complete ? String.valueOf(closingRejected) : null);
            out.add(view);
        }
        return out;
    }
}

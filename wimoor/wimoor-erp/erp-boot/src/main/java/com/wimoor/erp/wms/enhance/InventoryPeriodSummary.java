package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存期间汇总（对应源文件 inventory-period-summary.ts）。
 * 移植了：inventoryPeriodBoundaries（DAY/MONTH UTC 边界、31 天上限）、
 * buildInventoryPeriods（期初/入库/出库/期末滚动、按动作与来源分类、
 * otherIn/otherOut 差额）。源端从 Prisma 聚合行；本类提供纯计算函数供内存聚合复用。
 */
public final class InventoryPeriodSummary {

    /** InventoryGranularity。 */
    public enum Granularity { DAY, MONTH }

    /** 期间。 */
    public static final class Period {
        private final String from;
        private final String to;

        public Period(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String getFrom() { return from; }
        public String getTo() { return to; }
    }

    /** 边界结果。 */
    public static final class Boundaries {
        private final Granularity granularity;
        private final List<Period> periods;

        public Boundaries(Granularity granularity, List<Period> periods) {
            this.granularity = granularity;
            this.periods = periods;
        }

        public Granularity getGranularity() { return granularity; }
        public List<Period> getPeriods() { return periods; }
    }

    /** 聚合行（不含 balanceId）。 */
    public static final class Aggregate {
        private final String bucket;
        private final String action;
        private final String sourceType;
        private final String incoming;
        private final String outgoing;

        public Aggregate(String bucket, String action, String sourceType, String incoming, String outgoing) {
            this.bucket = bucket;
            this.action = action;
            this.sourceType = sourceType;
            this.incoming = incoming;
            this.outgoing = outgoing;
        }

        public String getBucket() { return bucket; }
        public String getAction() { return action; }
        public String getSourceType() { return sourceType; }
        public String getIncoming() { return incoming; }
        public String getOutgoing() { return outgoing; }
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private InventoryPeriodSummary() {}

    /** inventoryPeriodBoundaries(raw)：校验边界并生成期间序列。 */
    public static Boundaries inventoryPeriodBoundaries(Instant from, Instant to, Object raw) {
        Granularity granularity;
        if ("DAY".equals(raw)) granularity = Granularity.DAY;
        else if ("MONTH".equals(raw)) granularity = Granularity.MONTH;
        else throw new WmsError("历史汇总粒度须为 DAY 或 MONTH。", 400);
        LocalDate fromDate = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = to.atZone(ZoneOffset.UTC).toLocalDate();
        boolean badTime = from.getNano() != 0 || from.atZone(ZoneOffset.UTC).getHour() != 0
                || from.atZone(ZoneOffset.UTC).getMinute() != 0 || from.atZone(ZoneOffset.UTC).getSecond() != 0
                || to.getNano() != 0 || to.atZone(ZoneOffset.UTC).getHour() != 0
                || to.atZone(ZoneOffset.UTC).getMinute() != 0 || to.atZone(ZoneOffset.UTC).getSecond() != 0;
        if (badTime || (granularity == Granularity.MONTH
                && (fromDate.getDayOfMonth() != 1 || toDate.getDayOfMonth() != 1))) {
            throw new WmsError("日/月汇总须以完整 UTC 日/月边界查询。", 400);
        }
        if (granularity == Granularity.DAY && to.toEpochMilli() - from.toEpochMilli() > 31L * 86400000L) {
            throw new WmsError("逐日汇总一次最多 31 天。", 400);
        }
        List<Period> periods = new ArrayList<>();
        Instant start = from;
        while (start.isBefore(to)) {
            Instant end = granularity == Granularity.DAY
                    ? start.plusMillis(86400000L)
                    : LocalDate.ofInstant(start, ZoneOffset.UTC).plusMonths(1)
                            .atStartOfDay(ZoneOffset.UTC).toInstant();
            periods.add(new Period(ISO.format(start), ISO.format(end)));
            start = end;
        }
        return new Boundaries(granularity, periods);
    }

    /** buildInventoryPeriods：滚动数量并按来源分类。 */
    public static List<Map<String, Object>> buildInventoryPeriods(List<Period> periods, String opening,
                                                                  List<Aggregate> rows) {
        BigInteger quantity = opening == null ? null : new BigInteger(opening);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Period period : periods) {
            List<Aggregate> matches = new ArrayList<>();
            for (Aggregate row : rows) if (row.getBucket().equals(period.getFrom())) matches.add(row);
            BigInteger incoming = sum(matches, "incoming", null, null);
            BigInteger outgoing = sum(matches, "outgoing", null, null);
            BigInteger start = quantity;
            quantity = quantity == null ? null : quantity.add(incoming).subtract(outgoing);
            BigInteger receipt = sum(matches, "incoming", List.of("RECEIPT"), List.of());
            BigInteger shipment = sum(matches, "outgoing", List.of("RESERVATION"), List.of("DISPATCH"));
            BigInteger transferIn = sum(matches, "incoming", List.of("TRANSFER_ORDER"), List.of("TRANSFER"));
            BigInteger transferOut = sum(matches, "outgoing", List.of("TRANSFER_ORDER"), List.of("TRANSFER"));
            BigInteger conversionIn = sum(matches, "incoming",
                    List.of("SKU_CONVERSION", "SKU_CONVERSION_REVERSAL"), List.of());
            BigInteger conversionOut = sum(matches, "outgoing",
                    List.of("SKU_CONVERSION", "SKU_CONVERSION_REVERSAL"), List.of());
            BigInteger gain = sum(matches, "incoming", List.of("STOCKTAKE"), List.of("STOCKTAKE_POST"));
            BigInteger loss = sum(matches, "outgoing", List.of("STOCKTAKE"), List.of("STOCKTAKE_POST"));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("from", period.getFrom());
            view.put("to", period.getTo());
            view.put("dataStatus", opening == null ? "NEEDS_DATA" : "COMPLETE");
            view.put("openingQty", start == null ? null : start.toString());
            view.put("incomingQty", opening == null ? null : incoming.toString());
            view.put("outgoingQty", opening == null ? null : outgoing.toString());
            view.put("closingQty", quantity == null ? null : quantity.toString());
            Map<String, Object> categories = null;
            if (opening != null) {
                categories = new LinkedHashMap<>();
                categories.put("receiptQty", receipt.toString());
                categories.put("shipmentQty", shipment.toString());
                categories.put("transferInQty", transferIn.toString());
                categories.put("transferOutQty", transferOut.toString());
                categories.put("conversionInQty", conversionIn.toString());
                categories.put("conversionOutQty", conversionOut.toString());
                categories.put("stocktakeGainQty", gain.toString());
                categories.put("stocktakeLossQty", loss.toString());
                categories.put("otherInQty", incoming.subtract(receipt).subtract(transferIn)
                        .subtract(conversionIn).subtract(gain).toString());
                categories.put("otherOutQty", outgoing.subtract(shipment).subtract(transferOut)
                        .subtract(conversionOut).subtract(loss).toString());
            }
            view.put("categories", categories);
            view.put("turnoverRate", null);
            view.put("turnoverDays", null);
            out.add(view);
        }
        return out;
    }

    private static BigInteger sum(List<Aggregate> rows, String side, List<String> sources, List<String> actions) {
        BigInteger total = BigInteger.ZERO;
        for (Aggregate row : rows) {
            boolean match = sources == null || sources.contains(row.getSourceType())
                    || actions.contains(row.getAction());
            if (!match) continue;
            String value = side.equals("incoming") ? row.getIncoming() : row.getOutgoing();
            if (value != null) total = total.add(new BigInteger(value));
        }
        return total;
    }
}

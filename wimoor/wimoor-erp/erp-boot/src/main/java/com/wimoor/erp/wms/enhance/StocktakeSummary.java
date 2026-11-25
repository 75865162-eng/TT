package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 盘点汇总（对应源文件 stocktake-summary.ts）。
 * 移植了 stocktakeSummary：盲盘隐藏账面与差异、COMPLETE/NEEDS_DATA 判定、
 * 毛盈/毛亏分开统计、按库位与批次维度汇总（即使单据净额为零也不抵消）。
 */
public final class StocktakeSummary {

    private StocktakeSummary() {}

    /** CountLine。 */
    public static final class CountLine {
        private final String lotId;
        private final int baselineOnHand;
        private final Integer countedQty;

        public CountLine(String lotId, int baselineOnHand, Integer countedQty) {
            this.lotId = lotId;
            this.baselineOnHand = baselineOnHand;
            this.countedQty = countedQty;
        }

        public String getLotId() { return lotId; }
        public int getBaselineOnHand() { return baselineOnHand; }
        public Integer getCountedQty() { return countedQty; }
    }

    /** Location。 */
    public static final class Location {
        private final String locationId;
        private final List<CountLine> lines;

        public Location(String locationId, List<CountLine> lines) {
            this.locationId = locationId;
            this.lines = lines;
        }

        public String getLocationId() { return locationId; }
        public List<CountLine> getLines() { return lines; }
    }

    private static Map<String, Object> totals(List<CountLine> items, boolean hidden) {
        boolean complete = !items.isEmpty();
        for (CountLine item : items) if (item.getCountedQty() == null) { complete = false; break; }
        BigInteger baseline = BigInteger.ZERO, counted = BigInteger.ZERO;
        BigInteger gain = BigInteger.ZERO, loss = BigInteger.ZERO;
        int countedLines = 0;
        for (CountLine item : items) {
            if (item.getCountedQty() != null) countedLines++;
            baseline = baseline.add(BigInteger.valueOf(item.getBaselineOnHand()));
            if (item.getCountedQty() != null) {
                counted = counted.add(BigInteger.valueOf(item.getCountedQty()));
                int diff = item.getCountedQty() - item.getBaselineOnHand();
                if (diff > 0) gain = gain.add(BigInteger.valueOf(diff));
                else loss = loss.add(BigInteger.valueOf(-diff));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lineCount", items.size());
        out.put("countedLines", countedLines);
        out.put("baselineQty", hidden ? null : baseline.toString());
        out.put("countedQty", complete ? counted.toString() : null);
        out.put("gainQty", hidden || !complete ? null : gain.toString());
        out.put("lossQty", hidden || !complete ? null : loss.toString());
        return out;
    }

    /** stocktakeSummary。 */
    public static Map<String, Object> stocktakeSummary(boolean blind, String status, List<Location> rawLocations) {
        List<Location> locations = rawLocations == null ? new ArrayList<>() : rawLocations;
        List<CountLine> lines = new ArrayList<>();
        for (Location location : locations) lines.addAll(location.getLines());
        boolean hidden = blind && ("DRAFT".equals(status) || "COUNTING".equals(status));
        boolean allCounted = !lines.isEmpty();
        for (CountLine line : lines) if (line.getCountedQty() == null) { allCounted = false; break; }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataStatus", allCounted ? "COMPLETE" : "NEEDS_DATA");
        out.put("baselineVisible", !hidden);
        out.putAll(totals(lines, hidden));

        List<Map<String, Object>> locationViews = new ArrayList<>();
        for (Location location : locations) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("locationId", location.getLocationId());
            view.putAll(totals(location.getLines(), hidden));
            locationViews.add(view);
        }
        out.put("locations", locationViews);

        Set<String> lots = new LinkedHashSet<>();
        for (CountLine line : lines) lots.add(line.getLotId());
        List<String> sortedLots = new ArrayList<>(lots);
        sortedLots.sort(String::compareTo);
        List<Map<String, Object>> lotViews = new ArrayList<>();
        for (String lotId : sortedLots) {
            List<CountLine> lotLines = new ArrayList<>();
            for (CountLine line : lines) if (line.getLotId().equals(lotId)) lotLines.add(line);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("lotId", lotId);
            view.putAll(totals(lotLines, hidden));
            lotViews.add(view);
        }
        out.put("lots", lotViews);
        return out;
    }
}

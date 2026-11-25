package com.wimoor.ai.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ported from {@code mock-provider.ts} together with {@code types.ts}.
 *
 * <p>Transport-free mock data provider for UI/tests. Derived metrics use zero when a
 * denominator is zero (the UI mock contract differs from the production AdsMetrics
 * rule, where a missing denominator must stay null). CSV export quotes values safely.
 */
public final class AdsMockProvider {

    private AdsMockProvider() {}

    public static final Map<String, String> METRIC_LABELS = new LinkedHashMap<>();
    static {
        METRIC_LABELS.put("impressions", "展示量");
        METRIC_LABELS.put("clicks", "点击量");
        METRIC_LABELS.put("ctr", "CTR");
        METRIC_LABELS.put("spend", "总成本");
        METRIC_LABELS.put("cpc", "CPC");
        METRIC_LABELS.put("orders", "购买量");
        METRIC_LABELS.put("sales", "销售额");
        METRIC_LABELS.put("acos", "ACOS");
        METRIC_LABELS.put("roas", "ROAS");
        METRIC_LABELS.put("conversion", "购买率");
    }

    public static class Facts {
        public double impressions;
        public double clicks;
        public double spend;
        public double orders;
        public double sales;

        public Facts() {}

        public Facts(double impressions, double clicks, double spend, double orders, double sales) {
            this.impressions = impressions;
            this.clicks = clicks;
            this.spend = spend;
            this.orders = orders;
            this.sales = sales;
        }
    }

    public static final class Metrics extends Facts {
        public double ctr;
        public double cpc;
        public double acos;
        public double roas;
        public double conversion;

        public Metrics() {}

        public Metrics(Facts source) {
            super(source.impressions, source.clicks, source.spend, source.orders, source.sales);
            ctr = source.impressions != 0 ? source.clicks / source.impressions * 100 : 0;
            cpc = source.clicks != 0 ? source.spend / source.clicks : 0;
            acos = source.sales != 0 ? source.spend / source.sales * 100 : 0;
            roas = source.spend != 0 ? source.sales / source.spend : 0;
            conversion = source.clicks != 0 ? source.orders / source.clicks * 100 : 0;
        }
    }

    public static Metrics metrics(Facts f) { return new Metrics(f); }

    public static Metrics sumMetrics(List<Facts> rows) {
        Facts sum = new Facts();
        for (Facts row : rows) {
            sum.impressions += row.impressions;
            sum.clicks += row.clicks;
            sum.spend += row.spend;
            sum.orders += row.orders;
            sum.sales += row.sales;
        }
        return metrics(sum);
    }

    public static List<String> datesInRange(String from, String to) {
        List<String> dates = new ArrayList<>();
        LocalDate start = LocalDate.parse(from);
        LocalDate end = LocalDate.parse(to);
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) dates.add(day.toString());
        return dates;
    }

    /** RFC-4180-style CSV with deterministic column order and escaped quotes. */
    public static String csv(List<Map<String, Object>> rows, List<Map.Entry<String, String>> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(columns.get(i).getValue()));
        }
        sb.append('\n');
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(',');
                Object value = row.get(columns.get(i).getKey());
                sb.append(escape(value == null ? "" : String.valueOf(value)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

package com.wimoor.ai.lili;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 移植自 src/lib/lili/labels.ts：标签中英对照表与 tagLabel(locale, id)。 */
public final class Labels {

    private Labels() {}

    public static final class Label {
        public final String zh;
        public final String en;
        Label(String zh, String en) { this.zh = zh; this.en = en; }
    }

    public static final Map<String, Label> TAGS;
    static {
        Map<String, Label> tags = new LinkedHashMap<>();
        tags.put("reports", new Label("报表与分析", "Reports and analysis"));
        tags.put("ads", new Label("广告", "Ads"));
        tags.put("market", new Label("市场", "Market"));
        tags.put("listing", new Label("Listing", "Listing"));
        tags.put("profit", new Label("利润", "Profit"));
        tags.put("restock", new Label("补货", "Restock"));
        tags.put("data", new Label("数据", "Data"));
        tags.put("returns", new Label("退货", "Returns"));
        tags.put("keywords", new Label("关键词", "Keywords"));
        tags.put("compliance", new Label("合规", "Compliance"));
        tags.put("orders", new Label("订单", "Orders"));
        tags.put("featured", new Label("精选", "Featured"));
        TAGS = Collections.unmodifiableMap(tags);
    }

    /** 未登记的 id 原样返回。 */
    public static String tagLabel(Tx.Locale locale, String id) {
        Label row = TAGS.get(id);
        return row == null ? id : Tx.tx(locale, row.zh, row.en);
    }
}

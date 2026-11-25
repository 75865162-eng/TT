package com.wimoor.ai.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic field-mapping tests for every parser contract. */
class ReportParserTest {

    private static byte[] gzip(String value) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(value.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) row.put((String) kv[i], kv[i + 1]);
        return row;
    }

    @Test
    void queryContractValidatesAliasesDatesAndDefaults() {
        AdsQueryContract.Input input = AdsQueryContract.parse(AdsQueryContract.params(
                "kind", "reports", "from", "2026-11-01", "to", "2026-11-07"), 0L);
        assertEquals("reports", input.kind);
        assertEquals("2026-11-01", input.from);
        assertEquals("2026-11-07", input.to);
        assertEquals(1, input.page);
        assertEquals(50, input.pageSize);
        assertEquals("reportDate", input.sortBy);
        assertEquals("desc", input.sortDirection);

        AdsQueryContract.Input defaults = AdsQueryContract.parse(AdsQueryContract.params("kind", "reports"),
                java.time.Instant.parse("2026-11-25T00:00:00Z").toEpochMilli());
        assertEquals("2026-10-27", defaults.from, "default window is last 30 days ending today");
        assertEquals("2026-11-25", defaults.to);

        assertEquals("Ads 数据类型无效。", assertThrows(AdsQueryError.class,
                () -> AdsQueryContract.parse(AdsQueryContract.params("kind", "bogus"), 0L)).getCode());
        assertThrows(AdsQueryError.class, () -> AdsQueryContract.parse(AdsQueryContract.params(
                "kind", "entities", "from", "2026-11-01"), 0L));
        assertThrows(AdsQueryError.class, () -> AdsQueryContract.parse(AdsQueryContract.params(
                "profileRecordId", "a", "profileId", "b"), 0L));
        assertThrows(AdsQueryError.class, () -> AdsQueryContract.parse(AdsQueryContract.params(
                "from", "2026-11-10", "to", "2026-11-01"), 0L));
        assertDoesNotThrow(() -> AdsQueryContract.parse(AdsQueryContract.params(
                "kind", "reports", "from", "2026-01-01", "to", "2027-01-01"), 0L), "367 days is allowed");
        assertThrows(AdsQueryError.class, () -> AdsQueryContract.parse(AdsQueryContract.params(
                "kind", "reports", "from", "2026-01-01", "to", "2027-01-03"), 0L));
    }

    @Test
    void campaignV1ParserMapsExactFieldsAndRejectsDuplicateGrains() {
        List<Object> rows = new ArrayList<>();
        rows.add(row(
                "date.value", "2026-11-01",
                "campaign.id", "123456789",
                "campaign.name", "Core keywords",
                "budgetCurrency.value", "USD",
                "metric.impressions", new ReportingV1Json.JsonNumber("10000"),
                "metric.clicks", new ReportingV1Json.JsonNumber("500"),
                "metric.totalCost", new ReportingV1Json.JsonNumber("12.500000"),
                "metric.purchases", new ReportingV1Json.JsonNumber("25"),
                "metric.sales", new ReportingV1Json.JsonNumber("500.250000"),
                "metric.unitsSold", new ReportingV1Json.JsonNumber("30")));
        List<CampaignReportingV1Parser.Fact> facts =
                CampaignReportingV1Parser.mapRows(rows, "2026-11-01", "2026-11-07");
        CampaignReportingV1Parser.Fact fact = facts.get(0);
        assertEquals("2026-11-01", fact.reportDate);
        assertEquals("123456789", fact.campaignId);
        assertEquals("Core keywords", fact.campaignName);
        assertEquals("USD", fact.currencyCode);
        assertEquals("10000", fact.impressions);
        assertEquals("12.500000", fact.spend, "metric.totalCost -> spend, exact lexeme");
        assertEquals("25", fact.orders, "metric.purchases -> orders");
        assertEquals("500.250000", fact.sales, "metric.sales -> sales");
        assertEquals("30", fact.units);

        rows.add(row(
                "date.value", "2026-11-01", "campaign.id", "123456789",
                "campaign.name", "dup", "budgetCurrency.value", "USD",
                "metric.impressions", new ReportingV1Json.JsonNumber("0"),
                "metric.clicks", new ReportingV1Json.JsonNumber("0"),
                "metric.totalCost", new ReportingV1Json.JsonNumber("0"),
                "metric.purchases", new ReportingV1Json.JsonNumber("0"),
                "metric.sales", new ReportingV1Json.JsonNumber("0"),
                "metric.unitsSold", new ReportingV1Json.JsonNumber("0")));
        assertEquals("ADS_REPORT_V1_CAMPAIGN_DUPLICATE_GRAIN", assertThrows(AdsQueryError.class,
                () -> CampaignReportingV1Parser.mapRows(rows, "2026-11-01", "2026-11-07")).getCode());

        Map<String, Object> staged = CampaignReportingV1Parser.toStaging(
                CampaignReportingV1Parser.mapRows(rows.subList(0, 1), "2026-11-01", "2026-11-07"));
        assertEquals("USD", staged.get("currencyCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stagedFact = ((List<Map<String, Object>>) staged.get("facts")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> dims = (Map<String, Object>) stagedFact.get("dimensions");
        assertEquals("123456789", dims.get("campaignId"));
        assertEquals("12.500000", stagedFact.get("spend"));
    }

    @Test
    void gzipJsonPreservesNumberLexemesAndRejectsDuplicateKeys() {
        String json = "{\"metric\":{\"cost\":12.500000,\"units\":30},\"ok\":true}";
        ReportingV1Json.Parsed parsed = ReportingV1Json.parse(gzip(json));
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) parsed.value;
        @SuppressWarnings("unchecked")
        Map<String, Object> metric = (Map<String, Object>) value.get("metric");
        assertEquals("12.500000", ((ReportingV1Json.JsonNumber) metric.get("cost")).source);
        assertEquals("30", ((ReportingV1Json.JsonNumber) metric.get("units")).source);
        assertEquals(Boolean.TRUE, value.get("ok"));
        assertTrue(parsed.uncompressedBytes > 0);

        assertEquals("ADS_REPORT_V1_JSON_INVALID", assertThrows(AdsQueryError.class,
                () -> ReportingV1Json.parse(gzip("{\"a\":1,\"a\":2}"))).getCode());
        assertEquals("ADS_REPORT_V1_GZIP_INVALID_OR_LIMIT", assertThrows(AdsQueryError.class,
                () -> ReportingV1Json.parse("not gzip".getBytes(StandardCharsets.UTF_8))).getCode());
    }

    @Test
    void spRowMappingResolvesAliasesAndMergesDuplicateGrains() {
        Map<String, Object> row1 = row("date", "2026-11-01", "campaignId", "111",
                "impressions", "100", "clicks", "10", "cost", "1.500000",
                "purchasesClicks", "2", "salesClicks", "20.000000", "unitsSoldClicks", "3");
        Map<String, Object> row2 = row("date", "2026-11-01", "campaignId", "111",
                "impressions", "50", "clicks", "5", "spend", "2.250000",
                "purchases7d", "1", "sales7d", "10.000000", "unitsSold", "2");
        String json = "[" + AdsCanonical.json(row1) + "," + AdsCanonical.json(row2) + "]";
        SpReportParser.Result result = SpReportParser.parseFile("campaign", gzip(json),
                "2026-11-01", "2026-11-07", "USD", SpReportCatalog.defaultLimits());
        assertEquals(2, result.rowCount);
        assertEquals(1, result.mergedRows);
        assertEquals(1, result.facts.size());
        assertEquals("150", result.facts.get(0).get("impressions"));
        assertEquals("15", result.facts.get(0).get("clicks"));
        assertEquals("3.75", result.facts.get(0).get("spend"));
        assertEquals("30", result.facts.get(0).get("sales"));
        assertEquals("5", result.facts.get(0).get("units"));
    }

    @Test
    void campaignV3RequestAndStagingUseFrozenColumns() {
        CampaignReportContract.Spec spec = new CampaignReportContract.Spec(
                "2026-11-01", "2026-11-03", "USD", "America/Los_Angeles", 7);
        Map<String, Object> request = CampaignReportContract.request(spec);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("configuration");
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) config.get("columns");
        assertTrue(columns.contains("purchases7d"));
        assertTrue(columns.contains("sales7d"));
        assertEquals("spCampaigns", config.get("reportTypeId"));

        String json = "[{\"date\":\"2026-11-01\",\"campaignId\":\"123\",\"campaignBudgetCurrencyCode\":\"USD\","
                + "\"impressions\":100,\"clicks\":10,\"cost\":\"1.500000\","
                + "\"purchases7d\":2,\"sales7d\":\"20.000000\",\"unitsSoldClicks7d\":3}]";
        List<Map<String, Object>> facts = CampaignReportContract.parse(gzip(json), spec);
        assertEquals(1, facts.size());
        assertEquals("123", ((Map<?, ?>) facts.get(0).get("dimensions")).get("campaignId"));
        assertEquals("2", facts.get(0).get("orders"));
        assertEquals("20.000000", facts.get(0).get("sales"));
    }
}

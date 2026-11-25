package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存风险（跌价准备）规则（对应源文件 inventory-risk-rules.ts）。
 * 移植了：政策/依据的严格解析、销售窗口与快照日期一致性校验、
 * stagnant 滞销判定、proposedProvision/provisionDelta 计算、
 * NEEDS_DATA/COMPLETE 状态、增减凭证行生成。
 * 金额一律用 BigInteger 微单位（1e-6）精确计算。
 */
public final class InventoryRiskRules {

    private static final BigInteger MICROS = BigInteger.valueOf(1_000_000L);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private InventoryRiskRules() {}

    /** InventoryRiskPolicy。 */
    public static final class Policy {
        private final String reference;
        private final String currency;
        private final int windowDays;
        private final String maximumSoldQty;
        private final String expenseAccount;
        private final String allowanceAccount;

        public Policy(String reference, String currency, int windowDays, String maximumSoldQty,
                      String expenseAccount, String allowanceAccount) {
            this.reference = reference;
            this.currency = currency;
            this.windowDays = windowDays;
            this.maximumSoldQty = maximumSoldQty;
            this.expenseAccount = expenseAccount;
            this.allowanceAccount = allowanceAccount;
        }

        public String getReference() { return reference; }
        public String getCurrency() { return currency; }
        public int getWindowDays() { return windowDays; }
        public String getMaximumSoldQty() { return maximumSoldQty; }
        public String getExpenseAccount() { return expenseAccount; }
        public String getAllowanceAccount() { return allowanceAccount; }
    }

    /** InventoryRiskEvidence。 */
    public static final class Evidence {
        private final String productRecordId;
        private final String salesFrom;
        private final String salesTo;
        private final String soldQty;
        private final String salesCoverage;
        private final String salesReference;
        private final String carryingAmount;
        private final String netRealizableAmount;
        private final String existingProvision;
        private final String valuationReference;

        public Evidence(String productRecordId, String salesFrom, String salesTo, String soldQty,
                        String salesCoverage, String salesReference, String carryingAmount,
                        String netRealizableAmount, String existingProvision, String valuationReference) {
            this.productRecordId = productRecordId;
            this.salesFrom = salesFrom;
            this.salesTo = salesTo;
            this.soldQty = soldQty;
            this.salesCoverage = salesCoverage;
            this.salesReference = salesReference;
            this.carryingAmount = carryingAmount;
            this.netRealizableAmount = netRealizableAmount;
            this.existingProvision = existingProvision;
            this.valuationReference = valuationReference;
        }

        public String getProductRecordId() { return productRecordId; }
        public String getSalesFrom() { return salesFrom; }
        public String getSalesTo() { return salesTo; }
        public String getSoldQty() { return soldQty; }
        public String getSalesCoverage() { return salesCoverage; }
        public String getCarryingAmount() { return carryingAmount; }
        public String getNetRealizableAmount() { return netRealizableAmount; }
        public String getExistingProvision() { return existingProvision; }
    }

    /** InventoryRiskStock（快照行）。 */
    public static final class Stock {
        private final String productRecordId;
        private final String sku;
        private final String quantity;
        private final String dataStatus;
        private final Object estimate;

        public Stock(String productRecordId, String sku, String quantity, String dataStatus, Object estimate) {
            this.productRecordId = productRecordId;
            this.sku = sku;
            this.quantity = quantity;
            this.dataStatus = dataStatus;
            this.estimate = estimate;
        }

        public String getProductRecordId() { return productRecordId; }
        public String getSku() { return sku; }
        public String getQuantity() { return quantity; }
        public String getDataStatus() { return dataStatus; }
        public Object getEstimate() { return estimate; }
    }

    /** 评估明细项。 */
    public static final class Item {
        private final String productRecordId;
        private final String sku;
        private final String quantity;
        private final Object agingBasis;
        private final Boolean stagnant;
        private final String proposedProvision;
        private final String provisionDelta;
        private final String status;

        public Item(String productRecordId, String sku, String quantity, Object agingBasis,
                    Boolean stagnant, String proposedProvision, String provisionDelta, String status) {
            this.productRecordId = productRecordId;
            this.sku = sku;
            this.quantity = quantity;
            this.agingBasis = agingBasis;
            this.stagnant = stagnant;
            this.proposedProvision = proposedProvision;
            this.provisionDelta = provisionDelta;
            this.status = status;
        }

        public String getProductRecordId() { return productRecordId; }
        public String getQuantity() { return quantity; }
        public Boolean getStagnant() { return stagnant; }
        public String getProposedProvision() { return proposedProvision; }
        public String getProvisionDelta() { return provisionDelta; }
        public String getStatus() { return status; }
    }

    /** 评估结果。 */
    public static final class Assessment {
        private final String snapshotDate;
        private final String windowFrom;
        private final String currency;
        private final String status;
        private final List<Item> items;
        private final String provisionDelta;
        private final String provisionIncrease;
        private final String provisionRelease;
        private final Map<String, Object> voucher;

        public Assessment(String snapshotDate, String windowFrom, String currency, String status,
                          List<Item> items, String provisionDelta, String provisionIncrease,
                          String provisionRelease, Map<String, Object> voucher) {
            this.snapshotDate = snapshotDate;
            this.windowFrom = windowFrom;
            this.currency = currency;
            this.status = status;
            this.items = items;
            this.provisionDelta = provisionDelta;
            this.provisionIncrease = provisionIncrease;
            this.provisionRelease = provisionRelease;
            this.voucher = voucher;
        }

        public String getStatus() { return status; }
        public List<Item> getItems() { return items; }
        public String getProvisionDelta() { return provisionDelta; }
        public String getProvisionIncrease() { return provisionIncrease; }
        public String getProvisionRelease() { return provisionRelease; }
        public Map<String, Object> getVoucher() { return voucher; }
    }

    private static String quantity(Object value, String label) {
        if (!(value instanceof String) || !((String) value).matches("(?:0|[1-9]\\d{0,23})")) {
            throw new WmsError(label + "须为非负整数字符串。", 400);
        }
        return (String) value;
    }

    private static String validFinanceDate(Object value, String label) {
        if (!(value instanceof String)) throw new WmsError(label + "无效。", 400);
        String text = (String) value;
        try {
            LocalDate parsed = LocalDate.parse(text, DATE);
            if (!parsed.format(DATE).equals(text)) throw new WmsError(label + "无效。", 400);
        } catch (DateTimeParseException e) {
            throw new WmsError(label + "无效。", 400);
        }
        return text;
    }

    /** amountUnits：非负六位小数金额字符串 → 微单位整数。 */
    public static BigInteger amountUnits(String value) {
        if (value == null || !value.matches("(?:0|[1-9]\\d{0,23})(?:\\.\\d{1,6})?")) {
            throw new WmsError("金额须为最多六位小数的非负十进制字符串。", 400);
        }
        String[] parts = value.split("\\.");
        String whole = parts[0];
        String fraction = parts.length > 1 ? parts[1] : "";
        while (fraction.length() < 6) fraction += "0";
        return new BigInteger(whole).multiply(MICROS).add(new BigInteger(fraction));
    }

    /** unitsAmount：微单位整数 → 定点字符串（支持负数）。 */
    public static String unitsAmount(BigInteger micros) {
        String sign = micros.signum() < 0 ? "-" : "";
        BigInteger abs = micros.abs();
        return sign + abs.divide(MICROS) + "." + String.format("%06d", abs.remainder(MICROS));
    }

    private static BigInteger signed(String raw) {
        return raw.startsWith("-") ? amountUnits(raw.substring(1)).negate() : amountUnits(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String message) {
        if (!(raw instanceof Map)) throw new WmsError(message, 400);
        return (Map<String, Object>) raw;
    }

    /** parseInventoryRiskPolicy。 */
    public static Policy parseInventoryRiskPolicy(Object raw) {
        Map<String, Object> body = object(raw, "库存风险政策无效。");
        WmsRules.strictKeys(body, "reference", "currency", "windowDays", "maximumSoldQty",
                "expenseAccount", "allowanceAccount");
        if (!(body.get("windowDays") instanceof Number)) throw new WmsError("销售窗口须为 1–366 天。", 400);
        long windowDays = ((Number) body.get("windowDays")).longValue();
        if (windowDays < 1 || windowDays > 366) throw new WmsError("销售窗口须为 1–366 天。", 400);
        String currency = WmsRules.requireText(body.get("currency"), "评估币种", 3);
        if (!currency.matches("[A-Z]{3}")) throw new WmsError("评估币种须为三位大写编码。", 400);
        String expenseAccount = WmsRules.requireText(body.get("expenseAccount"), "目标端减值损益科目");
        String allowanceAccount = WmsRules.requireText(body.get("allowanceAccount"), "目标端跌价准备科目");
        if (expenseAccount.equals(allowanceAccount)) throw new WmsError("减值损益科目与准备科目不能相同。", 400);
        return new Policy(WmsRules.requireText(body.get("reference"), "目标端库存风险政策依据", 1000),
                currency, (int) windowDays, quantity(body.get("maximumSoldQty"), "滞销阈值"),
                expenseAccount, allowanceAccount);
    }

    /** parseInventoryRiskEvidence。 */
    public static List<Evidence> parseInventoryRiskEvidence(Object raw) {
        if (!(raw instanceof List)) throw new WmsError("库存风险依据须有 1–500 个商品。", 400);
        List<Object> rows = (List<Object>) raw;
        if (rows.isEmpty() || rows.size() > 500) throw new WmsError("库存风险依据须有 1–500 个商品。", 400);
        Set<String> seen = new HashSet<>();
        List<Evidence> out = new ArrayList<>();
        for (Object value : rows) {
            Map<String, Object> row = object(value, "库存风险依据行无效。");
            WmsRules.strictKeys(row, "productRecordId", "salesFrom", "salesTo", "soldQty",
                    "salesCoverage", "salesReference", "carryingAmount", "netRealizableAmount",
                    "existingProvision", "valuationReference");
            String productRecordId = WmsRules.requireText(row.get("productRecordId"), "商品主档 ID");
            if (!seen.add(productRecordId)) throw new WmsError("商品依据重复。", 400);
            String coverage = String.valueOf(row.get("salesCoverage"));
            if (!coverage.equals("COMPLETE") && !coverage.equals("PARTIAL") && !coverage.equals("MISSING")) {
                throw new WmsError("须明确销售窗口覆盖状态。", 400);
            }
            String soldQty = row.get("soldQty") == null ? null : quantity(row.get("soldQty"), "窗口销售数量");
            if (coverage.equals("COMPLETE") && soldQty == null) {
                throw new WmsError("完整窗口须提供明确销售数量，零须明确提供。", 400);
            }
            out.add(new Evidence(productRecordId,
                    validFinanceDate(row.get("salesFrom"), "销售窗口起始"),
                    validFinanceDate(row.get("salesTo"), "销售窗口结束（不含）"),
                    soldQty, coverage,
                    WmsRules.requireText(row.get("salesReference"), "销售窗口来源依据", 2000),
                    nullableAmount(row.get("carryingAmount")),
                    nullableAmount(row.get("netRealizableAmount")),
                    nullableAmount(row.get("existingProvision")),
                    WmsRules.requireText(row.get("valuationReference"), "目标估值/已有准备依据", 2000)));
        }
        return out;
    }

    private static String nullableAmount(Object value) {
        if (value == null) return null;
        return unitsAmount(amountUnits(WmsRules.requireText(value, "核算依据金额")));
    }

    /** assessInventoryRisk。 */
    public static Assessment assessInventoryRisk(List<Stock> stocks, String snapshotDate,
                                                 Policy policy, List<Evidence> evidence) {
        validFinanceDate(snapshotDate, "快照日期");
        LocalDate end = LocalDate.parse(snapshotDate, DATE);
        LocalDate start = end.minusDays(policy.getWindowDays());
        String startText = start.format(DATE);
        Map<String, Evidence> byProduct = new LinkedHashMap<>();
        for (Evidence row : evidence) byProduct.put(row.getProductRecordId(), row);
        if (stocks.size() != evidence.size()) {
            throw new WmsError("评估依据须恰好覆盖快照内全部商品；单次最多 500 个。", 422);
        }
        Set<String> stockIds = new HashSet<>();
        for (Stock stock : stocks) {
            if (!byProduct.containsKey(stock.getProductRecordId())) {
                throw new WmsError("评估依据须恰好覆盖快照内全部商品；单次最多 500 个。", 422);
            }
            if (!stockIds.add(stock.getProductRecordId())) throw new WmsError("快照商品重复，不能核算。", 422);
        }
        List<Item> items = new ArrayList<>();
        for (Stock stock : stocks) {
            quantity(stock.getQuantity(), "快照数量");
            Evidence source = byProduct.get(stock.getProductRecordId());
            if (!source.getSalesFrom().equals(startText) || !source.getSalesTo().equals(snapshotDate)) {
                throw new WmsError("销售窗口须与快照日期及政策天数严格一致，结束日期不含。", 422);
            }
            boolean salesComplete = source.getSalesCoverage().equals("COMPLETE") && source.getSoldQty() != null;
            boolean valuationComplete = source.getCarryingAmount() != null
                    && source.getNetRealizableAmount() != null && source.getExistingProvision() != null;
            BigInteger proposed = null;
            if (valuationComplete) {
                BigInteger excess = amountUnits(source.getCarryingAmount())
                        .subtract(amountUnits(source.getNetRealizableAmount()));
                proposed = excess.signum() > 0 ? excess : BigInteger.ZERO;
            }
            String proposedText = proposed == null ? null : unitsAmount(proposed);
            String deltaText = null;
            if (proposed != null) {
                deltaText = unitsAmount(proposed.subtract(amountUnits(source.getExistingProvision())));
            }
            Boolean stagnant = null;
            if (salesComplete) {
                BigInteger q = new BigInteger(stock.getQuantity());
                stagnant = q.signum() > 0 && new BigInteger(source.getSoldQty())
                        .compareTo(new BigInteger(policy.getMaximumSoldQty())) <= 0;
            }
            String status = salesComplete && valuationComplete && stock.getDataStatus().equals("COMPLETE")
                    ? "COMPLETE" : "NEEDS_DATA";
            items.add(new Item(stock.getProductRecordId(), stock.getSku(), stock.getQuantity(),
                    stock.getEstimate(), stagnant, proposedText, deltaText, status));
        }
        boolean complete = true;
        for (Item item : items) if (!item.getStatus().equals("COMPLETE")) { complete = false; break; }
        String totalDelta = null;
        String increase = null;
        String release = null;
        if (complete) {
            BigInteger total = BigInteger.ZERO, inc = BigInteger.ZERO, rel = BigInteger.ZERO;
            for (Item item : items) {
                BigInteger d = signed(item.getProvisionDelta());
                total = total.add(d);
                if (d.signum() > 0) inc = inc.add(d);
                if (d.signum() < 0) rel = rel.subtract(d);
            }
            totalDelta = unitsAmount(total);
            increase = unitsAmount(inc);
            release = unitsAmount(rel);
        }
        Map<String, Object> voucher = null;
        if (increase != null && release != null) {
            List<Map<String, Object>> lines = new ArrayList<>();
            addPair(lines, increase, policy.getExpenseAccount(), policy.getAllowanceAccount());
            addPair(lines, release, policy.getAllowanceAccount(), policy.getExpenseAccount());
            voucher = new LinkedHashMap<>();
            voucher.put("currency", policy.getCurrency());
            voucher.put("date", snapshotDate);
            voucher.put("lines", lines);
            voucher.put("postingExecuted", false);
        }
        return new Assessment(snapshotDate, startText, policy.getCurrency(),
                complete ? "COMPLETE" : "NEEDS_DATA", items, totalDelta, increase, release, voucher);
    }

    private static void addPair(List<Map<String, Object>> lines, String amount, String debit, String credit) {
        if (amountUnits(amount).signum() == 0) return;
        Map<String, Object> debitLine = new LinkedHashMap<>();
        debitLine.put("account", debit);
        debitLine.put("debit", amount);
        debitLine.put("credit", "0.000000");
        lines.add(debitLine);
        Map<String, Object> creditLine = new LinkedHashMap<>();
        creditLine.put("account", credit);
        creditLine.put("debit", "0.000000");
        creditLine.put("credit", amount);
        lines.add(creditLine);
    }
}

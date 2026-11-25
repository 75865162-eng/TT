package com.wimoor.erp.wms.enhance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 海外费用分摊规则（对应源文件 overseas-charge-rules.ts）。
 * 移植了：分摊方式校验（QUANTITY/WEIGHT/VOLUME/VALUE）、逐明细依据解析、
 * 计费重 max(实际重, 体积重) 口径、整数微单位最大余额法精确分摊、
 * 预估费用对比差额。源端依赖 fba-local/cost-allocation 与 finance/open-item-rules，
 * 本类把分摊核心算法内联为领域层纯函数；装箱层级（hierarchy）以原始箱单透传，
 * 不做跨模块的 lineShares 覆盖，以免引入外部 Prisma 表依赖。
 */
public final class OverseasChargeRules {

    private static final BigInteger SCALE = BigInteger.valueOf(1_000_000L);
    private static final List<String> METHODS = List.of("QUANTITY", "WEIGHT", "VOLUME", "VALUE");

    /** 计划明细。 */
    public static final class PlanLine {
        private final String id;
        private final int quantity;

        public PlanLine(String id, int quantity) {
            this.id = id;
            this.quantity = quantity;
        }

        public String getId() { return id; }
        public int getQuantity() { return quantity; }
    }

    /** 箱单明细。 */
    public static final class BoxItem {
        private final String transferLineId;
        private final int quantity;

        public BoxItem(String transferLineId, int quantity) {
            this.transferLineId = transferLineId;
            this.quantity = quantity;
        }

        public String getTransferLineId() { return transferLineId; }
        public int getQuantity() { return quantity; }
    }

    /** 箱。 */
    public static final class Box {
        private final String number;
        private final List<BoxItem> items;

        public Box(String number, List<BoxItem> items) {
            this.number = number;
            this.items = items;
        }

        public String getNumber() { return number; }
        public List<BoxItem> getItems() { return items; }
    }

    /** 预估费用。 */
    public static final class Estimate {
        private final String currency;
        private final String estimatedFee;

        public Estimate(String currency, String estimatedFee) {
            this.currency = currency;
            this.estimatedFee = estimatedFee;
        }

        public String getCurrency() { return currency; }
        public String getEstimatedFee() { return estimatedFee; }
    }

    private OverseasChargeRules() {}

    private static BigInteger decimal(Object raw, String label, boolean positive) {
        if (!(raw instanceof String) || !((String) raw).trim().matches("\\d{1,12}(?:\\.\\d{1,6})?")) {
            throw new WmsError.Input(label + "须为非负十进制字符串，最多六位小数。");
        }
        String[] parts = ((String) raw).trim().split("\\.", -1);
        String fraction = parts.length > 1 ? parts[1] : "";
        while (fraction.length() < 6) fraction += "0";
        BigInteger value = new BigInteger(parts[0]).multiply(SCALE).add(new BigInteger(fraction));
        if (positive && value.signum() == 0) throw new WmsError.Input(label + "须大于零。");
        return value;
    }

    private static String format(BigInteger value) {
        return value.divide(SCALE) + "." + String.format("%06d", value.remainder(SCALE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String message) {
        if (!(raw instanceof Map)) throw new WmsError.Input(message);
        return (Map<String, Object>) raw;
    }

    /** 分摊依据行。 */
    private static final class BasisRow {
        private final String lineId;
        private final int quantity;
        private final BigInteger unit;
        private final Map<String, String> evidence;

        private BasisRow(String lineId, int quantity, BigInteger unit, Map<String, String> evidence) {
            this.lineId = lineId;
            this.quantity = quantity;
            this.unit = unit;
            this.evidence = evidence;
        }
    }

    /** overseasChargeAllocation。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> overseasChargeAllocation(Map<String, Object> body,
                                                               List<PlanLine> lines,
                                                               List<Box> boxes,
                                                               Estimate estimate) {
        String method = WmsRules.requireText(body.get("allocationMethod"), "海外费用分摊方式");
        if (!METHODS.contains(method)) throw new WmsError("海外费用分摊方式无效。", 400);
        Object rawBasis = body.get("basis");
        if (!(rawBasis instanceof List)) throw new WmsError("须逐明细提供分摊依据。", 400);

        String[] allowedKeys;
        if (method.equals("QUANTITY")) allowedKeys = new String[]{"lineId"};
        else if (method.equals("WEIGHT")) allowedKeys = new String[]{"lineId", "unitWeightKg", "unitDimensionalWeightKg"};
        else if (method.equals("VOLUME")) allowedKeys = new String[]{"lineId", "unitVolumeM3"};
        else allowedKeys = new String[]{"lineId", "unitValue"};

        Map<String, PlanLine> planned = new LinkedHashMap<>();
        for (PlanLine line : lines) planned.put(line.getId(), line);
        List<BasisRow> bases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object raw : (List<Object>) rawBasis) {
            Map<String, Object> row = object(raw, "分摊依据须为对象。");
            WmsRules.strictKeys(row, allowedKeys);
            String lineId = WmsRules.requireText(row.get("lineId"), "计划明细 ID");
            PlanLine line = planned.get(lineId);
            if (line == null || !seen.add(lineId)) {
                throw new WmsError.Input("分摊明细重复或不属于当前计划。");
            }
            BigInteger unit = SCALE;
            Map<String, String> evidence = new LinkedHashMap<>();
            if (method.equals("WEIGHT")) {
                BigInteger actual = decimal(row.get("unitWeightKg"), "单位实际重（kg）", false);
                BigInteger dimensional = decimal(row.get("unitDimensionalWeightKg"), "单位体积重（kg）", false);
                unit = actual.max(dimensional);
                if (unit.signum() == 0) throw new WmsError.Input("计费重依据缺失或为零，请维护重量。");
                evidence.put("unitWeightKg", format(actual));
                evidence.put("unitDimensionalWeightKg", format(dimensional));
            } else if (!method.equals("QUANTITY")) {
                String key = method.equals("VOLUME") ? "unitVolumeM3" : "unitValue";
                String label = method.equals("VOLUME") ? "单位体积（m³）" : "单位货值（同费用币种）";
                unit = decimal(row.get(key), label, true);
                evidence.put(key, format(unit));
            }
            bases.add(new BasisRow(lineId, line.getQuantity(), unit, evidence));
        }
        bases.sort(Comparator.comparing(row -> row.lineId));
        if (bases.size() != lines.size()) throw new WmsError.Input("分摊依据须覆盖当前全部计划明细。");
        BigInteger total = BigInteger.ZERO;
        for (BasisRow row : bases) total = total.add(row.unit.multiply(BigInteger.valueOf(row.quantity)));
        if (total.signum() == 0) throw new WmsError.Input("分摊依据合计不能为零。");

        String reference = WmsRules.requireText(body.get("reference"), "费用来源单号");
        String currency = WmsRules.requireText(body.get("currency"), "币种", 3);
        if (!currency.matches("[A-Z]{3}")) throw new WmsError.Input("币种须为三个大写字母。");
        Object stageRaw = body.get("stage");
        String stage = stageRaw == null ? "ACTUAL" : String.valueOf(stageRaw);
        if (!stage.equals("ESTIMATE") && !stage.equals("ACTUAL")) throw new WmsError.Input("费用阶段须为预估或实际账单。");
        BigInteger amount = decimal(body.get("amount"), "费用金额", stage.equals("ESTIMATE"));
        WmsRules.requireText(body.get("manifestId"), "海外装箱资料");

        // 整数微单位最大余额法：先按 quotient 分配，再把余数按从大到小（同余按 lineId）补齐。
        List<BigInteger[]> shares = new ArrayList<>(); // {allocated, remainder}
        BigInteger allocatedSum = BigInteger.ZERO;
        for (BasisRow row : bases) {
            BigInteger weight = row.unit.multiply(BigInteger.valueOf(row.quantity));
            BigInteger[] qr = amount.multiply(weight).divideAndRemainder(total);
            shares.add(qr);
            allocatedSum = allocatedSum.add(qr[0]);
        }
        BigInteger remaining = amount.subtract(allocatedSum);
        List<Integer> rank = new ArrayList<>();
        for (int i = 0; i < shares.size(); i++) rank.add(i);
        rank.sort((a, b) -> {
            int cmp = shares.get(a)[1].compareTo(shares.get(b)[1]);
            if (cmp == 0) return bases.get(a).lineId.compareTo(bases.get(b).lineId);
            return -cmp; // 余数大的优先
        });
        for (int i = 0; i < remaining.intValue(); i++) {
            BigInteger[] qr = shares.get(rank.get(i));
            qr[0] = qr[0].add(BigInteger.ONE);
        }

        List<Map<String, Object>> outLines = new ArrayList<>();
        for (int i = 0; i < bases.size(); i++) {
            BasisRow row = bases.get(i);
            BigInteger allocated = shares.get(i)[0];
            BigInteger qty = BigInteger.valueOf(row.quantity);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineId", row.lineId);
            line.put("quantity", row.quantity);
            line.putAll(row.evidence);
            line.put("basis", format(row.unit.multiply(qty)));
            line.put("allocatedAmount", format(allocated));
            line.put("unitCost", format(allocated.divide(qty)));
            line.put("unitCostRemainder", format(allocated.remainder(qty)));
            outLines.add(line);
        }

        Map<String, Object> allocation = new LinkedHashMap<>();
        allocation.put("version", 1);
        allocation.put("reference", reference);
        allocation.put("stage", stage);
        allocation.put("currency", currency);
        allocation.put("amount", format(amount));
        allocation.put("method", method.equals("WEIGHT") ? "CHARGEABLE_WEIGHT" : method);
        // 装箱层级以原始箱单透传（领域层抽象，不依赖跨模块 Prisma 表）。
        List<Map<String, Object>> boxViews = new ArrayList<>();
        for (Box box : boxes) {
            Map<String, Object> boxView = new LinkedHashMap<>();
            boxView.put("number", box.getNumber());
            List<Map<String, Object>> items = new ArrayList<>();
            for (BoxItem item : box.getItems()) {
                Map<String, Object> itemView = new LinkedHashMap<>();
                itemView.put("lineId", item.getTransferLineId());
                itemView.put("quantity", item.getQuantity());
                items.add(itemView);
            }
            boxView.put("items", items);
            boxViews.add(boxView);
        }
        Map<String, Object> hierarchy = new LinkedHashMap<>();
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("shipmentId", WmsRules.requireText(body.get("manifestId"), "海外装箱资料"));
        shipment.put("boxes", boxViews);
        hierarchy.put("shipments", List.of(shipment));
        allocation.put("hierarchy", hierarchy);
        allocation.put("lines", outLines);

        String comparable = null;
        String estimateCurrency = estimate == null ? null : estimate.getCurrency();
        String estimateFee = estimate == null ? null : estimate.getEstimatedFee();
        if (estimateCurrency != null && estimateCurrency.equals(currency) && estimateFee != null) {
            comparable = new BigDecimal(estimateFee).setScale(6).toPlainString();
        }
        Map<String, Object> estimateView = new LinkedHashMap<>();
        estimateView.put("currency", estimateCurrency);
        estimateView.put("amount", estimateFee);
        estimateView.put("comparable", comparable != null);
        estimateView.put("difference", comparable == null ? null
                : InventoryRiskRules.unitsAmount(amount.subtract(
                        new BigInteger(comparable.replace(".", "")))));

        Map<String, Object> result = new LinkedHashMap<>(allocation);
        result.put("allocationMethod", method);
        result.put("estimate", estimateView);
        result.put("postingExecuted", false);
        result.put("inventoryCostUpdated", false);
        return result;
    }
}

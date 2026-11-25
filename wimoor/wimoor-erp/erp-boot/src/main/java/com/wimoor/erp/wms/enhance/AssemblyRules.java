package com.wimoor.erp.wms.enhance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组装纯规则（对应源文件 assembly-rules.ts）。
 * 移植了：BOM/组装草图的严格解析、BOM 展开（人工调整优先）、FIFO 组件分配
 * （任一组件不足即整体拒绝）、缺料文案、1e-6 定点成本换算
 * （scaleCost/formatCost/lineAmount/rolledUnitCost/totalComponentCost）。
 * 组装单草稿不预占库存；只有完成命令才扣组件、产成品、滚成本。
 */
public final class AssemblyRules {

    /** ASSEMBLY_LIMITS。 */
    public static final int MAX_BOM_LINES = 50;
    public static final int MAX_PLANNED_QTY = 100_000;
    /** ASSEMBLY_SCALE = 10^6。 */
    public static final BigInteger ASSEMBLY_SCALE = BigInteger.valueOf(1_000_000L);

    private AssemblyRules() {}

    /** BomLineDraft。 */
    public static final class BomLineDraft {
        private final String componentProductRecordId;
        private final int quantityPer;
        private final String notes;

        public BomLineDraft(String componentProductRecordId, int quantityPer, String notes) {
            this.componentProductRecordId = componentProductRecordId;
            this.quantityPer = quantityPer;
            this.notes = notes;
        }

        public String getComponentProductRecordId() { return componentProductRecordId; }
        public int getQuantityPer() { return quantityPer; }
        public String getNotes() { return notes; }
    }

    /** BomDraft。 */
    public static final class BomDraft {
        private final String productRecordId;
        private final String version;
        private final String notes;
        private final List<BomLineDraft> lines;

        public BomDraft(String productRecordId, String version, String notes, List<BomLineDraft> lines) {
            this.productRecordId = productRecordId;
            this.version = version;
            this.notes = notes;
            this.lines = lines;
        }

        public String getProductRecordId() { return productRecordId; }
        public String getVersion() { return version; }
        public String getNotes() { return notes; }
        public List<BomLineDraft> getLines() { return lines; }
    }

    /** AssemblyLineOverride。 */
    public static final class AssemblyLineOverride {
        private final String componentProductRecordId;
        private final int quantity;

        public AssemblyLineOverride(String componentProductRecordId, int quantity) {
            this.componentProductRecordId = componentProductRecordId;
            this.quantity = quantity;
        }

        public String getComponentProductRecordId() { return componentProductRecordId; }
        public int getQuantity() { return quantity; }
    }

    /** AssemblyDraft。 */
    public static final class AssemblyDraft {
        private final String number;
        private final String productRecordId;
        private final String bomId;
        private final String warehouseId;
        private final String locationId;
        private final String lotId;
        private final int plannedQty;
        private final String currency;
        private final String notes;
        private final String idempotencyKey;
        private final List<AssemblyLineOverride> overrides;

        public AssemblyDraft(String number, String productRecordId, String bomId, String warehouseId,
                             String locationId, String lotId, int plannedQty, String currency, String notes,
                             String idempotencyKey, List<AssemblyLineOverride> overrides) {
            this.number = number;
            this.productRecordId = productRecordId;
            this.bomId = bomId;
            this.warehouseId = warehouseId;
            this.locationId = locationId;
            this.lotId = lotId;
            this.plannedQty = plannedQty;
            this.currency = currency;
            this.notes = notes;
            this.idempotencyKey = idempotencyKey;
            this.overrides = overrides;
        }

        public String getNumber() { return number; }
        public String getProductRecordId() { return productRecordId; }
        public String getBomId() { return bomId; }
        public String getWarehouseId() { return warehouseId; }
        public String getLocationId() { return locationId; }
        public String getLotId() { return lotId; }
        public int getPlannedQty() { return plannedQty; }
        public String getCurrency() { return currency; }
        public String getNotes() { return notes; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public List<AssemblyLineOverride> getOverrides() { return overrides; }
    }

    /** AllocationCandidate。 */
    public static final class AllocationCandidate {
        private final String balanceId;
        private final String productRecordId;
        private final String lotId;
        private final String locationId;
        private final String warehouseId;
        private final int available;
        private final Date createdAt;

        public AllocationCandidate(String balanceId, String productRecordId, String lotId, String locationId,
                                   String warehouseId, int available, Date createdAt) {
            this.balanceId = balanceId;
            this.productRecordId = productRecordId;
            this.lotId = lotId;
            this.locationId = locationId;
            this.warehouseId = warehouseId;
            this.available = available;
            this.createdAt = createdAt;
        }

        public String getBalanceId() { return balanceId; }
        public String getProductRecordId() { return productRecordId; }
        public String getLotId() { return lotId; }
        public String getLocationId() { return locationId; }
        public String getWarehouseId() { return warehouseId; }
        public int getAvailable() { return available; }
        public Date getCreatedAt() { return createdAt; }
    }

    /** AllocatedLine。 */
    public static final class AllocatedLine {
        private final String balanceId;
        private final String componentProductRecordId;
        private final String lotId;
        private final String locationId;
        private final String warehouseId;
        private final int plannedQty;

        public AllocatedLine(String balanceId, String componentProductRecordId, String lotId,
                             String locationId, String warehouseId, int plannedQty) {
            this.balanceId = balanceId;
            this.componentProductRecordId = componentProductRecordId;
            this.lotId = lotId;
            this.locationId = locationId;
            this.warehouseId = warehouseId;
            this.plannedQty = plannedQty;
        }

        public String getBalanceId() { return balanceId; }
        public String getComponentProductRecordId() { return componentProductRecordId; }
        public String getLotId() { return lotId; }
        public String getLocationId() { return locationId; }
        public String getWarehouseId() { return warehouseId; }
        public int getPlannedQty() { return plannedQty; }
    }

    /** 缺料项。 */
    public static final class Shortage {
        private final String componentProductRecordId;
        private final int required;
        private final int available;

        public Shortage(String componentProductRecordId, int required, int available) {
            this.componentProductRecordId = componentProductRecordId;
            this.required = required;
            this.available = available;
        }

        public String getComponentProductRecordId() { return componentProductRecordId; }
        public int getRequired() { return required; }
        public int getAvailable() { return available; }
    }

    /** allocateComponents 的返回。 */
    public static final class AllocationResult {
        private final List<AllocatedLine> lines;
        private final List<Shortage> shortages;

        public AllocationResult(List<AllocatedLine> lines, List<Shortage> shortages) {
            this.lines = lines;
            this.shortages = shortages;
        }

        public List<AllocatedLine> getLines() { return lines; }
        public List<Shortage> getShortages() { return shortages; }
    }

    /** 展开需求项。 */
    public static final class Demand {
        private final String componentProductRecordId;
        private final int quantity;

        public Demand(String componentProductRecordId, int quantity) {
            this.componentProductRecordId = componentProductRecordId;
            this.quantity = quantity;
        }

        public String getComponentProductRecordId() { return componentProductRecordId; }
        public int getQuantity() { return quantity; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String message) {
        if (!(raw instanceof Map)) throw new WmsError(message, 400);
        return (Map<String, Object>) raw;
    }

    /** parseBomDraft。 */
    public static BomDraft parseBomDraft(Object raw) {
        Map<String, Object> value = object(raw, "BOM 内容无效。");
        WmsRules.strictKeys(value, "productRecordId", "version", "notes", "lines");
        String productRecordId = WmsRules.requireText(value.get("productRecordId"), "成品商品", 100);
        String version = value.get("version") == null ? "V1"
                : WmsRules.requireText(value.get("version"), "版本", 40);
        String notes = value.get("notes") == null ? ""
                : WmsRules.requireText(value.get("notes"), "备注", 500);
        Object rawLines = value.get("lines");
        if (!(rawLines instanceof List) || ((List<?>) rawLines).isEmpty()
                || ((List<?>) rawLines).size() > MAX_BOM_LINES) {
            throw new WmsError("BOM 组件须为 1-" + MAX_BOM_LINES + " 行。", 400);
        }
        Set<String> seen = new HashSet<>();
        List<BomLineDraft> lines = new ArrayList<>();
        int index = 0;
        for (Object rowRaw : (List<Object>) rawLines) {
            index++;
            Map<String, Object> line = object(rowRaw, "第 " + index + " 行组件无效。");
            WmsRules.strictKeys(line, "componentProductRecordId", "quantityPer", "notes");
            String componentProductRecordId = WmsRules.requireText(line.get("componentProductRecordId"), "组件商品", 100);
            if (componentProductRecordId.equals(productRecordId)) throw new WmsError("成品不能作为自己的组件。", 422);
            if (!seen.add(componentProductRecordId)) throw new WmsError("第 " + index + " 行组件重复。", 422);
            int quantityPer = WmsRules.requirePositiveQuantity(line.get("quantityPer"), "单位用量");
            if (quantityPer > MAX_PLANNED_QTY) throw new WmsError("单位用量超出上限。", 422);
            String lineNotes = line.get("notes") == null ? ""
                    : WmsRules.requireText(line.get("notes"), "行备注", 200);
            lines.add(new BomLineDraft(componentProductRecordId, quantityPer, lineNotes));
        }
        return new BomDraft(productRecordId, version, notes, lines);
    }

    /** parseAssemblyDraft。 */
    public static AssemblyDraft parseAssemblyDraft(Object raw) {
        Map<String, Object> value = object(raw, "组装单内容无效。");
        WmsRules.strictKeys(value, "number", "productRecordId", "bomId", "warehouseId", "locationId",
                "lotId", "plannedQty", "currency", "notes", "idempotencyKey", "overrides");
        int plannedQty = WmsRules.requirePositiveQuantity(value.get("plannedQty"), "计划产量");
        if (plannedQty > MAX_PLANNED_QTY) throw new WmsError("计划产量超出上限。", 422);
        String currency = value.get("currency") == null ? "CNY"
                : WmsRules.requireText(value.get("currency"), "币种", 10).toUpperCase();
        Object overridesRaw = value.get("overrides") == null ? new ArrayList<>() : value.get("overrides");
        if (!(overridesRaw instanceof List)) throw new WmsError("组件调整须为数组。", 400);
        Set<String> seen = new HashSet<>();
        List<AssemblyLineOverride> overrides = new ArrayList<>();
        int index = 0;
        for (Object rowRaw : (List<Object>) overridesRaw) {
            index++;
            Map<String, Object> item = object(rowRaw, "第 " + index + " 行组件调整无效。");
            WmsRules.strictKeys(item, "componentProductRecordId", "quantity");
            String componentProductRecordId = WmsRules.requireText(item.get("componentProductRecordId"), "组件商品", 100);
            if (!seen.add(componentProductRecordId)) throw new WmsError("第 " + index + " 行组件调整重复。", 422);
            overrides.add(new AssemblyLineOverride(componentProductRecordId,
                    WmsRules.requirePositiveQuantity(item.get("quantity"), "组件数量")));
        }
        String bomId = isBlank(value.get("bomId")) ? null
                : WmsRules.requireText(value.get("bomId"), "BOM", 100);
        String lotId = isBlank(value.get("lotId")) ? null
                : WmsRules.requireText(value.get("lotId"), "成品批次", 100);
        return new AssemblyDraft(
                WmsRules.requireText(value.get("number"), "组装单号", 80),
                WmsRules.requireText(value.get("productRecordId"), "成品商品", 100),
                bomId,
                WmsRules.requireText(value.get("warehouseId"), "仓库", 100),
                WmsRules.requireText(value.get("locationId"), "成品库位", 100),
                lotId,
                plannedQty,
                currency,
                value.get("notes") == null ? "" : WmsRules.requireText(value.get("notes"), "备注", 1000),
                WmsRules.requireText(value.get("idempotencyKey"), "幂等键", 120),
                overrides);
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String && ((String) value).trim().isEmpty());
    }

    /** expandComponents：计划产量 × 单位用量，人工调整优先。 */
    public static List<Demand> expandComponents(List<Demand> bom, int plannedQty,
                                                List<AssemblyLineOverride> overrides) {
        Map<String, Integer> overrideMap = new LinkedHashMap<>();
        for (AssemblyLineOverride row : overrides) overrideMap.put(row.getComponentProductRecordId(), row.getQuantity());
        for (String key : overrideMap.keySet()) {
            boolean found = false;
            for (Demand line : bom) if (line.getComponentProductRecordId().equals(key)) { found = true; break; }
            if (!found) throw new WmsError("组件调整包含 BOM 之外的物料。", 422);
        }
        Map<String, Integer> demand = new LinkedHashMap<>();
        for (Demand line : bom) {
            Integer override = overrideMap.get(line.getComponentProductRecordId());
            long quantity = override != null ? override : (long) line.getQuantity() * plannedQty;
            if (quantity <= 0 || quantity > WmsRules.WMS_QUANTITY_LIMIT) {
                throw new WmsError("组件需求量越界。", 422);
            }
            demand.merge(line.getComponentProductRecordId(), (int) quantity, Integer::sum);
        }
        List<Demand> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : demand.entrySet()) out.add(new Demand(entry.getKey(), entry.getValue()));
        out.sort(Comparator.comparing(Demand::getComponentProductRecordId));
        return out;
    }

    /** allocateComponents：FIFO 按入库时间分配，任一组件不足整体拒绝。 */
    public static AllocationResult allocateComponents(List<Demand> demand, List<AllocationCandidate> candidates,
                                                      String warehouseId) {
        List<AllocationCandidate> ordered = new ArrayList<>();
        for (AllocationCandidate row : candidates) {
            if (warehouseId.equals(row.getWarehouseId()) && row.getAvailable() > 0) ordered.add(row);
        }
        ordered.sort(Comparator.comparing(AllocationCandidate::getCreatedAt)
                .thenComparing(AllocationCandidate::getBalanceId));
        List<AllocatedLine> lines = new ArrayList<>();
        List<Shortage> shortages = new ArrayList<>();
        for (Demand need : demand) {
            List<AllocationCandidate> pool = new ArrayList<>();
            long total = 0;
            for (AllocationCandidate row : ordered) {
                if (row.getProductRecordId().equals(need.getComponentProductRecordId())) {
                    pool.add(row);
                    total += row.getAvailable();
                }
            }
            if (total < need.getQuantity()) {
                shortages.add(new Shortage(need.getComponentProductRecordId(), need.getQuantity(), (int) total));
                continue;
            }
            int remaining = need.getQuantity();
            for (AllocationCandidate row : pool) {
                if (remaining <= 0) break;
                int take = Math.min(remaining, row.getAvailable());
                lines.add(new AllocatedLine(row.getBalanceId(), need.getComponentProductRecordId(),
                        row.getLotId(), row.getLocationId(), row.getWarehouseId(), take));
                remaining -= take;
            }
        }
        return new AllocationResult(lines, shortages);
    }

    /** shortageMessage：拼接缺料文案。 */
    public static String shortageMessage(List<Shortage> shortages) {
        if (shortages.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Shortage row : shortages) {
            parts.add(row.getComponentProductRecordId() + " 需 " + row.getRequired() + " 可用 " + row.getAvailable());
        }
        return "组件库存不足：" + String.join("；", parts);
    }

    /** scaleCost：字符串或数字 → 1e-6 微单位整数。 */
    public static BigInteger scaleCost(Object value) {
        String text;
        if (value instanceof Number) {
            text = BigDecimal.valueOf(((Number) value).doubleValue()).setScale(6, RoundingMode.HALF_UP).toPlainString();
        } else if (value instanceof String) {
            text = ((String) value).trim();
        } else {
            throw new WmsError("成本数值无效。", 400);
        }
        if (!text.matches("-?\\d+(\\.\\d{1,6})?")) throw new WmsError("成本数值无效。", 400);
        boolean negative = text.startsWith("-");
        String body = negative ? text.substring(1) : text;
        String[] parts = body.split("\\.", -1);
        String whole = parts[0];
        String fraction = parts.length > 1 ? parts[1] : "";
        while (fraction.length() < 6) fraction += "0";
        BigInteger units = new BigInteger(whole).multiply(ASSEMBLY_SCALE).add(new BigInteger(fraction.substring(0, 6)));
        return negative ? units.negate() : units;
    }

    /** formatCost：微单位整数 → 六位定点字符串。 */
    public static String formatCost(BigInteger units) {
        boolean negative = units.signum() < 0;
        BigInteger abs = negative ? units.negate() : units;
        BigInteger whole = abs.divide(ASSEMBLY_SCALE);
        String fraction = String.format("%06d", abs.remainder(ASSEMBLY_SCALE));
        return (negative ? "-" : "") + whole + "." + fraction;
    }

    /** lineAmount：耗用量 × 单价。 */
    public static String lineAmount(int quantity, String unitCost) {
        return formatCost(scaleCost(unitCost).multiply(BigInteger.valueOf(quantity)));
    }

    /** rolledUnitCost：总成本 / 产出，四舍五入 6 位（half-up）。 */
    public static String rolledUnitCost(String totalCost, int outputQty) {
        if (outputQty <= 0) throw new WmsError("产出数量须为正整数。", 422);
        BigInteger total = scaleCost(totalCost);
        BigInteger qty = BigInteger.valueOf(outputQty);
        return formatCost(total.add(qty.divide(BigInteger.valueOf(2))).divide(qty));
    }

    /** totalComponentCost：Σ(耗用量 × 单价)。 */
    public static String totalComponentCost(List<ConsumedLine> lines) {
        BigInteger total = BigInteger.ZERO;
        for (ConsumedLine line : lines) {
            total = total.add(scaleCost(line.getUnitCost()).multiply(BigInteger.valueOf(line.getConsumedQty())));
        }
        return formatCost(total);
    }

    /** 组件耗用成本行。 */
    public static final class ConsumedLine {
        private final int consumedQty;
        private final Object unitCost;

        public ConsumedLine(int consumedQty, Object unitCost) {
            this.consumedQty = consumedQty;
            this.unitCost = unitCost;
        }

        public int getConsumedQty() { return consumedQty; }
        public Object getUnitCost() { return unitCost; }
    }

    /** assemblyScopeKey：组织 + 工作区。 */
    public static List<String> assemblyScopeKey(WmsContracts.Scope scope) {
        return Arrays.asList(scope.getOrganizationId(), scope.getWorkspaceId());
    }
}

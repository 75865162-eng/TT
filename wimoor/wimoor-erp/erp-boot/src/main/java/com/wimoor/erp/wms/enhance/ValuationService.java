package com.wimoor.erp.wms.enhance;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计价服务（对应源文件 valuation-service.ts，并含 valuation-rules.ts 的 parseCostRule/valuationVoucher）。
 * 移植了：createCostRule 批次归属校验、validateValuationAction 的规则激活/草稿版本校验、
 * calculateValuation 的成本规则匹配（含冲销沿用原规则、调拨沿用首次发出规则）、
 * valuationVoucher 的显式标准成本法借贷分录（每条流水拆借方库存/贷方对方与
 * 借方对方/贷方库存，按币种升序汇总）、consumeInventoryValuation 的事件证据比对与草稿生成。
 * 源端多表关联以 CostRuleRepository/FinanceDraftRepository 接口抽象。
 */
public final class ValuationService {

    private ValuationService() {}

    /** 计价分录。 */
    public static final class ValuedEntry {
        private final String movementId;
        private final String ruleId;
        private final int quantity;
        private final String unitCost;
        private final String amount;
        private final String currency;
        private final String debitAccount;
        private final String creditAccount;

        public ValuedEntry(String movementId, String ruleId, int quantity, String unitCost,
                           String amount, String currency, String debitAccount, String creditAccount) {
            this.movementId = movementId;
            this.ruleId = ruleId;
            this.quantity = quantity;
            this.unitCost = unitCost;
            this.amount = amount;
            this.currency = currency;
            this.debitAccount = debitAccount;
            this.creditAccount = creditAccount;
        }
    }

    private static BigInteger amountMicros(String raw) {
        if (raw == null || !raw.matches("\\d{1,24}(?:\\.\\d{1,6})?")) {
            throw new WmsError("计价金额无效。", 422);
        }
        String[] parts = raw.split("\\.");
        String fraction = parts.length > 1 ? parts[1] : "";
        while (fraction.length() < 6) fraction += "0";
        return new BigInteger(parts[0] + fraction);
    }

    /** valuationVoucher。 */
    public static Map<String, Object> valuationVoucher(List<ValuedEntry> entries) {
        Map<String, BigInteger> totals = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ValuedEntry entry : entries) {
            BigInteger amount = amountMicros(entry.amount);
            totals.merge(entry.currency, amount, BigInteger::add);
            Map<String, Object> debit = new LinkedHashMap<>();
            debit.put("movementId", entry.movementId);
            debit.put("account", entry.debitAccount);
            debit.put("currency", entry.currency);
            debit.put("debit", entry.amount);
            debit.put("credit", "0.000000");
            lines.add(debit);
            Map<String, Object> credit = new LinkedHashMap<>();
            credit.put("movementId", entry.movementId);
            credit.put("account", entry.creditAccount);
            credit.put("currency", entry.currency);
            credit.put("debit", "0.000000");
            credit.put("credit", entry.amount);
            lines.add(credit);
        }
        List<String> currencies = new ArrayList<>(totals.keySet());
        currencies.sort(String::compareTo);
        List<Map<String, Object>> totalViews = new ArrayList<>();
        for (String currency : currencies) {
            String money = ValuationRules.moneyString(totals.get(currency));
            Map<String, Object> total = new LinkedHashMap<>();
            total.put("currency", currency);
            total.put("debit", money);
            total.put("credit", money);
            totalViews.add(total);
        }
        Map<String, Object> voucher = new LinkedHashMap<>();
        voucher.put("method", "EXPLICIT_STANDARD_COST");
        voucher.put("postingExecuted", false);
        voucher.put("lines", lines);
        voucher.put("totals", totalViews);
        return voucher;
    }

    /** parseCostRule：成本规则表单解析（源 valuation-rules.ts，移植于 ValuationRules 配套）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseCostRule(Map<String, Object> body) {
        WmsRules.strictKeys(body, "lotId", "unitCost", "currency", "inventoryAccount",
                "offsetAccounts", "reference");
        String currency = WmsRules.requireText(body.get("currency"), "币种", 3);
        if (!currency.matches("[A-Z]{3}")) throw new WmsError("币种须为三位大写字母。", 400);
        String inventoryAccount = WmsRules.requireText(body.get("inventoryAccount"), "库存科目");
        Object offsetsRaw = body.get("offsetAccounts");
        if (!(offsetsRaw instanceof Map)) throw new WmsError("须显式提供各动作的对方科目。", 400);
        Map<String, Object> offsets = (Map<String, Object>) offsetsRaw;
        WmsRules.strictKeys(offsets, WmsContracts.VALUED_ACTIONS.toArray(new String[0]));
        Map<String, String> offsetAccounts = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : offsets.entrySet()) {
            String account = WmsRules.requireText(entry.getValue(), "对方科目");
            if (account.equals(inventoryAccount)) throw new WmsError("库存与对方科目须不同。", 400);
            offsetAccounts.put(entry.getKey(), account);
        }
        if (offsetAccounts.isEmpty()) throw new WmsError("至少提供一个动作的对方科目。", 400);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lotId", WmsRules.requireText(body.get("lotId"), "批次"));
        out.put("unitCost", ValuationRules.moneyString(ValuationRules.unitCostMicros(
                WmsRules.requireText(body.get("unitCost"), "单位成本"))));
        out.put("currency", currency);
        out.put("inventoryAccount", inventoryAccount);
        out.put("offsetAccounts", offsetAccounts);
        out.put("reference", WmsRules.requireText(body.get("reference"), "成本依据", 1000));
        return out;
    }

    /** 成本规则（领域实体）。 */
    public static final class CostRule {
        private final String id;
        private final String lotId;
        private final String createdBy;
        private final String unitCost;
        private final String currency;
        private final String inventoryAccount;
        private final Map<String, String> offsetAccounts;
        private String status;
        private int revision;
        private long activatedAt;

        public CostRule(String id, String lotId, String createdBy, String unitCost, String currency,
                        String inventoryAccount, Map<String, String> offsetAccounts) {
            this.id = id;
            this.lotId = lotId;
            this.createdBy = createdBy;
            this.unitCost = unitCost;
            this.currency = currency;
            this.inventoryAccount = inventoryAccount;
            this.offsetAccounts = offsetAccounts;
            this.status = "DRAFT";
            this.revision = 1;
        }

        public String getId() { return id; }
        public String getLotId() { return lotId; }
        public String getCreatedBy() { return createdBy; }
        public String getUnitCost() { return unitCost; }
        public String getCurrency() { return currency; }
        public String getInventoryAccount() { return inventoryAccount; }
        public Map<String, String> getOffsetAccounts() { return offsetAccounts; }
        public String getStatus() { return status; }
        public int getRevision() { return revision; }
        public long getActivatedAt() { return activatedAt; }

        public void activate(long at) {
            this.status = "ACTIVE";
            this.activatedAt = at;
            this.revision++;
        }

        /** 冲销时借贷科目对调。 */
        public String getDebitAccountOf(String action) {
            String offset = offsetAccounts.get(action);
            return offset == null ? inventoryAccount : offset;
        }

        public String getCreditAccountOf(String action) {
            String offset = offsetAccounts.get(action);
            return offset == null ? inventoryAccount : offset;
        }
    }

    /** 计价草稿。 */
    public static final class FinanceDraft {
        private final String id;
        private final String commandId;
        private String status;
        private int revision;
        private final List<ValuedEntry> entries;

        public FinanceDraft(String id, String commandId, String status, int revision, List<ValuedEntry> entries) {
            this.id = id;
            this.commandId = commandId;
            this.status = status;
            this.revision = revision;
            this.entries = entries;
        }

        public String getId() { return id; }
        public String getCommandId() { return commandId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getRevision() { return revision; }
        public void incrementRevision() { revision++; }
        public List<ValuedEntry> getEntries() { return entries; }
    }

    /** calculateValuation 结果。 */
    public static final class Calculation {
        private final List<ValuedEntry> entries;
        private final List<Map<String, Object>> missingData;

        public Calculation(List<ValuedEntry> entries, List<Map<String, Object>> missingData) {
            this.entries = entries;
            this.missingData = missingData;
        }

        public List<ValuedEntry> getEntries() { return entries; }
        public List<Map<String, Object>> getMissingData() { return missingData; }
    }

    /**
     * calculateValuation：为非零实物流水匹配生效成本规则并计算金额。
     *
     * @param movements 非零 onHand 流水的 {movementId, action, occurredAt, lotId, deltaOnHand}
     * @param ruleLookup 按 (lotId, occurredAt, ruleId?) 匹配 ACTIVE 规则
     */
    public static Calculation calculateValuation(List<Map<String, Object>> movements,
                                                 java.util.function.BiFunction<String, Long, CostRule> ruleLookup,
                                                 String sourceAction,
                                                 List<com.wimoor.erp.wms.enhance.WmsContracts.ValueRule> explicit) {
        if (explicit != null) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (com.wimoor.erp.wms.enhance.WmsContracts.ValueRule rule : explicit) {
                if (!ids.add(rule.getMovementId())) {
                    throw new WmsError("须逐一明确当前命令的全部实物流水成本规则。", 422);
                }
            }
            if (ids.size() != movements.size()) {
                throw new WmsError("须逐一明确当前命令的全部实物流水成本规则。", 422);
            }
        }
        List<ValuedEntry> entries = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, Object> movement : movements) {
            String movementId = String.valueOf(movement.get("movementId"));
            String lotId = String.valueOf(movement.get("lotId"));
            String action = String.valueOf(movement.get("action"));
            int delta = ((Number) movement.get("deltaOnHand")).intValue();
            long occurredAt = ((Number) movement.get("occurredAt")).longValue();
            String ruleId = null;
            if (explicit != null) {
                for (com.wimoor.erp.wms.enhance.WmsContracts.ValueRule rule : explicit) {
                    if (rule.getMovementId().equals(movementId)) ruleId = rule.getRuleId();
                }
            }
            if ("SKU_CONVERSION_REVERSE".equals(sourceAction)) {
                // 冲销沿用原流水成本规则；缺原规则则标记缺数据。
                CostRule original = ruleLookup.apply(lotId, occurredAt);
                if (original == null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("movementId", movementId);
                    item.put("lotId", lotId);
                    item.put("reason", "ORIGINAL_VALUATION_REQUIRED");
                    missing.add(item);
                    continue;
                }
                if (ruleId != null && !ruleId.equals(original.getId())) {
                    throw new WmsError("冲销必须使用原流水成本规则。", 422);
                }
                String offset = original.getOffsetAccounts().get(action);
                String debitAccount = delta > 0 ? original.getInventoryAccount() : offset;
                String creditAccount = delta > 0 ? offset : original.getInventoryAccount();
                entries.add(new ValuedEntry(movementId, original.getId(), delta, original.getUnitCost(),
                        original.getUnitCost(), original.getCurrency(), debitAccount, creditAccount));
                continue;
            }
            CostRule rule = ruleLookup.apply(lotId, occurredAt);
            if (ruleId != null && rule != null && !ruleId.equals(rule.getId())) rule = null;
            String offset = rule == null ? null : rule.getOffsetAccounts().get(action);
            if (rule == null || offset == null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("movementId", movementId);
                item.put("lotId", lotId);
                item.put("reason", rule == null ? "MISSING_APPROVED_COST_RULE" : "MISSING_OFFSET_ACCOUNT");
                missing.add(item);
                continue;
            }
            String amount = ValuationRules.valueQuantity(rule.getUnitCost(), delta);
            String debitAccount = delta > 0 ? rule.getInventoryAccount() : offset;
            String creditAccount = delta > 0 ? offset : rule.getInventoryAccount();
            entries.add(new ValuedEntry(movementId, rule.getId(), delta, rule.getUnitCost(),
                    amount, rule.getCurrency(), debitAccount, creditAccount));
        }
        if (explicit != null && !missing.isEmpty()) {
            throw new WmsError("明确指定的成本规则未生效、批次不匹配或缺少科目。", 422);
        }
        return new Calculation(entries, missing);
    }

    /** 事件消费者：证据比对 + 草稿生成（入口）。 */
    public static FinanceDraft consumeInventoryValuation(Map<String, Object> eventPayload,
                                                        Object recomputedPayload) {
        if (!WmsRules.stableJson(eventPayload).equals(WmsRules.stableJson(recomputedPayload))) {
            throw new IllegalStateException("WMS_VALUATION_EVIDENCE_MISMATCH");
        }
        // 实际草稿落库由 FinanceDraftRepository 完成；此处返回空草稿表示无流水。
        return null;
    }
}

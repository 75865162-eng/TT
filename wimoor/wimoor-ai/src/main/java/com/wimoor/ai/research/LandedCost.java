package com.wimoor.ai.research;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 到岸成本情景重算（移植自 src/lib/selection-research/landed-cost.ts）。
 * 从价税相加、逐件计算；customsValue 只是显式税基，绝不作为成本二次相加。
 */
public final class LandedCost {

    public static final List<String> FIELDS = List.of(
            "fob", "freight", "insurance", "brokerage", "warehouseInbound", "otherFees", "customsValue");
    private static final Set<String> METHODS = Set.of("ad_valorem_additive", "unsupported");
    private static final Set<String> BASIS_KINDS = Set.of("observed", "supplier_quote", "estimated", "assumption");
    private static final Set<String> HARD_EVIDENCE = Set.of("observed", "supplier_quote");
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9007199254740991L);
    private static final BigDecimal MAX_VALUE = BigDecimal.valueOf(Double.MAX_VALUE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    public static class TariffSource {
        public List<String> evidenceIds = new ArrayList<>();
        public String sourceUrl;
        public String applicableDate;
        public String note;
        public String status; // candidate/verified 或 verified/pending/stress_test
    }

    public static final class Classification extends TariffSource {
        public String code;
    }

    public static final class Rate extends TariffSource {
        public String name;
        public String kind; // base | additional
        public BigDecimal rate;
    }

    public static final class LandedCostScenario {
        public String name;
        public String currency;
        public String originCountry;
        public String destinationCountry;
        public String asOf;
        public BigInteger quantity;
        public Classification classification;
        public String method;
        public Map<String, BigDecimal> costs = new java.util.LinkedHashMap<>();
        public Map<String, Economics.CostBasis> basis = new java.util.LinkedHashMap<>();
        public List<Rate> rates = new ArrayList<>();
    }

    public static final class Tax {
        public final String name;
        public final String kind;
        public final String status;
        public final BigDecimal rate;
        public final BigDecimal unitTax;

        public Tax(String name, String kind, String status, BigDecimal rate, BigDecimal unitTax) {
            this.name = name;
            this.kind = kind;
            this.status = status;
            this.rate = rate;
            this.unitTax = unitTax;
        }
    }

    public static final class Calculation {
        public List<Tax> taxes = new ArrayList<>();
        public BigDecimal baseDuty;
        public BigDecimal additionalDuty;
        public BigDecimal unitLandedCost;
        public BigDecimal batchLandedCost;
        public boolean hasUnverifiedInputs;
        public final Map<String, String> formulas = Map.of(
                "duty", "customsValue * rate (rates are fractions; 10% = 0.10; additive, no compounding)",
                "unit", "fob + freight + insurance + baseDuty + additionalDuty + brokerage + warehouseInbound + otherFees",
                "batch", "unitLandedCost * quantity");
        public final List<String> limitations = List.of(
                "仅支持明确的从价税相加情景；不支持的从量税、复合税、抵扣或税率适用条件需另行核实",
                "结果未应用报关机构的法定舍入规则；情景算术不能证明税率当前有效");
    }

    public static final class Evaluation {
        public String status; // NEEDS_DATA | SCENARIO_CALCULATED
        public List<String> missing = new ArrayList<>();
        public Calculation calculation;
    }

    private LandedCost() {}

    private static boolean validDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return DATE.format(LocalDate.parse(value, DATE)).equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    private static void sourceValid(TariffSource source, boolean verified) {
        if (source == null || source.evidenceIds == null
                || source.evidenceIds.stream().anyMatch(id -> id == null || id.isEmpty())
                || source.note == null || source.note.strip().isEmpty()
                || source.sourceUrl != null && !source.sourceUrl.startsWith("https://")
                || source.applicableDate != null && !validDate(source.applicableDate))
            throw new ResearchError("INVALID_TARIFF_SOURCE");
        if (verified && (source.evidenceIds.isEmpty() || source.sourceUrl == null || source.applicableDate == null))
            throw new ResearchError("VERIFIED_TARIFF_SOURCE_REQUIRED");
    }

    public static Evaluation calculateLandedCostScenario(LandedCostScenario scenario) {
        if (scenario == null || scenario.name == null || scenario.name.strip().isEmpty()
                || scenario.currency == null || !scenario.currency.matches("[A-Z]{3}")
                || scenario.originCountry == null || !scenario.originCountry.matches("[A-Z]{2}")
                || scenario.destinationCountry == null || !scenario.destinationCountry.matches("[A-Z]{2}")
                || !validDate(scenario.asOf) || !METHODS.contains(scenario.method)
                || scenario.costs == null || scenario.basis == null || scenario.classification == null
                || scenario.classification.code == null || !scenario.classification.code.matches("[0-9]{4,10}(\\.[0-9]{1,4})*")
                || !Set.of("candidate", "verified").contains(scenario.classification.status)
                || scenario.rates == null || scenario.rates.isEmpty()
                || scenario.rates.stream().filter(r -> "base".equals(r.kind)).count() != 1
                || scenario.rates.stream().map(r -> r.name).distinct().count() != scenario.rates.size()
                || scenario.quantity != null && (scenario.quantity.signum() <= 0
                    || scenario.quantity.compareTo(MAX_SAFE_INTEGER) > 0))
            throw new ResearchError("INVALID_LANDED_COST_SCENARIO");
        sourceValid(scenario.classification, "verified".equals(scenario.classification.status));
        if ("verified".equals(scenario.classification.status)
                && scenario.classification.applicableDate.compareTo(scenario.asOf) > 0)
            throw new ResearchError("INVALID_TARIFF_APPLICABILITY");
        List<String> missing = new ArrayList<>();
        for (String field : FIELDS) {
            BigDecimal value = scenario.costs.get(field);
            if (value == null) { missing.add(field); continue; }
            if (value.signum() < 0) throw new ResearchError("INVALID_LANDED_COST_INPUT");
            Economics.CostBasis basis = scenario.basis.get(field);
            if (basis == null || !BASIS_KINDS.contains(basis.kind) || basis.note == null || basis.note.strip().isEmpty()
                    || basis.evidenceIds == null
                    || HARD_EVIDENCE.contains(basis.kind) && basis.evidenceIds.isEmpty())
                throw new ResearchError("COST_BASIS_REQUIRED");
        }
        for (Rate rate : scenario.rates) {
            if (rate.name == null || rate.name.strip().isEmpty() || !Set.of("base", "additional").contains(rate.kind)
                    || !Set.of("verified", "pending", "stress_test").contains(rate.status)
                    || rate.rate != null && rate.rate.signum() < 0)
                throw new ResearchError("INVALID_TARIFF_RATE");
            sourceValid(rate, "verified".equals(rate.status));
            if (rate.rate == null) missing.add("rate:" + rate.name);
            if ("verified".equals(rate.status)
                    && (rate.rate == null || rate.applicableDate.compareTo(scenario.asOf) > 0))
                throw new ResearchError("INVALID_TARIFF_APPLICABILITY");
        }
        if ("unsupported".equals(scenario.method)) missing.add("unsupported_tax_method");
        Evaluation result = new Evaluation();
        result.missing = missing;
        if (!missing.isEmpty()) {
            result.status = "NEEDS_DATA";
            return result;
        }
        BigDecimal customsValue = scenario.costs.get("customsValue");
        Calculation calc = new Calculation();
        BigDecimal baseDuty = BigDecimal.ZERO, additionalDuty = BigDecimal.ZERO;
        for (Rate rate : scenario.rates) {
            Tax tax = new Tax(rate.name, rate.kind, rate.status, rate.rate, customsValue.multiply(rate.rate));
            calc.taxes.add(tax);
            if ("base".equals(rate.kind)) baseDuty = baseDuty.add(tax.unitTax);
            else additionalDuty = additionalDuty.add(tax.unitTax);
        }
        calc.baseDuty = baseDuty;
        calc.additionalDuty = additionalDuty;
        calc.unitLandedCost = scenario.costs.get("fob").add(scenario.costs.get("freight"))
                .add(scenario.costs.get("insurance")).add(baseDuty).add(additionalDuty)
                .add(scenario.costs.get("brokerage")).add(scenario.costs.get("warehouseInbound"))
                .add(scenario.costs.get("otherFees"));
        calc.batchLandedCost = scenario.quantity == null ? null
                : calc.unitLandedCost.multiply(new BigDecimal(scenario.quantity));
        List<BigDecimal> checks = new ArrayList<>(List.of(baseDuty, additionalDuty, calc.unitLandedCost));
        if (calc.batchLandedCost != null) checks.add(calc.batchLandedCost);
        if (checks.stream().anyMatch(v -> v.abs().compareTo(MAX_VALUE) > 0))
            throw new ResearchError("LANDED_COST_OVERFLOW");
        calc.hasUnverifiedInputs = !"verified".equals(scenario.classification.status)
                || scenario.rates.stream().anyMatch(r -> !"verified".equals(r.status))
                || FIELDS.stream().anyMatch(f -> !HARD_EVIDENCE.contains(scenario.basis.get(f).kind));
        result.status = "SCENARIO_CALCULATED";
        result.calculation = calc;
        return result;
    }

    /** 情景引用的全部证据 id（去重，保留顺序）。 */
    public static List<String> landedCostEvidenceIds(List<LandedCostScenario> scenarios) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (scenarios == null) return new ArrayList<>();
        for (LandedCostScenario scenario : scenarios) {
            ids.addAll(scenario.classification.evidenceIds);
            for (Rate rate : scenario.rates) ids.addAll(rate.evidenceIds);
            for (Economics.CostBasis basis : scenario.basis.values())
                if (basis != null) ids.addAll(basis.evidenceIds);
        }
        return new ArrayList<>(ids);
    }
}

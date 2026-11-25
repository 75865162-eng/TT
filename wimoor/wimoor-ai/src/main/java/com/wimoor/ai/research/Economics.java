package com.wimoor.ai.research;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单位利润、批量贡献与评分卡重算（移植自 src/lib/selection-research/economics.ts）。
 * 所有金额为同币种每件 BigDecimal；未知成本保持 null，绝不补 0 变成"零成本盈利"。
 */
public final class Economics {

    public static final List<String> COST_FIELDS = List.of(
            "price", "procurement", "internationalFreight", "importCharges", "referralFee",
            "fbaFee", "advertising", "storage", "returnsAllowance");
    private static final Set<String> BASIS_KINDS = Set.of("observed", "supplier_quote", "estimated", "assumption");
    private static final Set<String> HARD_EVIDENCE = Set.of("observed", "supplier_quote");
    private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9007199254740991L);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MAX_VALUE = BigDecimal.valueOf(Double.MAX_VALUE);

    public static final class UnitEconomicsInput {
        public String currency;
        public BigDecimal price;
        public BigDecimal procurement;
        public BigDecimal internationalFreight;
        public BigDecimal importCharges;
        public BigDecimal referralFee;
        public BigDecimal fbaFee;
        public BigDecimal advertising;
        public BigDecimal storage;
        public BigDecimal returnsAllowance;

        public BigDecimal costSum() {
            return procurement.add(internationalFreight).add(importCharges).add(referralFee)
                    .add(fbaFee).add(advertising).add(storage).add(returnsAllowance);
        }
    }

    public static class UnitCalculation {
        public BigDecimal landedCost;
        public BigDecimal totalCost;
        public BigDecimal profit;
        public BigDecimal margin;
        public BigDecimal breakEvenAdvertising;
        public String formula = "profit = price - procurement - internationalFreight - importCharges - referralFee - fbaFee - advertising - storage - returnsAllowance";
    }

    public static final class CostBasis {
        public String kind;
        public List<String> evidenceIds = new ArrayList<>();
        public String note;
    }

    /** 输入中的成本字段可为 null（待核实）；basis 只覆盖已填写字段。 */
    public static final class ScenarioInput {
        public String currency;
        public final Map<String, BigDecimal> costs = new LinkedHashMap<>();

        public BigDecimal get(String field) { return costs.get(field); }
        public void set(String field, BigDecimal value) { costs.put(field, value); }

        public UnitEconomicsInput toUnitInput() {
            UnitEconomicsInput in = new UnitEconomicsInput();
            in.currency = currency;
            in.price = get("price");
            in.procurement = get("procurement");
            in.internationalFreight = get("internationalFreight");
            in.importCharges = get("importCharges");
            in.referralFee = get("referralFee");
            in.fbaFee = get("fbaFee");
            in.advertising = get("advertising");
            in.storage = get("storage");
            in.returnsAllowance = get("returnsAllowance");
            return in;
        }
    }

    public static final class ResearchEconomicsScenario {
        public String name;
        public ScenarioInput input;
        public Map<String, CostBasis> basis = new LinkedHashMap<>();
        public BigInteger quantity;
        public BigDecimal fixedCosts;
    }

    public static final class Dimension {
        public String name;
        public BigDecimal weight;
        public BigDecimal score;
        public List<String> evidenceIds = new ArrayList<>();
        public String rationale;
    }

    public static final class ProductScorecard {
        public String candidateId;
        public List<Dimension> dimensions = new ArrayList<>();
    }

    public static final class DecisionAnalysis {
        public List<ResearchEconomicsScenario> scenarios = new ArrayList<>();
        public List<ProductScorecard> scorecards = new ArrayList<>();
    }

    public static final class Calculation extends UnitCalculation {
        public BigDecimal contribution;
        public BigDecimal contributionRate;
        public BigDecimal breakEvenPrice;
        public BigInteger breakEvenUnits;
        public BigDecimal batchLandedCost;
        public BigDecimal batchContribution;
        public BigDecimal batchAfterFixedCosts;
        public final Map<String, String> formulas = Map.of(
                "breakEvenUnits", "ceil(fixedCosts / unitContribution), only when unitContribution > 0",
                "batchLandedCost", "landedCost * quantity",
                "batchAfterFixedCosts", "unitContribution * quantity - fixedCosts");
    }

    public static final class Evaluation {
        public String status; // NEEDS_DATA | CALCULATED
        public List<String> missing = new ArrayList<>();
        public Calculation calculation;
    }

    public static final class ScoreEvaluation {
        public BigDecimal total;
        public BigDecimal observedWeight;
        public List<String> missingDimensions = new ArrayList<>();
        public String formula = "sum(weight * score), weights sum to 1; missing scores keep total unknown";
    }

    private Economics() {}

    /** 所有成本必须显式、同币种、每件；没有默认税率/FBA/广告费率。 */
    public static UnitCalculation calculateUnitEconomics(UnitEconomicsInput input) {
        if (input == null || input.currency == null || !input.currency.matches("[A-Z]{3}")
                || input.price == null || input.price.signum() <= 0)
            throw new ResearchError("INVALID_ECONOMICS_PRICE_OR_CURRENCY");
        List<BigDecimal> costs = List.of(input.procurement, input.internationalFreight, input.importCharges,
                input.referralFee, input.fbaFee, input.advertising, input.storage, input.returnsAllowance);
        if (costs.stream().anyMatch(c -> c == null || c.signum() < 0))
            throw new ResearchError("ALL_UNIT_COSTS_MUST_BE_EXPLICIT");
        UnitCalculation out = new UnitCalculation();
        out.landedCost = input.procurement.add(input.internationalFreight).add(input.importCharges);
        out.totalCost = input.costSum();
        out.profit = input.price.subtract(out.totalCost);
        out.margin = out.profit.divide(input.price, MC);
        out.breakEvenAdvertising = input.price.subtract(out.totalCost.subtract(input.advertising));
        return out;
    }

    public static Evaluation evaluateResearchEconomics(ResearchEconomicsScenario scenario) {
        if (scenario == null || scenario.name == null || scenario.name.strip().isEmpty() || scenario.input == null
                || scenario.input.currency == null || !scenario.input.currency.matches("[A-Z]{3}") || scenario.basis == null)
            throw new ResearchError("INVALID_ECONOMICS_SCENARIO");
        if (scenario.quantity != null && (scenario.quantity.signum() <= 0
                || scenario.quantity.compareTo(MAX_SAFE_INTEGER) > 0)
                || scenario.fixedCosts != null && scenario.fixedCosts.signum() < 0)
            throw new ResearchError("INVALID_BATCH_COSTS");
        List<String> missing = new ArrayList<>();
        for (String field : COST_FIELDS) {
            BigDecimal value = scenario.input.get(field);
            if (value == null) { missing.add(field); continue; }
            if (value.signum() < 0 || "price".equals(field) && value.signum() == 0)
                throw new ResearchError("INVALID_SCENARIO_COST");
            CostBasis basis = scenario.basis.get(field);
            if (basis == null || !BASIS_KINDS.contains(basis.kind) || basis.note == null || basis.note.strip().isEmpty()
                    || basis.evidenceIds == null
                    || HARD_EVIDENCE.contains(basis.kind) && basis.evidenceIds.isEmpty())
                throw new ResearchError("COST_BASIS_REQUIRED");
        }
        Evaluation result = new Evaluation();
        result.missing = missing;
        if (!missing.isEmpty()) {
            result.status = "NEEDS_DATA";
            return result;
        }
        UnitCalculation unit = calculateUnitEconomics(scenario.input.toUnitInput());
        Calculation calc = new Calculation();
        copyUnit(unit, calc);
        calc.contribution = unit.profit;
        calc.contributionRate = unit.margin;
        calc.breakEvenPrice = unit.totalCost;
        calc.breakEvenUnits = scenario.fixedCosts == null || unit.profit.signum() <= 0 ? null
                : scenario.fixedCosts.divide(unit.profit, 0, RoundingMode.CEILING).toBigIntegerExact();
        calc.batchLandedCost = scenario.quantity == null ? null : unit.landedCost.multiply(bigDecimal(scenario.quantity));
        calc.batchContribution = scenario.quantity == null ? null : unit.profit.multiply(bigDecimal(scenario.quantity));
        calc.batchAfterFixedCosts = calc.batchContribution == null || scenario.fixedCosts == null
                ? null : calc.batchContribution.subtract(scenario.fixedCosts);
        checkFinite(calc);
        result.status = "CALCULATED";
        result.calculation = calc;
        return result;
    }

    private static void copyUnit(UnitCalculation src, UnitCalculation dst) {
        dst.landedCost = src.landedCost;
        dst.totalCost = src.totalCost;
        dst.profit = src.profit;
        dst.margin = src.margin;
        dst.breakEvenAdvertising = src.breakEvenAdvertising;
    }

    private static BigDecimal bigDecimal(BigInteger value) { return new BigDecimal(value); }

    private static void checkFinite(Calculation calc) {
        List<BigDecimal> values = new ArrayList<>(List.of(calc.landedCost, calc.totalCost, calc.profit, calc.margin,
                calc.breakEvenAdvertising, calc.contribution, calc.contributionRate, calc.breakEvenPrice,
                calc.batchLandedCost, calc.batchContribution, calc.batchAfterFixedCosts));
        for (BigDecimal value : values)
            if (value != null && value.abs().compareTo(MAX_VALUE) > 0) throw new ResearchError("ECONOMICS_OVERFLOW");
    }

    /** 缺失维度不赋中性分、不自动重分配权重；总分在有缺失时保持未知。 */
    public static ScoreEvaluation evaluateProductScorecard(ProductScorecard card) {
        if (card == null || card.candidateId == null || card.candidateId.strip().isEmpty()
                || card.dimensions == null || card.dimensions.isEmpty())
            throw new ResearchError("INVALID_SCORECARD");
        Set<String> names = new HashSet<>();
        for (Dimension d : card.dimensions) if (!names.add(d.name)) throw new ResearchError("INVALID_SCORECARD");
        BigDecimal weight = BigDecimal.ZERO, observedWeight = BigDecimal.ZERO, total = BigDecimal.ZERO;
        for (Dimension row : card.dimensions) {
            if (row.name == null || row.name.strip().isEmpty() || row.rationale == null || row.rationale.strip().isEmpty()
                    || row.weight == null || row.weight.signum() <= 0 || row.weight.compareTo(BigDecimal.ONE) > 0
                    || row.evidenceIds == null
                    || row.score != null && (row.score.signum() < 0 || row.score.compareTo(BigDecimal.valueOf(100)) > 0
                        || row.evidenceIds.isEmpty()))
                throw new ResearchError("INVALID_SCORE_DIMENSION");
            weight = weight.add(row.weight);
            if (row.score != null) {
                observedWeight = observedWeight.add(row.weight);
                total = total.add(row.score.multiply(row.weight));
            }
        }
        if (weight.subtract(BigDecimal.ONE).abs().compareTo(BigDecimal.valueOf(1e-6)) > 0)
            throw new ResearchError("SCORE_WEIGHTS_MUST_SUM_TO_ONE");
        ScoreEvaluation out = new ScoreEvaluation();
        out.observedWeight = observedWeight;
        boolean missing = card.dimensions.stream().anyMatch(row -> row.score == null);
        out.total = missing ? null : total;
        if (missing) for (Dimension row : card.dimensions) if (row.score == null) out.missingDimensions.add(row.name);
        return out;
    }
}

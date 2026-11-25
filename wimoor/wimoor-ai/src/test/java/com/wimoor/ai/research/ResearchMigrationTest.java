package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨栈迁移确定性测试：五阶段编排、成本/到岸价、预算、schema、授权与输入校验。
 */
class ResearchMigrationTest {

    private static final ResearchTypes.Scope SCOPE = new ResearchTypes.Scope("org-1", "ws-1", "user-1");

    private static ResearchEngine.CreateInput fixtureInput() {
        ResearchEngine.CreateInput input = new ResearchEngine.CreateInput();
        input.requirement = "研究宠物用品市场";
        input.marketplace = "US";
        List<ResearchTypes.SkillSnapshot> skills = new ArrayList<>();
        for (int i = 0; i < ResearchTypes.SKILLS.size(); i++)
            skills.add(new ResearchTypes.SkillSnapshot(ResearchTypes.SKILLS.get(i),
                    "/skills/" + ResearchTypes.SKILLS.get(i) + "/SKILL.md", "sha-" + i, "# skill " + i, List.of()));
        input.skills = skills;
        input.policies = Fixture.researchFixture().policies;
        input.limits = new LinkedHashMap<>();
        return input;
    }

    private static ResearchEngine fixtureEngine(ResearchStore store) {
        Fixture.Components fixture = Fixture.researchFixture();
        return new ResearchEngine(store, SCOPE, fixture.transport, fixture.agent,
                new ResearchEngine.Options("fixture"));
    }

    private static ResearchTypes.ResearchRun stepUntilWait(ResearchEngine engine, String id) {
        for (int i = 0; i < 8; i++) {
            ResearchTypes.ResearchRun run = engine.step(id, null);
            if ("WAITING_FOR_USER".equals(run.status) || "COMPLETED".equals(run.status)) return run;
            if ("FAILED".equals(run.status)) fail("unexpected failure: " + run.failure);
        }
        throw new AssertionError("no user gate reached");
    }

    private static ResearchTypes.ResearchRun stepUntilStage(ResearchEngine engine, String id, int stage) {
        for (int i = 0; i < 8; i++) {
            ResearchTypes.ResearchRun run = engine.step(id, null);
            if (run.stage >= stage || "WAITING_FOR_USER".equals(run.status)) return run;
            if ("FAILED".equals(run.status)) fail("unexpected failure: " + run.failure);
        }
        throw new AssertionError("stage " + stage + " not reached");
    }

    @Test
    void fiveStageOrchestrationFollowsHumanGates() {
        ResearchStore store = new InMemoryResearchStore();
        ResearchEngine engine = fixtureEngine(store);
        ResearchTypes.ResearchRun run = engine.create(fixtureInput());
        assertEquals(1, run.stage);
        assertEquals(ResearchTypes.Status.CREATED, run.status);

        run = stepUntilWait(engine, run.id);
        assertEquals(1, run.results.size());
        assertEquals(ResearchTypes.GateKind.NICHE, run.gate.kind);
        assertEquals(ResearchTypes.Status.WAITING_FOR_USER, run.status);

        ResearchTypes.Decision select = new ResearchTypes.Decision();
        select.gateId = run.gate.id;
        select.kind = ResearchTypes.DecisionKind.SELECT;
        select.candidateId = run.results.get(0).candidates.get(0).id;
        run = engine.decide(run.id, select, run.revision);
        assertEquals(2, run.stage);
        assertEquals("fixture-1", run.selectedCandidateId);

        assertEquals(3, stepUntilStage(engine, run.id, 3).stage);

        run = stepUntilStage(engine, run.id, 4);
        assertEquals(4, run.stage);
        assertEquals(4, run.calls.size());
        long reviewCalls = run.calls.stream().filter(c -> "market_get_reviews".equals(c.tool)
                && "SUCCEEDED".equals(c.status)).count();
        assertEquals(2, reviewCalls);

        run = stepUntilWait(engine, run.id);
        assertEquals(5, run.stage);
        assertEquals(5, run.results.size());
        assertEquals(ResearchTypes.GateKind.FINAL, run.gate.kind);

        ResearchTypes.Decision confirm = new ResearchTypes.Decision();
        confirm.gateId = run.gate.id;
        confirm.kind = ResearchTypes.DecisionKind.CONFIRM;
        run = engine.decide(run.id, confirm, run.revision);
        assertEquals(ResearchTypes.Status.COMPLETED, run.status);
        assertEquals(2, run.decisions.size());

        String html = Report.renderResearchReport(run);
        assertTrue(html.contains("AI 选品研究报告"));
        assertTrue(html.contains("id=\"stage-5\""));
        Map<String, Object> saved = engine.saveReport(run.id, r -> "file-1");
        assertEquals("file-1", saved.get("fileId"));
        assertEquals("file-1", engine.saveReport(run.id, r -> "file-2").get("fileId"));

        // 两个人工门缺一不可
        ResearchStore s2 = new InMemoryResearchStore();
        ResearchEngine e2 = fixtureEngine(s2);
        ResearchTypes.ResearchRun r2 = e2.create(fixtureInput());
        assertThrows(ResearchError.class, () -> e2.saveReport(r2.id, runValue -> "file-1"));
    }

    @Test
    void staleDecisionAndWrongGateAreRejected() {
        ResearchStore store = new InMemoryResearchStore();
        ResearchEngine engine = fixtureEngine(store);
        ResearchTypes.ResearchRun run = engine.create(fixtureInput());
        run = stepUntilWait(engine, run.id);
        ResearchTypes.Decision bad = new ResearchTypes.Decision();
        bad.gateId = "not-the-gate";
        bad.kind = ResearchTypes.DecisionKind.SELECT;
        bad.candidateId = "fixture-1";
        final String runId = run.id;
        final int revision = run.revision;
        assertThrows(ResearchError.class, () -> engine.decide(runId, bad, revision));
        assertThrows(ResearchError.class, () -> engine.decide(runId, bad, revision - 1));
    }

    @Test
    void unitEconomicsAndScorecardUseExactBigDecimal() {
        Economics.UnitEconomicsInput in = new Economics.UnitEconomicsInput();
        in.currency = "USD";
        in.price = new BigDecimal("100");
        in.procurement = new BigDecimal("20");
        in.internationalFreight = new BigDecimal("5");
        in.importCharges = new BigDecimal("3");
        in.referralFee = new BigDecimal("15");
        in.fbaFee = new BigDecimal("8");
        in.advertising = new BigDecimal("10");
        in.storage = new BigDecimal("2");
        in.returnsAllowance = new BigDecimal("2");
        Economics.UnitCalculation calc = Economics.calculateUnitEconomics(in);
        assertEquals(0, new BigDecimal("28").compareTo(calc.landedCost));
        assertEquals(0, new BigDecimal("65").compareTo(calc.totalCost));
        assertEquals(0, new BigDecimal("35").compareTo(calc.profit));
        assertEquals(0, new BigDecimal("0.35").compareTo(calc.margin));
        assertEquals(0, new BigDecimal("45").compareTo(calc.breakEvenAdvertising));

        Economics.ResearchEconomicsScenario scenario = new Economics.ResearchEconomicsScenario();
        scenario.name = "base";
        scenario.input = new Economics.ScenarioInput();
        scenario.input.currency = "USD";
        for (Map.Entry<String, BigDecimal> e : Map.of(
                "price", in.price, "procurement", in.procurement,
                "internationalFreight", in.internationalFreight, "importCharges", in.importCharges,
                "referralFee", in.referralFee, "fbaFee", in.fbaFee, "advertising", in.advertising,
                "storage", in.storage, "returnsAllowance", in.returnsAllowance).entrySet())
            scenario.input.set(e.getKey(), e.getValue());
        Economics.CostBasis basis = new Economics.CostBasis();
        basis.kind = "observed";
        basis.note = "sample";
        basis.evidenceIds = List.of("call-1");
        for (String field : Economics.COST_FIELDS) scenario.basis.put(field, basis);
        scenario.quantity = BigInteger.valueOf(100);
        scenario.fixedCosts = new BigDecimal("350");
        Economics.Evaluation evaluated = Economics.evaluateResearchEconomics(scenario);
        assertEquals("CALCULATED", evaluated.status);
        assertEquals(0, new BigDecimal("3500").compareTo(evaluated.calculation.batchContribution));
        assertEquals(BigInteger.valueOf(10), evaluated.calculation.breakEvenUnits);

        scenario.input.set("fbaFee", null);
        assertEquals("NEEDS_DATA", Economics.evaluateResearchEconomics(scenario).status);
        assertEquals(List.of("fbaFee"), Economics.evaluateResearchEconomics(scenario).missing);

        Economics.ProductScorecard card = new Economics.ProductScorecard();
        card.candidateId = "fixture-1";
        Economics.Dimension d1 = new Economics.Dimension();
        d1.name = "demand"; d1.weight = new BigDecimal("0.6"); d1.score = new BigDecimal("80");
        d1.evidenceIds = List.of("call-1"); d1.rationale = "observed";
        Economics.Dimension d2 = new Economics.Dimension();
        d2.name = "risk"; d2.weight = new BigDecimal("0.4"); d2.score = null;
        d2.evidenceIds = List.of(); d2.rationale = "unknown";
        card.dimensions = List.of(d1, d2);
        Economics.ScoreEvaluation score = Economics.evaluateProductScorecard(card);
        assertNull(score.total);
        assertEquals(0, new BigDecimal("0.6").compareTo(score.observedWeight));
        assertEquals(List.of("risk"), score.missingDimensions);
        d2.weight = new BigDecimal("0.5");
        assertThrows(ResearchError.class, () -> Economics.evaluateProductScorecard(card));
    }

    @Test
    void landedCostAddsTaxesOnceAndKeepsUnknowns() {
        LandedCost.LandedCostScenario scenario = landedScenario();
        LandedCost.Evaluation evaluation = LandedCost.calculateLandedCostScenario(scenario);
        assertEquals("SCENARIO_CALCULATED", evaluation.status);
        assertEquals(0, new BigDecimal("10").compareTo(evaluation.calculation.baseDuty));
        assertEquals(0, new BigDecimal("5").compareTo(evaluation.calculation.additionalDuty));
        assertEquals(0, new BigDecimal("47").compareTo(evaluation.calculation.unitLandedCost));
        assertEquals(0, new BigDecimal("470").compareTo(evaluation.calculation.batchLandedCost));
        assertTrue(evaluation.calculation.hasUnverifiedInputs);

        scenario.rates.get(0).rate = null;
        assertEquals("NEEDS_DATA", LandedCost.calculateLandedCostScenario(scenario).status);
        assertTrue(LandedCost.calculateLandedCostScenario(scenario).missing.contains("rate:base"));
    }

    private static LandedCost.LandedCostScenario landedScenario() {
        LandedCost.LandedCostScenario scenario = new LandedCost.LandedCostScenario();
        scenario.name = "US import";
        scenario.currency = "USD";
        scenario.originCountry = "CN";
        scenario.destinationCountry = "US";
        scenario.asOf = "2026-11-25";
        scenario.quantity = BigInteger.TEN;
        scenario.method = "ad_valorem_additive";
        LandedCost.Classification classification = new LandedCost.Classification();
        classification.code = "9403.60";
        classification.status = "candidate";
        classification.evidenceIds = List.of("e-1");
        classification.note = "candidate code";
        scenario.classification = classification;
        for (String field : LandedCost.FIELDS) {
            scenario.costs.put(field, new BigDecimal("0"));
            Economics.CostBasis basis = new Economics.CostBasis();
            basis.kind = "estimated";
            basis.note = "estimate";
            basis.evidenceIds = List.of();
            scenario.basis.put(field, basis);
        }
        scenario.costs.put("fob", new BigDecimal("20"));
        scenario.costs.put("freight", new BigDecimal("5"));
        scenario.costs.put("insurance", new BigDecimal("1"));
        scenario.costs.put("brokerage", new BigDecimal("2"));
        scenario.costs.put("warehouseInbound", new BigDecimal("3"));
        scenario.costs.put("otherFees", new BigDecimal("1"));
        scenario.costs.put("customsValue", new BigDecimal("100"));
        LandedCost.Rate base = new LandedCost.Rate();
        base.name = "base"; base.kind = "base"; base.status = "pending";
        base.rate = new BigDecimal("0.10"); base.evidenceIds = List.of("e-1"); base.note = "pending";
        LandedCost.Rate additional = new LandedCost.Rate();
        additional.name = "section301"; additional.kind = "additional"; additional.status = "stress_test";
        additional.rate = new BigDecimal("0.05"); additional.evidenceIds = List.of("e-2"); additional.note = "stress";
        scenario.rates = List.of(base, additional);
        return scenario;
    }

    @Test
    void schemaStableJsonValidateAndCredentialRejection() {
        JsonNode a = ResearchSchema.parse("{\"b\":1,\"a\":[2,1]}");
        JsonNode b = ResearchSchema.parse("{\"a\":[2,1],\"b\":1}");
        assertEquals(ResearchSchema.stableJson(a), ResearchSchema.stableJson(b));
        assertEquals("{\"a\":[2,1],\"b\":1}", ResearchSchema.stableJson(a));

        JsonNode schema = ResearchSchema.parse(
                "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\",\"minLength\":2}},\"required\":[\"keyword\"],\"additionalProperties\":false}");
        ResearchSchema.validate(schema, ResearchSchema.parse("{\"keyword\":\"ab\"}"));
        assertEquals("SCHEMA_REQUIRED:$.keyword", assertThrows(ResearchError.class,
                () -> ResearchSchema.validate(schema, ResearchSchema.parse("{}"))).code());
        assertEquals("SCHEMA_UNKNOWN:$.extra", assertThrows(ResearchError.class,
                () -> ResearchSchema.validate(schema, ResearchSchema.parse("{\"keyword\":\"ab\",\"extra\":1}"))).code());
        assertEquals("CREDENTIAL_FIELD_REJECTED", assertThrows(ResearchError.class,
                () -> ResearchSchema.rejectCredentials(Map.of("apiKey", "x"))).code());

        assertEquals("SCHEMA_ENUM:$", assertThrows(ResearchError.class, () -> ResearchSchema.validate(
                ResearchSchema.parse("{\"enum\":[\"a\",\"b\"]}"), ResearchSchema.parse("\"c\""))).code());
    }

    @Test
    void budgetPreviewAndUsageAreDeterministic() {
        ResearchStore store = new InMemoryResearchStore();
        ResearchEngine engine = fixtureEngine(store);
        ResearchTypes.ResearchRun run = engine.create(fixtureInput());
        ResearchBudget.Preview preview = ResearchBudget.preview(run);
        assertEquals("reviewed", preview.mode);
        assertEquals(18, ((Map<?, ?>) preview.planning.get("calls")).get("min"));
        assertEquals(30, ((Map<?, ?>) preview.planning.get("calls")).get("max"));
        assertEquals(5, preview.stages.size());

        ResearchTypes.CallRecord call = new ResearchTypes.CallRecord();
        call.provider = "sellersprite";
        call.paid = true;
        call.billing = "CONFIRMED";
        call.billingUnit = "credits";
        call.knownUnits = 7;
        call.dispatch = "ATTEMPTED";
        run.calls.add(call);
        assertEquals(7, ResearchBudget.researchUsage(run).get("sellersprite").confirmedCredits);

        call.researchCharge = new ResearchCredits.ResearchCharge(1.0, "CNY", 1.0,
                "2026-11-25", "rate", "receipt", "confirmed");
        ResearchBudget.CreditUsage usage = ResearchBudget.researchCreditUsage(run);
        assertEquals(0, new BigDecimal("100").compareTo(usage.confirmed));
        assertEquals(0, new BigDecimal("100").compareTo(usage.total));
        assertEquals(0, usage.pendingCalls);
    }

    @Test
    void consentAndRequestAuthorizationAreScoped() {
        PaidCallConsent.InMemorySettingStore settings = new PaidCallConsent.InMemorySettingStore();
        PaidCallConsent.Service service = new PaidCallConsent.Service(settings);
        assertNull(service.read(SCOPE));
        PaidCallConsent.Consent consent = service.save(SCOPE, true,
                (scope, actor) -> assertEquals("user-1", actor));
        assertEquals("user-1", service.read(SCOPE).approvedByUserId);
        assertEquals(consent.id, service.read(SCOPE).id);
        assertEquals(1, service.auditLog().size());
        assertNull(service.read(new ResearchTypes.Scope("org-1", "ws-1", "user-2")));

        assertThrows(ResearchError.class, () -> RequestAuthorization.withResearchRequestAuthorization(
                () -> "https://x/api/other", new RequestAuthorization.Authorization("c", (m, a, r) -> null), () -> 1));
        RequestAuthorization.RequestView request = () -> "https://x/api/selection-research/step";
        RequestAuthorization.Authorization auth = new RequestAuthorization.Authorization("cred-1",
                (m, a, r) -> () -> true);
        Integer result = RequestAuthorization.withResearchRequestAuthorization(request, auth, () -> 42);
        assertEquals(42, result);
        assertNull(RequestAuthorization.researchRequestAuthorization(request));
    }

    @Test
    void inputPreflightAndBriefParsingStayConservative() {
        Map<String, Object> body = new HashMap<>();
        body.put("mode", "live");
        body.put("requirement", "研究宠物用品，采购数量：500 件，目标售价：29.9 美元");
        body.put("marketplace", "us");
        body.put("maxMarketCalls", 10);
        Preflight.ResearchInput input = Preflight.parseResearchInput(body);
        assertEquals("reviewed", input.budgetMode);
        assertEquals("US", input.marketplace);
        assertEquals("workspace", input.aiConfigSource);
        body.put("mode", "live");
        body.put("budgetMode", "reviewed");
        body.remove("maxMarketCalls");
        assertEquals("MISSING_MARKET_CALL_BUDGET", assertThrows(ResearchError.class,
                () -> Preflight.parseResearchInput(body)).code());
        body.put("budgetMode", "mock_unlimited");
        assertEquals("LIVE_BUDGET_REVIEW_REQUIRED", assertThrows(ResearchError.class,
                () -> Preflight.parseResearchInput(body)).code());

        BriefParser.ResearchBrief brief = BriefParser.parseResearchBrief(
                "产品方向：宠物饮水机。目标用户：养猫人群。采购数量：500 件", "US");
        assertEquals("宠物饮水机", brief.productDirection.value);
        assertEquals("养猫人群", brief.targetAudience.value);
        assertEquals(500L, brief.procurementQuantity.value);
        assertTrue(brief.unresolved.contains("targetPrice"));
        BriefParser.ResearchBrief ambiguous = BriefParser.parseResearchBrief(
                "产品方向：宠物饮水机。产品方向：户外水杯", "US");
        assertNull(ambiguous.productDirection);
        assertTrue(ambiguous.unresolved.contains("productDirection"));
    }

    @Test
    void policyConsentFeeAndToolAliases() {
        assertEquals("market_get_asin", ResearchEngine.canonicalResearchTool("asin.detail"));
        assertEquals("market_get_reviews", ResearchEngine.canonicalResearchTool("sellersprite__asin.review.list"));
        assertEquals("erp__orders.list", ResearchEngine.canonicalResearchTool("erp__orders.list"));
        assertNull(ResearchEngine.canonicalResearchTool("unknown.tool"));
        assertTrue(FeePolicy.feeReviewRequired("付费调用需先审批", ""));
        assertFalse(FeePolicy.feeReviewRequired("无需费用审批", ""));
        assertEquals(18, ResearchCallPlan.totalMin());
        assertEquals(30, ResearchCallPlan.totalMax());
    }

    @Test
    void reviewEvidenceKeepsOnlyLowStarAndRanksProblems() {
        ReviewEvidenceModel.ReviewEvidence row = new ReviewEvidenceModel.ReviewEvidence();
        row.evidenceId = "call-1"; row.asin = "B000000001"; row.reviewId = "r-1";
        row.stars = 2; row.body = "Leaks after one week"; row.scope = "current_child";
        row.relevant = true; row.applicable = true; row.date = "2026-01-01"; row.variant = "black";
        ReviewEvidenceModel.PreparedReviews prepared = ReviewEvidenceModel.prepareLowStarReviews(List.of(row));
        assertEquals(1, prepared.retained.size());
        assertEquals("review-1", prepared.retained.get(0).key);
        row.stars = 5;
        prepared = ReviewEvidenceModel.prepareLowStarReviews(List.of(row));
        assertEquals(1, prepared.excluded.size());
        assertEquals("NOT_LOW_STAR", prepared.excluded.get(0).reason);

        ReviewEvidenceModel.ReviewEvidence low = new ReviewEvidenceModel.ReviewEvidence();
        low.evidenceId = "call-1"; low.asin = "B000000001"; low.reviewId = "r-2";
        low.stars = 1; low.body = "broken"; low.scope = "current_child"; low.relevant = true; low.applicable = true;
        prepared = ReviewEvidenceModel.prepareLowStarReviews(List.of(low));
        ReviewEvidenceModel.ReviewProblem problem = new ReviewEvidenceModel.ReviewProblem();
        problem.category = "durability"; problem.reviewKeys = List.of("review-1");
        problem.severity = 4; problem.improvementValue = 5; problem.suggestion = "reinforce seal";
        List<ReviewEvidenceModel.RankedProblem> ranked = ReviewEvidenceModel.rankReviewProblems(prepared, List.of(problem));
        assertEquals(1, ranked.get(0).count);
        assertEquals(List.of("call-1"), ranked.get(0).evidenceIds);
        assertTrue(ReviewEvidenceModel.containsReviewBody(Map.of("text", "{\"body\":\"broken\"}"), "broken"));
    }

    @Test
    void interruptedCallIsNeverReplayed() {
        ResearchRepository repository = new InMemoryResearchRepository();
        ResearchTypes.Scope scope = SCOPE;
        ResearchEngine engine = new ResearchEngine(new LeaseResearchStore(repository, scope), scope,
                Fixture.researchFixture().transport, Fixture.researchFixture().agent,
                new ResearchEngine.Options("fixture"));
        ResearchTypes.ResearchRun run = engine.create(fixtureInput());
        ResearchTypes.ResearchRun first = engine.step(run.id, null);
        assertEquals("SUCCEEDED", first.calls.get(0).status);
        ResearchRepository.ResearchRow row = repository.read(run.id, scope);
        row.run.calls.get(0).status = "IN_FLIGHT";
        repository.compareAndSet(run.id, scope, row.version, row.run, null, 0L);
        ResearchTypes.ResearchRun second = engine.step(run.id, null);
        assertEquals("OUTCOME_UNVERIFIED_NOT_REPLAYED", second.calls.get(0).errorCode);
        assertEquals("FAILED", second.calls.get(0).status);
        assertEquals(1, second.calls.size());
    }
}

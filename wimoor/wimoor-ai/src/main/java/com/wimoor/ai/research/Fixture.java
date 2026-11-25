package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 隔离测试夹具（移植自 src/lib/selection-research/fixture.ts）。
 * 只在 fixture 模式替代传输与代理；live 传输失败时绝不降级到本夹具。
 */
public final class Fixture {

    public static final class Components {
        public final AiAgent agent;
        public final ResearchTransport transport;
        public final Map<String, ResearchTypes.ToolPolicy> policies;

        public Components(AiAgent agent, ResearchTransport transport, Map<String, ResearchTypes.ToolPolicy> policies) {
            this.agent = agent;
            this.transport = transport;
            this.policies = policies;
        }
    }

    private static final List<ResearchTypes.ToolDefinition> DEFINITIONS = List.of(
            new ResearchTypes.ToolDefinition("market_find_products", ResearchSchema.parse(
                    "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"},\"marketplace\":{\"type\":\"string\"}},\"required\":[\"keyword\"]}")),
            new ResearchTypes.ToolDefinition("market_research_keyword", ResearchSchema.parse(
                    "{\"type\":\"object\",\"properties\":{\"keywords\":{\"type\":\"string\"},\"marketplace\":{\"type\":\"string\"}},\"required\":[\"keywords\"]}")),
            new ResearchTypes.ToolDefinition("market_get_reviews", ResearchSchema.parse(
                    "{\"type\":\"object\",\"properties\":{\"asin\":{\"type\":\"string\"},\"stars\":{\"type\":\"string\"}},\"required\":[\"asin\",\"stars\"]}")));

    private Fixture() {}

    public static Components researchFixture() {
        Map<String, ResearchTypes.ToolPolicy> policies = new LinkedHashMap<>();
        for (ResearchTypes.ToolDefinition tool : DEFINITIONS) {
            policies.put(tool.name, new ResearchTypes.ToolPolicy("fixture", false, 0, "credits",
                    "Isolated test fixture: no remote calls or charges", 10, List.of(1, 2, 3, 4, 5)));
        }
        ResearchTransport transport = new ResearchTransport() {
            @Override
            public List<ResearchTypes.ToolDefinition> listTools() {
                List<ResearchTypes.ToolDefinition> out = new ArrayList<>();
                for (ResearchTypes.ToolDefinition d : DEFINITIONS) out.add(ResearchJson.copy(d));
                return out;
            }

            @Override
            public JsonNode callTool(String name, JsonNode args, JsonNode expectedSchema) {
                ObjectNode out = ResearchSchema.MAPPER.createObjectNode();
                out.put("ok", true);
                out.put("source", "isolated-fixture");
                out.put("synthetic", true);
                out.put("tool", name);
                out.set("arguments", args == null ? ResearchSchema.MAPPER.createObjectNode() : args.deepCopy());
                out.put("note", "仅验证流程，不包含真实销量、搜索量、评论或税率");
                return out;
            }
        };
        AiAgent agent = context -> {
            ResearchTypes.ResearchRun run = context.run;
            List<ResearchTypes.CallRecord> calls = new ArrayList<>();
            for (ResearchTypes.CallRecord call : run.calls)
                if (call.stage == run.stage && "SUCCEEDED".equals(call.status)) calls.add(call);
            if (run.stage == 1 && calls.isEmpty()) {
                ObjectNode args = ResearchSchema.MAPPER.createObjectNode();
                args.put("keyword", run.requirement);
                args.put("marketplace", run.marketplace);
                return ResearchTypes.Action.call("market_find_products", args);
            }
            if (run.stage == 2 && calls.isEmpty()) {
                ObjectNode args = ResearchSchema.MAPPER.createObjectNode();
                args.put("keywords", run.selectedCandidateId == null ? run.requirement : run.selectedCandidateId);
                args.put("marketplace", run.marketplace);
                return ResearchTypes.Action.call("market_research_keyword", args);
            }
            if (run.stage == 3 && calls.size() < 2) {
                ObjectNode args = ResearchSchema.MAPPER.createObjectNode();
                args.put("asin", "FIXTURE-ASIN");
                args.put("stars", calls.isEmpty() ? "1,2,3" : "4,5");
                return ResearchTypes.Action.call("market_get_reviews", args);
            }
            ResearchTypes.StageResult result = new ResearchTypes.StageResult(
                    "离线流程测试阶段 " + run.stage + "，不构成市场研究结论。",
                    List.of(new ResearchTypes.Claim(
                            !calls.isEmpty() ? "已保存隔离样本响应；没有真实市场记录。" : "采购、运费、广告和费用均待真实数据验证。",
                            !calls.isEmpty() ? "observed" : "assumption",
                            calls.stream().map(c -> c.id).collect(java.util.stream.Collectors.toList()))));
            if (run.stage == 1) {
                result.candidates = new ArrayList<>();
                for (int i = 1; i <= 3; i++)
                    result.candidates.add(new ResearchTypes.Candidate("fixture-" + i, "流程测试方向 " + i + "（非市场建议）"));
            }
            if (run.stage == 4) {
                ResearchTypes.Claim tariff = new ResearchTypes.Claim("税率未核验；未设置任何历史或示例税率。",
                        "assumption", new ArrayList<>());
                tariff.topic = "tariff";
                result.claims.add(tariff);
            }
            if (run.stage == 5) result.verdict = "NEEDS_VALIDATION";
            return ResearchTypes.Action.complete(result);
        };
        return new Components(agent, transport, policies);
    }

    /** 去掉 Markdown 代码围栏后解析模型 JSON 动作。 */
    public static JsonNode parseAction(String text) {
        if (text == null) throw new ResearchError("INVALID_AGENT_JSON");
        String cleaned = text.strip().replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("(?s)\\s*```$", "");
        try {
            JsonNode parsed = ResearchSchema.MAPPER.readTree(cleaned);
            if (parsed == null || !parsed.isObject()) throw new ResearchError("INVALID_AGENT_JSON");
            return parsed;
        } catch (Exception e) {
            if (e instanceof ResearchError) throw (ResearchError) e;
            throw new ResearchError("INVALID_AGENT_JSON", e);
        }
    }
}

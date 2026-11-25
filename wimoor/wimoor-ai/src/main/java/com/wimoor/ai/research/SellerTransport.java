package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SellerSprite 只读传输与策略（移植自 src/lib/selection-research/seller-transport.ts）。
 * 使用当前用户已有的集成设置；密钥留在适配器内部，不进入 Run 或报告。
 */
public final class SellerTransport {

    public static final class SellerSpriteMcpSettings {
        public String apiKey;
        public String serverUrl;
        public boolean enabled;

        public SellerSpriteMcpSettings() {}
        public SellerSpriteMcpSettings(String apiKey, String serverUrl, boolean enabled) {
            this.apiKey = apiKey;
            this.serverUrl = serverUrl;
            this.enabled = enabled;
        }
    }

    /** 宿主现有的 SellerSprite MCP 调用实现。 */
    public interface RemoteOps {
        List<ResearchTypes.ToolDefinition> listRemoteTools();
        JsonNode callRemoteTool(String name, JsonNode args, JsonNode expectedSchema);
    }

    private static final Map<String, List<Integer>> STAGES = Map.ofEntries(
            Map.entry("market_find_products", List.of(1, 2, 5)),
            Map.entry("market_find_node", List.of(1)),
            Map.entry("market_scan_node", List.of(1, 2)),
            Map.entry("market_get_asin", List.of(1, 2, 3, 5)),
            Map.entry("market_research_keyword", List.of(1, 2, 5)),
            Map.entry("market_mine_keywords", List.of(1, 2)),
            Map.entry("market_get_reviews", List.of(3, 5)),
            Map.entry("market_list_competitors", List.of(2, 3)),
            Map.entry("market_aba_trend", List.of(1, 2)));

    private SellerTransport() {}

    /** 付费策略：每次 1 calls；首次请求需持久化的任务级人工授权。 */
    public static ResearchTypes.ToolPolicy sellerSpritePolicy(ResearchTypes.ToolDefinition tool) {
        String canonical = ResearchEngine.canonicalResearchTool(tool.name);
        if (canonical == null) return null;
        String pricing = (tool.description == null
                ? "SellerSprite runtime registered read-only tool" : tool.description)
                + " Price per request is not represented as a fixed credit amount; the first paid request requires persisted task-level human approval, and later requests may use that task authorization within its saved call limits.";
        return new ResearchTypes.ToolPolicy("sellersprite", true, 1, "calls", pricing, 3,
                STAGES.getOrDefault(canonical, List.of(1, 2, 3, 4, 5)));
    }

    public static ResearchTransport createSellerSpriteResearchTransport(SellerSpriteMcpSettings settings, RemoteOps remote) {
        return new ResearchTransport() {
            @Override
            public List<ResearchTypes.ToolDefinition> listTools() {
                List<ResearchTypes.ToolDefinition> out = new ArrayList<>();
                for (ResearchTypes.ToolDefinition tool : remote.listRemoteTools()) {
                    if (sellerSpritePolicy(tool) == null || Boolean.FALSE.equals(tool.readOnlyHint)) continue;
                    ResearchTypes.ToolDefinition safe = new ResearchTypes.ToolDefinition(tool.name, tool.inputSchema);
                    safe.description = tool.description;
                    safe.readOnlyHint = true;
                    out.add(safe);
                }
                return out;
            }

            @Override
            public JsonNode callTool(String name, JsonNode args, JsonNode expectedSchema) {
                return remote.callRemoteTool(name, args, expectedSchema);
            }
        };
    }
}

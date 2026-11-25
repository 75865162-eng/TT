package com.wimoor.ai.research;

/**
 * AI 配置来源解析与显式选择（移植自 src/lib/selection-research/ai-settings.ts）。
 * 每次运行显式选择，绝不自动跨作用域回退到别的凭证。
 */
public final class AiSettings {

    public static final class AiModelSettings {
        public String provider;
        public String model;
        public String apiKey;
        public String wireApi;
        public String endpoint;

        public AiModelSettings() {}
        public AiModelSettings(String provider, String model, String apiKey, String wireApi) {
            this.provider = provider;
            this.model = model;
            this.apiKey = apiKey;
            this.wireApi = wireApi;
        }

        public AiModelSettings(String provider, String model, String apiKey, String wireApi, String endpoint) {
            this(provider, model, apiKey, wireApi);
            this.endpoint = endpoint;
        }
    }

    public interface Adapters {
        boolean authorizeRuntime();
        AiModelSettings workspace();
        AiModelSettings runtime();
    }

    private AiSettings() {}

    public static String parseResearchAiSource(Object value) {
        if (value == null || "workspace".equals(value)) return "workspace";
        if ("server_runtime".equals(value)) return (String) value;
        throw new ResearchError("INVALID_RESEARCH_AI_SOURCE");
    }

    /** 选择服务端运行时模型前必须当前账号具备系统设置编辑权限。 */
    public static AiModelSettings selectResearchAiSettings(Object source, Adapters adapters) {
        if ("workspace".equals(parseResearchAiSource(source))) return adapters.workspace();
        if (!adapters.authorizeRuntime()) throw new ResearchError("RESEARCH_RUNTIME_AI_PERMISSION_REQUIRED");
        return adapters.runtime();
    }
}

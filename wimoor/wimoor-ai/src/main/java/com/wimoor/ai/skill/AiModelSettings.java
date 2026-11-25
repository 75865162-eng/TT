package com.wimoor.ai.skill;

/**
 * AI 模型配置（移植自条条 ERP {@code src/lib/ai-settings} 的 AiModelSettings 子集）。
 * 对话编排器与 Skill runner 共用同一份配置；apiKey 只允许服务端持有，
 * 返回给前端/工具结果时必须通过安全校验（不得泄漏）。
 */
public final class AiModelSettings {

    private String apiKey = "";
    private String model = "";
    private String provider = "";
    /** "chat_completions" 或 "responses"；等价原 wireApi 字段。 */
    private String wireApi = "chat_completions";
    private String endpoint = "";

    public AiModelSettings() {}

    public AiModelSettings(String apiKey, String model, String provider, String wireApi, String endpoint) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.provider = provider == null ? "" : provider;
        this.wireApi = wireApi == null ? "chat_completions" : wireApi;
        this.endpoint = endpoint == null ? "" : endpoint;
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "" : model; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider == null ? "" : provider; }
    public String getWireApi() { return wireApi; }
    public void setWireApi(String wireApi) { this.wireApi = wireApi == null ? "chat_completions" : wireApi; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint; }

    /** 等价原代码 settings.wireApi === "chat_completions"。 */
    public boolean isChatCompletions() { return "chat_completions".equals(wireApi); }

    /** 是否配置了可用凭据。 */
    public boolean hasApiKey() { return apiKey != null && !apiKey.trim().isEmpty(); }
}

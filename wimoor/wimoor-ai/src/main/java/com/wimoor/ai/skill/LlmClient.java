package com.wimoor.ai.skill;

/**
 * LLM 调用边界。移植说明：原 orchestrator.ts / runner.ts 直接调用
 * {@code fetchAiApi(buildAiTextEndpoint(...), ...)}；真实的 HTTP 运行时在 Java 侧抽象为接口，
 * 由部署方注入（生产可由 RestTemplate 实现），记忆体内实现用于确定性测试与离线运行。
 * 接口方法只接收系统指令与用户输入，返回模型原文（可能是 JSON action 或报告 JSON）。
 */
@FunctionalInterface
public interface LlmClient {

    /**
     * 调用模型。
     *
     * @param settings 模型配置（apiKey 绝不出现在返回内容中）
     * @param system   系统协议 / 报告约束
     * @param user     用户提示
     * @return 模型原始文本
     */
    String call(AiModelSettings settings, String system, String user);

    /** 可控的脚本化内存实现：按入队顺序返回响应并记录调用参数。 */
    final class InMemory implements LlmClient {
        private final java.util.Deque<String> responses = new java.util.ArrayDeque<>();
        private final java.util.List<CallRecord> records = new java.util.ArrayList<>();

        public InMemory(String... responses) {
            for (String r : responses) this.responses.addLast(r);
        }

        public InMemory enqueue(String response) {
            this.responses.addLast(response);
            return this;
        }

        @Override
        public String call(AiModelSettings settings, String system, String user) {
            records.add(new CallRecord(settings, system, user));
            if (responses.isEmpty()) throw new IllegalStateException("LLM_SCRIPT_EXHAUSTED");
            return responses.removeFirst();
        }

        public java.util.List<CallRecord> getRecords() { return java.util.Collections.unmodifiableList(records); }
        public int remaining() { return responses.size(); }

        /** 一次模型调用的可审计记录。 */
        public static final class CallRecord {
            private final AiModelSettings settings;
            private final String system;
            private final String user;
            CallRecord(AiModelSettings settings, String system, String user) {
                this.settings = settings; this.system = system; this.user = user;
            }
            public AiModelSettings getSettings() { return settings; }
            public String getSystem() { return system; }
            public String getUser() { return user; }
        }
    }
}

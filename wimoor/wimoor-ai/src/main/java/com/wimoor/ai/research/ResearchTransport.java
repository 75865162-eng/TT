package com.wimoor.ai.research;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 只读工具目录与调用传输抽象（对应 types.ts 的 Transport）。
 * 具体实现只负责请求构造/响应解析；阶段调度、去重、计费与授权全部留在 ResearchEngine。
 */
public interface ResearchTransport {

    List<ResearchTypes.ToolDefinition> listTools();

    JsonNode callTool(String name, JsonNode args, JsonNode expectedSchema);

    /** 只用于历史/导出：绝不发起业务请求。 */
    final class Unavailable implements ResearchTransport {
        @Override
        public List<ResearchTypes.ToolDefinition> listTools() {
            throw new ResearchError("LIVE_EXECUTION_NOT_INITIALIZED");
        }

        @Override
        public JsonNode callTool(String name, JsonNode args, JsonNode expectedSchema) {
            throw new ResearchError("LIVE_EXECUTION_NOT_INITIALIZED");
        }
    }
}

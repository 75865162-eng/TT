package com.wimoor.ai.research;

/**
 * 领域错误码载体（移植自各源 .ts 中 throw new Error("CODE") 的约定）。
 * 只有主机自有的稳定码会被持久化；provider/模型的异常正文不得进入此类。
 */
public class ResearchError extends RuntimeException {

    private final String code;

    public ResearchError(String code) {
        super(code);
        this.code = code;
    }

    public ResearchError(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 仅受信任的适配器可断言请求未发出；不得由远端 JSON 或错误文本推断。 */
    public static final class ToolNotDispatchedError extends ResearchError {
        public ToolNotDispatchedError(String code) {
            super(code);
        }
    }
}

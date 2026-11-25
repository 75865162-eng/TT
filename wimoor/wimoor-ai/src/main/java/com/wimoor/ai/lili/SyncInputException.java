package com.wimoor.ai.lili;

/** 移植自 @/lib/amazon-sync/policy 的 SyncInputError（lili 模块内共用的带状态码输入异常）。 */
public class SyncInputException extends RuntimeException {
    public final int status;
    public SyncInputException(String message, int status) { super(message); this.status = status; }
    public SyncInputException(String message) { this(message, 400); }
}

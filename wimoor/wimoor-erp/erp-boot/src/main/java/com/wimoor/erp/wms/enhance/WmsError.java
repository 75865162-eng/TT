package com.wimoor.erp.wms.enhance;

/**
 * WMS 领域异常（对应源文件 contracts.ts / rules.ts）。
 * 移植了源端 WmsError(message, status=409)、WmsInputError(status=400)、
 * WmsConflictError(status=409) 的携带 HTTP 状态语义，便于服务层精确回传错误码。
 */
public class WmsError extends RuntimeException {

    private final int status;

    public WmsError(String message) {
        this(message, 409);
    }

    public WmsError(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** 对应 rules.ts 的 WmsInputError，默认 400。 */
    public static class Input extends WmsError {
        public Input(String message) {
            super(message, 400);
        }

        public Input(String message, int status) {
            super(message, status);
        }
    }

    /** 对应 rules.ts 的 WmsConflictError，固定 409。 */
    public static class Conflict extends WmsError {
        public Conflict(String message) {
            super(message, 409);
        }
    }
}

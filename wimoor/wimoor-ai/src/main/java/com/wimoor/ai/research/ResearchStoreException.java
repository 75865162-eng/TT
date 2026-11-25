package com.wimoor.ai.research;

/** 存储层故障（锁冲突、CAS 失败、租约过期等）的统一非受检异常。 */
public class ResearchStoreException extends RuntimeException {

    public ResearchStoreException(String code) {
        super(code);
    }

    public ResearchStoreException(String code, Throwable cause) {
        super(code, cause);
    }
}

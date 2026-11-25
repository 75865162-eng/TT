package com.wimoor.ai.research;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 服务端请求身份绑定（移植自 src/lib/selection-research/request-authorization.ts）。
 * 用 WeakMap 保证只有经过 withResearchRequestAuthorization 的请求对象才持有身份；
 * 请求头、Cookie 或克隆的 Request 不能绑定该身份。
 */
public final class RequestAuthorization {

    /** 只暴露 URL 的最小请求视图，避免依赖具体 HTTP 框架。 */
    public interface RequestView {
        String url();
    }

    /** 权限结果由宿主提供；ok=false 时拒绝执行。 */
    public interface PermissionResult {
        boolean ok();
    }

    public static final class Authorization {
        public final String credentialId;
        public final Authorize authorize;

        public interface Authorize {
            PermissionResult apply(String moduleId, String action, RequestView request);
        }

        public Authorization(String credentialId, Authorize authorize) {
            this.credentialId = credentialId;
            this.authorize = authorize;
        }
    }

    private static final Pattern PATH = Pattern.compile("^/api/selection-research(?:/[A-Za-z0-9_-]+)?$");
    private static final Map<RequestView, Authorization> VERIFIED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RequestAuthorization() {}

    public static Authorization researchRequestAuthorization(RequestView request) {
        return VERIFIED.get(request);
    }

    /** 路径白名单 + 一次性绑定；无论执行成功或失败都清除绑定。 */
    public static <T> T withResearchRequestAuthorization(RequestView request, Authorization authorization, Supplier<T> execute) {
        if (!PATH.matcher(pathOf(request.url())).matches() || VERIFIED.containsKey(request))
            throw new ResearchError("RESEARCH_SCOPE_DENIED");
        VERIFIED.put(request, authorization);
        try {
            return execute.get();
        } finally {
            VERIFIED.remove(request);
        }
    }

    private static String pathOf(String url) {
        if (url == null) return "";
        int start = url.indexOf('/', url.indexOf("//") >= 0 ? url.indexOf("//") + 2 : 0);
        if (start < 0) return "/";
        int end = url.indexOf('?', start);
        if (end < 0) end = url.indexOf('#', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }
}

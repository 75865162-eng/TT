package com.wimoor.ai.persistence;

import com.wimoor.ai.research.LiliTransport.HttpFetcher;
import com.wimoor.ai.research.LiliTransport.HttpRequest;
import com.wimoor.ai.research.LiliTransport.HttpResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 真实 HTTP fetcher：供 LiliTransport / ReportingV*Transport 注入，替换测试用假 fetcher。
 * 安全约束：端点必须在白名单内（防止 SSRF）、超时、响应体上限。
 */
public final class JdkHttpFetcher implements HttpFetcher {

    private final HttpClient http;
    private final Duration timeout;
    private final int maxBodyBytes;
    private final Set<String> allowedHosts;

    public JdkHttpFetcher(Duration timeout, int maxBodyBytes, Set<String> allowedHosts) {
        this.timeout = timeout;
        this.maxBodyBytes = maxBodyBytes;
        this.allowedHosts = allowedHosts == null ? new HashSet<>() : new HashSet<>(allowedHosts);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public HttpResponse fetch(HttpRequest request) {
        try {
            URI uri = URI.create(request.url);
            String host = uri.getHost();
            if (host == null || (!allowedHosts.isEmpty() && !allowedHosts.contains(host))) {
                throw new SecurityException("ENDPOINT_NOT_ALLOWED: " + host);
            }
            Builder b = java.net.http.HttpRequest.newBuilder(uri).timeout(timeout);
            for (Map.Entry<String, String> h : request.headers.entrySet()) b.header(h.getKey(), h.getValue());
            String method = request.method == null ? "GET" : request.method.toUpperCase();
            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                b.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(request.body == null ? "" : request.body));
            } else {
                b.method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
            }
            java.net.http.HttpResponse<String> resp = http.send(b.build(), BodyHandlers.ofString());
            String body = resp.body();
            if (body != null && body.length() > maxBodyBytes) throw new IllegalStateException("RESPONSE_TOO_LARGE");
            HttpResponse out = new HttpResponse();
            out.status = resp.statusCode();
            out.body = body;
            resp.headers().map().forEach((k, v) -> { if (!v.isEmpty()) out.headers.put(k, v.get(0)); });
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTTP_IO_ERROR", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP_INTERRUPTED", e);
        }
    }
}

package com.wimoor.common.service.impl;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSONObject;
import com.wimoor.common.result.Result;
import com.wimoor.util.SpringUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * 调度任务统一 HTTP 调用工具。
 *
 * <p>后台拉取端点（订单/库存/报告/广告等）都在网关鉴权之后，必须带 jsessionid，
 * 否则返回 403 token已被禁止访问。原 QuartzTaskFactory / SysTaskController.runApi
 * 直接用 RestTemplate 裸调，定时任务无人登录时必然 403，导致所有调度任务空跑。
 *
 * <p>本工具在每次调用前用内置账号登录本机网关拿 jsessionid，再带 token 调目标端点。
 * 登录只访问 127.0.0.1 本机网关（纯内网），数据链路本身仍直连亚马逊官方端点，
 * 不经任何第三方中转。token 缓存 30 分钟，过期自动重新登录。
 *
 * <p>配置（带默认值，可不配）：
 * <ul>
 *   <li>scheduler.auth.gateway 默认 http://127.0.0.1:8080</li>
 *   <li>scheduler.auth.account 默认 1</li>
 *   <li>scheduler.auth.password 默认 1</li>
 * </ul>
 */
@Slf4j
@Component
public class SchedulerApiUtil {

    @Value("${scheduler.auth.gateway:http://127.0.0.1:8080}")
    private String gateway;

    @Value("${scheduler.auth.account:1}")
    private String account;

    @Value("${scheduler.auth.password:1}")
    private String password;

    private static final long TOKEN_TTL_MS = 30L * 60 * 1000;

    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    /**
     * 带登录态 GET 调用一个后台端点。
     *
     * @param path 完整 URL（经网关，如 http://127.0.0.1:8080/amazon/api/v1/report/processReport），
     *             或以 / 开头的网关内路径（如 /amazon/api/v1/report/processReport）
     * @return 端点返回的 Result；调用失败返回 null（不抛异常，避免打断 Quartz 调度）
     */
    public Result<?> get(String path) {
        RestTemplate restTemplate = SpringUtil.getBean("restTemplateApi");
        String url = path.startsWith("http://") || path.startsWith("https://") ? path : gateway + path;
        try {
            String token = getToken(restTemplate);
            HttpHeaders headers = new HttpHeaders();
            headers.set("jsessionid", token);
            headers.set("token", token);
            HttpEntity<Void> entity = new HttpEntity<Void>(headers);
            ResponseEntity<Result> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Result.class);
            Result<?> result = resp.getBody();
            if (result != null) {
                log.info("[scheduler] GET {} -> code={}", url, result.getCode());
            }
            return result;
        } catch (Exception e) {
            // 若 token 失效（如被登出），强制刷新后重试一次
            cachedToken = null;
            try {
                String token = getToken(restTemplate);
                HttpHeaders headers = new HttpHeaders();
                headers.set("jsessionid", token);
                headers.set("token", token);
                HttpEntity<Void> entity = new HttpEntity<Void>(headers);
                ResponseEntity<Result> resp = restTemplate.exchange(url, HttpMethod.GET, entity, Result.class);
                Result<?> result = resp.getBody();
                if (result != null) {
                    log.info("[scheduler] GET(retry) {} -> code={}", url, result.getCode());
                }
                return result;
            } catch (Exception retryEx) {
                log.warn("[scheduler] GET {} failed: {}", url, retryEx.getMessage());
                return null;
            }
        }
    }

    /** 取有效 token，缓存 30 分钟 */
    private String getToken(RestTemplate restTemplate) {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAt) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpireAt) {
                return cachedToken;
            }
            String loginUrl = gateway + "/admin/api/v1/auth/login";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = new HashMap<String, String>();
            body.put("account", account);
            body.put("password", password);
            HttpEntity<Map<String, String>> req = new HttpEntity<Map<String, String>>(body, headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(loginUrl, req, String.class);
            JSONObject json = JSONObject.parseObject(resp.getBody());
            JSONObject data = json == null ? null : json.getJSONObject("data");
            if (data == null || data.getString("session") == null) {
                throw new IllegalStateException("调度登录失败: " + (resp.getBody() == null ? "empty" : resp.getBody().substring(0, Math.min(200, resp.getBody().length()))));
            }
            cachedToken = data.getString("session");
            tokenExpireAt = System.currentTimeMillis() + TOKEN_TTL_MS;
            log.info("[scheduler] login ok, token acquired at {}", new Date());
            return cachedToken;
        }
    }
}

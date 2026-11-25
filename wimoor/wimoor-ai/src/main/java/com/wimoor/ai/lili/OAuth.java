package com.wimoor.ai.lili;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import com.wimoor.ai.lili.Ports.HttpTransport;

/**
 * 移植自 src/lib/lili/oauth.ts：Lili 公共原生客户端的 OAuth 状态机。
 * origin 校验、redirect_uri 白名单、PKCE S256、code/refresh 单次使用、
 * refresh 重放撤销整台设备；HTTP/持久化通过端口注入，业务规则保持纯 Java。
 */
public final class OAuth {

    private OAuth() {}

    /** URLSearchParams 的等价物，保留重复键检测。 */
    public static final class Params {
        private final Map<String, List<String>> values = new LinkedHashMap<>();
        public static Params parse(String query) {
            Params p = new Params();
            if (query == null || query.isEmpty()) return p;
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                String rawKey = idx < 0 ? pair : pair.substring(0, idx);
                String rawValue = idx < 0 ? "" : pair.substring(idx + 1);
                p.add(decode(rawKey), decode(rawValue));
            }
            return p;
        }
        private static String decode(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
        public void add(String key, String value) { values.computeIfAbsent(key, k -> new ArrayList<>()).add(value); }
        public void set(String key, String value) { values.put(key, new ArrayList<>(List.of(value))); }
        public String get(String key) {
            List<String> v = values.get(key);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
        public boolean has(String key) { return values.containsKey(key); }
        /** [keys()].some(k => getAll(k).length !== 1) */
        public boolean hasDuplicates() {
            for (List<String> v : values.values()) if (v.size() != 1) return true;
            return false;
        }
        public String encode() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : values.entrySet()) {
                for (String v : e.getValue()) {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(enc(e.getKey())).append('=').append(enc(v));
                }
            }
            return sb.toString();
        }
        private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    }

    /** OAuthHttpClient：token/刷新端点调用的 HTTP 抽象（宿主可提供真实实现）。 */
    public interface OAuthHttpClient extends HttpTransport { }

    public static final class OAuthClient {
        public String id;
        public List<String> redirectUris;
    }

    public interface OAuthClientStore {
        void create(OAuthClient client);
        OAuthClient findById(String id);
    }

    public static final class Grant {
        public String id, clientId, redirectUri, challenge, resource, deviceId, codeHash;
        public Long codeExpiresAt, codeUsedAt, expiresAt, revokedAt;
    }

    public interface OAuthGrantStore {
        void create(Grant grant);
        void update(Grant grant);
        Grant findById(String id);
        Grant findByCodeHash(String hash);
        /** revokeLiliToken 的条件查找：grantId 或 deviceId 二选一，另一个为 null。 */
        Grant findForRevocation(String clientId, String grantId, String deviceId);
    }

    public static final class Refresh {
        public String hash, grantId;
        public Long consumedAt;
    }

    public interface OAuthRefreshStore {
        void create(Refresh refresh);
        void update(Refresh refresh);
        Refresh findByHash(String hash);
    }

    public interface OAuthDeviceStore {
        DevicePairing.Credential findById(String id);
        DevicePairing.Credential findByTokenHash(String hash);
        void update(DevicePairing.Credential device);
    }

    public interface DeviceValidator {
        void validate(String deviceId, boolean strict);
    }

    /** authorizeLili 对 exchangePairing 的抽象。 */
    public interface PairingExchanger {
        DevicePairing.ExchangeResult exchange(String pairingId, String code, String deviceName,
                                              DevicePairing.PairedHook hook);
    }

    /** token 交换端口：默认 LocalTokenIssuer 完成全部本地状态迁移，HTTP 实现可替换。 */
    public interface TokenIssuer {
        Map<String, Object> issue(String grantType, String presented, Params params);
    }

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Set<String> GRANT_TYPES = Set.of("authorization_code", "refresh_token");

    private static boolean loopback(String host) { return host != null && LOOPBACK.contains(host); }

    private static SyncInputException oauthError(String message) { return new SyncInputException(message, 503); }
    private static SyncInputException invalid() { return new SyncInputException("invalid_grant", 400); }

    /** liliOAuthOrigin：永不从 Host/Forwarded 头推导生产 issuer。 */
    public static String liliOAuthOrigin(String requestUrl, String configuredOrigin) {
        String raw = configuredOrigin != null && !configuredOrigin.isEmpty() ? configuredOrigin : requestUrl;
        URI url;
        try { url = URI.create(raw); }
        catch (IllegalArgumentException e) { throw oauthError("OAuth requires HTTPS or loopback."); }
        String scheme = url.getScheme();
        if (url.getUserInfo() != null || !("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback(url.getHost()))))
            throw oauthError("OAuth requires HTTPS or loopback.");
        if (configuredOrigin == null || configuredOrigin.isEmpty()) {
            if (!loopback(url.getHost())) throw oauthError("LILI_PUBLIC_ORIGIN is required.");
        } else if ((url.getPath() != null && !url.getPath().isEmpty() && !"/".equals(url.getPath()))
            || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw oauthError("Invalid OAuth origin.");
        }
        return scheme + "://" + url.getAuthority();
    }

    public static Map<String, Object> oauthMetadata(String origin) {
        String base = origin + "/api/lili/oauth";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", origin);
        m.put("authorization_endpoint", base + "/authorize");
        m.put("token_endpoint", base + "/token");
        m.put("registration_endpoint", base + "/register");
        m.put("revocation_endpoint", base + "/revoke");
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("none"));
        m.put("scopes_supported", List.of("lili"));
        return m;
    }

    /** validRedirect：https 或 loopback http，禁止凭证与 fragment。 */
    public static boolean validRedirect(String value) {
        if (value == null || value.length() > 2048) return false;
        try {
            URI u = URI.create(value);
            String scheme = u.getScheme();
            return u.getUserInfo() == null && u.getRawFragment() == null
                && ("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback(u.getHost())));
        } catch (IllegalArgumentException e) { return false; }
    }

    /** registerLiliClient：仅公共原生客户端，不抓取客户端提供的 URL。 */
    public static Map<String, Object> registerLiliClient(Map<String, Object> body, OAuthClientStore store) {
        List<Object> uris = JsonSupport.arr(body.get("redirect_uris"));
        Object tam = body.get("token_endpoint_auth_method");
        Object grants = body.get("grant_types");
        Object responses = body.get("response_types");
        boolean bad = uris == null || uris.isEmpty() || uris.size() > 5;
        if (!bad) for (Object u : uris) if (!validRedirect(u instanceof String ? (String) u : null)) { bad = true; break; }
        if (tam != null && !"none".equals(tam)) bad = true;
        if (grants != null) {
            List<Object> list = JsonSupport.arr(grants);
            if (list == null) bad = true;
            else for (Object g : list) if (!GRANT_TYPES.contains(String.valueOf(g))) { bad = true; break; }
        }
        if (responses != null) {
            List<Object> list = JsonSupport.arr(responses);
            if (list == null) bad = true;
            else for (Object r : list) if (!"code".equals(r)) { bad = true; break; }
        }
        if (bad) throw new SyncInputException("invalid_client_metadata", 400);
        Set<String> dedup = new LinkedHashSet<>();
        for (Object u : uris) dedup.add((String) u);
        OAuthClient client = new OAuthClient();
        client.id = CryptoSupport.uuid();
        client.redirectUris = new ArrayList<>(dedup);
        store.create(client);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", client.id);
        out.put("redirect_uris", client.redirectUris);
        out.put("token_endpoint_auth_method", "none");
        out.put("grant_types", List.of("authorization_code", "refresh_token"));
        out.put("response_types", List.of("code"));
        return out;
    }

    public static final class AuthorizationFields {
        final String clientId, redirectUri, challenge, resource, state;
        AuthorizationFields(String clientId, String redirectUri, String challenge, String resource, String state) {
            this.clientId = clientId; this.redirectUri = redirectUri; this.challenge = challenge;
            this.resource = resource; this.state = state;
        }
    }

    /** validateAuthorization：逐字段严格校验，任何参数重复即拒绝。 */
    public static AuthorizationFields validateAuthorization(Params params, String requestUrl,
                                                            String configuredOrigin, OAuthClientStore store) {
        if (params.hasDuplicates()) throw new SyncInputException("invalid_request", 400);
        String clientId = nullToEmpty(params.get("client_id"));
        String redirectUri = nullToEmpty(params.get("redirect_uri"));
        OAuthClient client = store.findById(clientId);
        if (client == null || !client.redirectUris.contains(redirectUri))
            throw new SyncInputException("invalid_redirect_uri", 400);
        String challenge = nullToEmpty(params.get("code_challenge"));
        String resource = nullToEmpty(params.get("resource"));
        String state = params.get("state");
        boolean bad = !"code".equals(params.get("response_type"))
            || !"S256".equals(params.get("code_challenge_method"))
            || !CryptoSupport.isToken43(challenge)
            || !(liliOAuthOrigin(requestUrl, configuredOrigin) + "/api/lili/mcp").equals(resource)
            || (params.has("scope") && !"lili".equals(params.get("scope")))
            || (state != null && state.length() > 2048);
        if (bad) throw new SyncInputException("invalid_request", 400);
        return new AuthorizationFields(clientId, redirectUri, challenge, resource, state);
    }

    /** authorizeLili：配对成功后签发一次性 code，重定向回客户端并原样回显 state。 */
    public static String authorizeLili(Params params, String requestUrl, String configuredOrigin,
                                       String pairingId, String pairingCode,
                                       OAuthClientStore clientStore, OAuthGrantStore grantStore,
                                       PairingExchanger exchanger, long now) {
        AuthorizationFields fields = validateAuthorization(params, requestUrl, configuredOrigin, clientStore);
        String code = CryptoSupport.randomToken();
        exchanger.exchange(pairingId, pairingCode, "Codex OAuth", (store, device) -> {
            Grant grant = new Grant();
            grant.id = CryptoSupport.uuid();
            grant.clientId = fields.clientId;
            grant.redirectUri = fields.redirectUri;
            grant.challenge = fields.challenge;
            grant.resource = fields.resource;
            grant.deviceId = device.id;
            grant.codeHash = CryptoSupport.sha256Hex(code);
            grant.codeExpiresAt = now + 60_000L;
            grant.expiresAt = device.expiresAt;
            grantStore.create(grant);
            // 临时 pairing bearer 永不披露、永不可使用。
            device.expiresAt = now;
            store.update(device);
        });
        URI redirect = URI.create(fields.redirectUri);
        Params redirected = Params.parse(redirect.getRawQuery());
        redirected.set("code", code);
        if (params.has("state")) redirected.set("state", params.get("state"));
        String query = redirected.encode();
        String base = redirect.toString();
        int q = base.indexOf('?');
        return (q < 0 ? base : base.substring(0, q)) + (query.isEmpty() ? "" : "?" + query);
    }

    /** exchangeLiliToken：code/refresh 交换，PKCE 校验与重放检测。 */
    public static Map<String, Object> exchangeLiliToken(Params params, String requestUrl, String configuredOrigin,
                                                        OAuthGrantStore grants, OAuthRefreshStore refreshes,
                                                        OAuthDeviceStore devices, Ports.AuditSink audit,
                                                        TokenIssuer issuer, long now) {
        if (params.hasDuplicates()) throw invalid();
        String kind = params.get("grant_type");
        String clientId = nullToEmpty(params.get("client_id"));
        if (!GRANT_TYPES.contains(kind == null ? "" : kind)) throw new SyncInputException("unsupported_grant_type", 400);
        boolean isCode = "authorization_code".equals(kind);
        String presented = nullToEmpty(params.get(isCode ? "code" : "refresh_token"));
        if (!CryptoSupport.isToken43(presented)) throw invalid();
        Refresh refresh = isCode ? null : refreshes.findByHash(CryptoSupport.sha256Hex(presented));
        Grant found = isCode ? grants.findByCodeHash(CryptoSupport.sha256Hex(presented))
            : refresh == null ? null : grants.findById(refresh.grantId);
        String resource = liliOAuthOrigin(requestUrl, configuredOrigin) + "/api/lili/mcp";
        if (found == null || !clientId.equals(found.clientId) || !resource.equals(found.resource)
            || (params.has("resource") && !resource.equals(params.get("resource")))) throw invalid();
        final Grant target = found;
        final String grantLock = target.id;
        synchronized (grantLock.intern()) {
            Grant grant = grants.findById(target.id);
            if (grant == null || grant.revokedAt != null || (grant.expiresAt != null && grant.expiresAt <= now)) throw invalid();
            if (isCode) {
                String verifier = nullToEmpty(params.get("code_verifier"));
                boolean bad = grant.codeUsedAt != null
                    || (grant.codeExpiresAt != null && grant.codeExpiresAt <= now)
                    || !nullToEmpty(params.get("redirect_uri")).equals(grant.redirectUri)
                    || !CryptoSupport.isPkceVerifier(verifier)
                    || !CryptoSupport.sha256Base64Url(verifier).equals(grant.challenge);
                if (bad) throw invalid();
                grant.codeUsedAt = now;
                grants.update(grant);
            } else {
                Refresh current = refreshes.findByHash(CryptoSupport.sha256Hex(presented));
                if (current == null || current.consumedAt != null) {
                    grant.revokedAt = now;
                    grants.update(grant);
                    DevicePairing.Credential device = devices.findById(grant.deviceId);
                    if (device != null) {
                        device.status = "REVOKED";
                        device.revision++;
                        devices.update(device);
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("workspaceId", device.workspaceId);
                        meta.put("clientId", clientId);
                        audit.record(device.organizationId, device.userId, "lili.oauth.refresh.replay_denied",
                            "AmazonMcpCredential", device.id, meta);
                    }
                    throw invalid();
                }
                current.consumedAt = now;
                refreshes.update(current);
            }
            DevicePairing.Credential device = devices.findById(grant.deviceId);
            if (device == null) throw invalid();
            if (issuer != null) {
                Map<String, Object> external = issuer.issue(kind, presented, params);
                if (external != null) return external;
            }
            String access = CryptoSupport.accessToken();
            String nextRefresh = CryptoSupport.randomToken();
            long expiresAt = Math.min(grant.expiresAt == null ? now : grant.expiresAt, now + 3_600_000L);
            device.tokenHash = CryptoSupport.sha256Hex(access);
            device.tokenPrefix = CryptoSupport.tokenPrefix(access);
            device.expiresAt = expiresAt;
            device.revision++;
            devices.update(device);
            Refresh next = new Refresh();
            next.hash = CryptoSupport.sha256Hex(nextRefresh);
            next.grantId = grant.id;
            refreshes.create(next);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("workspaceId", device.workspaceId);
            meta.put("clientId", clientId);
            audit.record(device.organizationId, device.userId,
                "lili.oauth.token." + (isCode ? "issued" : "refreshed"), "AmazonMcpCredential", grant.deviceId, meta);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("access_token", access);
            out.put("token_type", "Bearer");
            out.put("expires_in", Math.max(0, (expiresAt - now) / 1000));
            out.put("refresh_token", nextRefresh);
            out.put("scope", "lili");
            return out;
        }
    }

    /** revokeLiliToken：token 超过 200 字符静默忽略。 */
    public static void revokeLiliToken(Params params, OAuthGrantStore grants, OAuthRefreshStore refreshes,
                                       OAuthDeviceStore devices, Ports.AuditSink audit, long now) {
        String value = nullToEmpty(params.get("token"));
        String clientId = nullToEmpty(params.get("client_id"));
        if (value.length() > 200) return;
        Refresh refresh = refreshes.findByHash(CryptoSupport.sha256Hex(value));
        DevicePairing.Credential device = value.startsWith("amcp_") ? devices.findByTokenHash(CryptoSupport.sha256Hex(value)) : null;
        Grant grant = refresh != null ? grants.findForRevocation(clientId, refresh.grantId, null)
            : device != null ? grants.findForRevocation(clientId, null, device.id)
            : grants.findForRevocation(clientId, "", "");
        if (grant == null) return;
        synchronized (grant.id.intern()) {
            grant.revokedAt = now;
            grants.update(grant);
            DevicePairing.Credential row = devices.findById(grant.deviceId);
            if (row != null) {
                row.status = "REVOKED";
                row.revision++;
                devices.update(row);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("workspaceId", row.workspaceId);
                meta.put("clientId", clientId);
                audit.record(row.organizationId, row.userId, "lili.oauth.token.revoked", "AmazonMcpCredential", row.id, meta);
            }
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}

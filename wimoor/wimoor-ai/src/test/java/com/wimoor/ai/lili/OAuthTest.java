package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wimoor.ai.lili.DevicePairing.Credential;
import com.wimoor.ai.lili.OAuth.AuthorizationFields;
import com.wimoor.ai.lili.OAuth.Grant;
import com.wimoor.ai.lili.OAuth.OAuthClient;
import com.wimoor.ai.lili.OAuth.OAuthClientStore;
import com.wimoor.ai.lili.OAuth.OAuthDeviceStore;
import com.wimoor.ai.lili.OAuth.OAuthGrantStore;
import com.wimoor.ai.lili.OAuth.OAuthRefreshStore;
import com.wimoor.ai.lili.OAuth.Params;
import com.wimoor.ai.lili.OAuth.Refresh;

/** OAuth 状态机、PKCE 与 refresh 重放保护的确定性测试（移植自 oauth.ts）。 */
class OAuthTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String ORIGIN = "https://erp.example.com";
    private static final String REDIRECT = "https://client.example/cb";
    private static final String RESOURCE = ORIGIN + "/api/lili/mcp";

    static class Clients implements OAuthClientStore {
        final Map<String, OAuthClient> rows = new ConcurrentHashMap<>();
        @Override public void create(OAuthClient c) { rows.put(c.id, c); }
        @Override public OAuthClient findById(String id) { return rows.get(id); }
    }
    static class Grants implements OAuthGrantStore {
        final Map<String, Grant> rows = new ConcurrentHashMap<>();
        @Override public void create(Grant g) { rows.put(g.id, g); }
        @Override public void update(Grant g) { rows.put(g.id, g); }
        @Override public Grant findById(String id) { return rows.get(id); }
        @Override public Grant findByCodeHash(String hash) {
            return rows.values().stream().filter(g -> hash.equals(g.codeHash)).findFirst().orElse(null);
        }
        @Override public Grant findForRevocation(String clientId, String grantId, String deviceId) {
            for (Grant g : rows.values()) {
                if (!clientId.equals(g.clientId)) continue;
                if (grantId != null && grantId.equals(g.id)) return g;
                if (deviceId != null && deviceId.equals(g.deviceId)) return g;
            }
            return null;
        }
    }
    static class Refreshes implements OAuthRefreshStore {
        final Map<String, Refresh> rows = new ConcurrentHashMap<>();
        @Override public void create(Refresh r) { rows.put(r.hash, r); }
        @Override public void update(Refresh r) { rows.put(r.hash, r); }
        @Override public Refresh findByHash(String hash) { return rows.get(hash); }
    }
    static class Devices implements OAuthDeviceStore {
        final Map<String, Credential> rows = new ConcurrentHashMap<>();
        @Override public Credential findById(String id) { return rows.get(id); }
        @Override public Credential findByTokenHash(String hash) {
            return rows.values().stream().filter(c -> hash.equals(c.tokenHash)).findFirst().orElse(null);
        }
        @Override public void update(Credential c) { rows.put(c.id, c); }
        void create(Credential c) { rows.put(c.id, c); }
    }

    private Clients clients;
    private Grants grants;
    private Refreshes refreshes;
    private Devices devices;
    private String clientId;
    private String verifier;
    private String challenge;

    @BeforeEach
    void setUp() {
        clients = new Clients();
        grants = new Grants();
        refreshes = new Refreshes();
        devices = new Devices();
        OAuthClient client = new OAuthClient();
        client.id = CryptoSupport.uuid();
        client.redirectUris = List.of(REDIRECT);
        clients.create(client);
        clientId = client.id;
        verifier = CryptoSupport.randomToken();
        challenge = CryptoSupport.sha256Base64Url(verifier);
    }

    private OAuth.PairingExchanger exchanger() {
        return (pairingId, code, deviceName, hook) -> {
            Credential device = new Credential();
            device.id = "device-1";
            device.organizationId = "org-1";
            device.workspaceId = "ws-1";
            device.userId = "user-1";
            device.expiresAt = NOW + 3_600_000L;
            devices.create(device);
            try { hook.onPaired(new DevicePairing.PairingStore() {
                @Override public Credential findById(String id) { return devices.findById(id); }
                @Override public Credential findActiveParent(String id, DevicePairing.Scope s, String u, long n) { return null; }
                @Override public void create(Credential c) { devices.create(c); }
                @Override public void update(Credential c) { devices.update(c); }
                @Override public List<Credential> list(DevicePairing.Scope s, String u, String p) { return new ArrayList<>(); }
                @Override public void audit(String o, String u, String a, String t, String id, Map<String, Object> m) { }
            }, device); } catch (Exception e) { throw new IllegalStateException(e); }
            return new DevicePairing.ExchangeResult(device, "amcp_placeholder");
        };
    }

    private Params authorizationParams() {
        Params p = new Params();
        p.set("client_id", clientId);
        p.set("redirect_uri", REDIRECT);
        p.set("response_type", "code");
        p.set("code_challenge_method", "S256");
        p.set("code_challenge", challenge);
        p.set("resource", RESOURCE);
        p.set("state", "state-123");
        return p;
    }

    private String authorizeAndGetCode() {
        String redirect = OAuth.authorizeLili(authorizationParams(), ORIGIN, ORIGIN, "pairing-id", "pairing-code",
            clients, grants, exchanger(), NOW);
        int idx = redirect.indexOf("code=");
        assertTrue(idx > 0);
        assertTrue(redirect.contains("state=state-123"));
        return URLDecoder.decode(redirect.substring(idx + 5, redirect.indexOf('&', idx) < 0 ? redirect.length() : redirect.indexOf('&', idx)), StandardCharsets.UTF_8);
    }

    @Test
    void originValidationRequiresHttpsOrLoopback() {
        assertEquals("http://localhost:3000", OAuth.liliOAuthOrigin("http://localhost:3000/v2/skills", null));
        assertThrows(SyncInputException.class, () -> OAuth.liliOAuthOrigin("https://erp.example.com/x", null));
    }

    @Test
    void configuredOriginMustBeClean() {
        assertEquals(ORIGIN, OAuth.liliOAuthOrigin("http://ignored.example", ORIGIN + "/"));
        assertThrows(SyncInputException.class, () -> OAuth.liliOAuthOrigin("https://a.example/x", null));
        assertThrows(SyncInputException.class, () -> OAuth.liliOAuthOrigin("https://a.example/x", ORIGIN + "/path"));
        assertThrows(SyncInputException.class, () -> OAuth.liliOAuthOrigin("http://remote.example", null));
    }

    @Test
    void redirectValidationAndMetadata() {
        assertTrue(OAuth.validRedirect(REDIRECT));
        assertTrue(OAuth.validRedirect("http://127.0.0.1:3000/cb"));
        assertFalse(OAuth.validRedirect("http://remote.example/cb"));
        assertFalse(OAuth.validRedirect("https://u:p@client.example/cb#frag"));
        Map<String, Object> meta = OAuth.oauthMetadata(ORIGIN);
        assertEquals(ORIGIN, meta.get("issuer"));
        assertEquals(ORIGIN + "/api/lili/oauth/token", meta.get("token_endpoint"));
        assertEquals(List.of("S256"), meta.get("code_challenge_methods_supported"));
        assertEquals(List.of("none"), meta.get("token_endpoint_auth_methods_supported"));
    }

    @Test
    void registerClientRejectsInvalidMetadataAndDeduplicatesUris() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("redirect_uris", List.of(REDIRECT, REDIRECT, "http://localhost:3000/cb"));
        Map<String, Object> registered = OAuth.registerLiliClient(body, clients);
        assertEquals(2, ((List<?>) registered.get("redirect_uris")).size());
        body.put("token_endpoint_auth_method", "client_secret_basic");
        assertThrows(SyncInputException.class, () -> OAuth.registerLiliClient(body, clients));
    }

    @Test
    void validateAuthorizationEnforcesPkceResourceAndState() {
        AuthorizationFields fields = OAuth.validateAuthorization(authorizationParams(), ORIGIN, ORIGIN, clients);
        assertEquals(clientId, fields.clientId);
        assertEquals(RESOURCE, fields.resource);
        assertEquals("state-123", fields.state);
        Params bad = authorizationParams();
        bad.set("code_challenge", "short");
        assertThrows(SyncInputException.class, () -> OAuth.validateAuthorization(bad, ORIGIN, ORIGIN, clients));
        Params dup = authorizationParams();
        dup.add("state", "another");
        assertThrows(SyncInputException.class, () -> OAuth.validateAuthorization(dup, ORIGIN, ORIGIN, clients));
        Params longState = authorizationParams();
        longState.set("state", "x".repeat(2049));
        assertThrows(SyncInputException.class, () -> OAuth.validateAuthorization(longState, ORIGIN, ORIGIN, clients));
    }

    @Test
    void authorizationCodeExchangeUsesPkceAndIssuesTokens() {
        String code = authorizeAndGetCode();
        Params params = new Params();
        params.set("grant_type", "authorization_code");
        params.set("client_id", clientId);
        params.set("code", code);
        params.set("code_verifier", verifier);
        params.set("redirect_uri", REDIRECT);
        params.set("resource", RESOURCE);
        Map<String, Object> token = OAuth.exchangeLiliToken(params, ORIGIN, ORIGIN, grants, refreshes, devices,
            Ports.NOOP_AUDIT, null, NOW);
        assertTrue(((String) token.get("access_token")).startsWith("amcp_"));
        assertEquals("Bearer", token.get("token_type"));
        assertEquals("lili", token.get("scope"));
        assertEquals(3600L, token.get("expires_in"));
        assertTrue(CryptoSupport.isToken43((String) token.get("refresh_token")));
        assertEquals(CryptoSupport.sha256Hex((String) token.get("access_token")), devices.findById("device-1").tokenHash);
    }

    @Test
    void codeReplayAndWrongVerifierAreRejected() {
        String code = authorizeAndGetCode();
        Params params = new Params();
        params.set("grant_type", "authorization_code");
        params.set("client_id", clientId);
        params.set("code", code);
        params.set("code_verifier", CryptoSupport.randomToken());
        params.set("redirect_uri", REDIRECT);
        assertThrows(SyncInputException.class, () -> OAuth.exchangeLiliToken(params, ORIGIN, ORIGIN,
            grants, refreshes, devices, Ports.NOOP_AUDIT, null, NOW));
    }

    @Test
    void refreshReplayRevokesDevice() {
        String code = authorizeAndGetCode();
        Params codeParams = new Params();
        codeParams.set("grant_type", "authorization_code");
        codeParams.set("client_id", clientId);
        codeParams.set("code", code);
        codeParams.set("code_verifier", verifier);
        codeParams.set("redirect_uri", REDIRECT);
        Map<String, Object> first = OAuth.exchangeLiliToken(codeParams, ORIGIN, ORIGIN, grants, refreshes, devices,
            Ports.NOOP_AUDIT, null, NOW);
        String refreshToken = (String) first.get("refresh_token");
        Params refreshParams = new Params();
        refreshParams.set("grant_type", "refresh_token");
        refreshParams.set("client_id", clientId);
        refreshParams.set("refresh_token", refreshToken);
        Map<String, Object> second = OAuth.exchangeLiliToken(refreshParams, ORIGIN, ORIGIN, grants, refreshes, devices,
            Ports.NOOP_AUDIT, null, NOW + 1000);
        assertNotEquals(first.get("access_token"), second.get("access_token"));
        SyncInputException ex = assertThrows(SyncInputException.class, () -> OAuth.exchangeLiliToken(refreshParams,
            ORIGIN, ORIGIN, grants, refreshes, devices, Ports.NOOP_AUDIT, null, NOW + 2000));
        assertEquals("invalid_grant", ex.getMessage());
        assertEquals("REVOKED", devices.findById("device-1").status);
    }

    @Test
    void revokeTokenMarksGrantAndDeviceRevoked() {
        String code = authorizeAndGetCode();
        Params codeParams = new Params();
        codeParams.set("grant_type", "authorization_code");
        codeParams.set("client_id", clientId);
        codeParams.set("code", code);
        codeParams.set("code_verifier", verifier);
        codeParams.set("redirect_uri", REDIRECT);
        Map<String, Object> token = OAuth.exchangeLiliToken(codeParams, ORIGIN, ORIGIN, grants, refreshes, devices,
            Ports.NOOP_AUDIT, null, NOW);
        Params revoke = new Params();
        revoke.set("token", (String) token.get("access_token"));
        revoke.set("client_id", clientId);
        OAuth.revokeLiliToken(revoke, grants, refreshes, devices, Ports.NOOP_AUDIT, NOW + 10);
        assertEquals("REVOKED", devices.findById("device-1").status);
    }
}

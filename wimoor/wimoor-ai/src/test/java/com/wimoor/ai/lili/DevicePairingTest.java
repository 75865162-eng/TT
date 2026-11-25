package com.wimoor.ai.lili;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wimoor.ai.lili.DevicePairing.Credential;
import com.wimoor.ai.lili.DevicePairing.PairingGuard;
import com.wimoor.ai.lili.DevicePairing.PairingResult;
import com.wimoor.ai.lili.DevicePairing.PairingStore;
import com.wimoor.ai.lili.DevicePairing.Scope;

/** 配对码生成、校验、设备签发与撤销的确定性测试（移植自 device-pairing.ts）。 */
class DevicePairingTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Scope SCOPE = new Scope("org-1", "ws-1");
    private static final String ACTOR = "user-1";

    static class MemoryStore implements PairingStore {
        final Map<String, Credential> rows = new ConcurrentHashMap<>();
        final List<String> audits = new ArrayList<>();
        @Override public Credential findById(String id) { return rows.get(id); }
        @Override public Credential findActiveParent(String id, Scope scope, String userId, long now) {
            Credential c = rows.get(id);
            if (c == null || !"ACTIVE".equals(c.status) || c.expiresAt <= now
                || !scope.organizationId.equals(c.organizationId) || !scope.workspaceId.equals(c.workspaceId)
                || !userId.equals(c.userId)) return null;
            return c;
        }
        @Override public void create(Credential c) { rows.put(c.id, c); }
        @Override public void update(Credential c) { rows.put(c.id, c); }
        @Override public List<Credential> list(Scope scope, String actor, String parentId) {
            List<Credential> out = new ArrayList<>();
            for (Credential c : rows.values()) {
                if (!scope.organizationId.equals(c.organizationId) || !scope.workspaceId.equals(c.workspaceId)
                    || !actor.equals(c.userId)) continue;
                if (parentId != null && !parentId.equals(c.rotatedFromId)) continue;
                out.add(c);
            }
            return out;
        }
        @Override public void audit(String org, String user, String action, String type, String id, Map<String, Object> meta) {
            audits.add(action);
        }
    }

    static final PairingGuard GUARD = new PairingGuard() {
        @Override public void assertSettingsEdit(Scope scope, String actor) { }
        @Override public void assertDataAccess(Scope scope, String actor) { }
    };

    private MemoryStore store;
    private String parentId;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        Credential parent = new Credential();
        parent.id = CryptoSupport.uuid();
        parent.organizationId = SCOPE.organizationId;
        parent.workspaceId = SCOPE.workspaceId;
        parent.userId = ACTOR;
        parent.status = "ACTIVE";
        parent.expiresAt = NOW + 86_400_000L;
        parent.tools = JsonSupport.parseArray("[\"ads_get_data_health\",\"meta_list_stores\"]");
        parent.scopes = JsonSupport.parseArray("[{\"sellerAccountId\":\"seller-1\",\"marketplaceIds\":[\"ATVPDKIKX0DER\"]}]");
        store.create(parent);
        parentId = parent.id;
    }

    private PairingResult pair() {
        return DevicePairing.createPairing(SCOPE, ACTOR, parentId, store, GUARD, NOW);
    }

    @Test
    void pairingCodeIsHashedAndExpiresAfterFiveMinutes() {
        PairingResult result = pair();
        assertTrue(CryptoSupport.isPairingId(result.pairingId));
        assertTrue(CryptoSupport.isToken43(result.code));
        assertEquals("bearer-transition", result.authentication);
        assertFalse(result.oauth);
        assertEquals(NOW + 300_000L, result.expiresAt);
        Credential pending = store.findById(result.pairingId);
        assertEquals("DISABLED", pending.status);
        assertEquals("pairing", pending.tokenPrefix);
        assertEquals(CryptoSupport.sha256Hex(result.code), pending.tokenHash);
        assertNotEquals(result.code, pending.tokenHash);
        assertTrue(store.audits.contains("lili.pairing.created"));
    }

    @Test
    void exchangeIssuesDeviceAndRevokesPairing() {
        PairingResult result = pair();
        final int[] hooks = {0};
        DevicePairing.ExchangeResult exchanged = DevicePairing.exchangePairing(result.pairingId, result.code,
            "  Codex OAuth  ", store, GUARD, (tx, device) -> hooks[0]++, NOW);
        assertTrue(exchanged.accessToken.startsWith("amcp_"));
        assertEquals(CryptoSupport.tokenPrefix(exchanged.accessToken), exchanged.device.tokenPrefix);
        assertTrue(exchanged.device.name.startsWith("Lili device: "));
        assertEquals(1, hooks[0]);
        assertEquals("REVOKED", store.findById(result.pairingId).status);
        assertEquals(store.findById(parentId).tools, exchanged.device.tools);
        assertTrue(store.audits.contains("lili.device.paired"));
    }

    @Test
    void wrongCodeIncrementsRevisionAndRevokesAfterFiveAttempts() {
        PairingResult result = pair();
        String wrong = CryptoSupport.randomToken();
        for (int i = 0; i < 5; i++) assertThrows(SyncInputException.class,
            () -> DevicePairing.exchangePairing(result.pairingId, wrong, "dev", store, GUARD, null, NOW));
        assertEquals(5, store.findById(result.pairingId).revision);
        assertEquals("DISABLED", store.findById(result.pairingId).status);
        assertThrows(SyncInputException.class,
            () -> DevicePairing.exchangePairing(result.pairingId, wrong, "dev", store, GUARD, null, NOW));
        assertEquals("REVOKED", store.findById(result.pairingId).status);
        assertTrue(store.audits.contains("lili.pairing.denied"));
    }

    @Test
    void malformedInputIsRejected() {
        assertThrows(SyncInputException.class,
            () -> DevicePairing.exchangePairing("not-a-uuid", CryptoSupport.randomToken(), "dev", store, GUARD, null, NOW));
        PairingResult result = pair();
        assertThrows(SyncInputException.class,
            () -> DevicePairing.exchangePairing(result.pairingId, "short", "dev", store, GUARD, null, NOW));
        assertThrows(SyncInputException.class,
            () -> DevicePairing.exchangePairing(result.pairingId, result.code, "   ", store, GUARD, null, NOW));
    }

    @Test
    void expiredPairingAndChangedParentScopesAreRejected() {
        PairingResult result = pair();
        assertThrows(SyncInputException.class,
            () -> DevicePairing.exchangePairing(result.pairingId, result.code, "dev", store, GUARD, null, NOW + 300_001L));
        Credential parent = store.findById(parentId);
        parent.scopes = JsonSupport.parseArray("[]");
        assertThrows(SyncInputException.class,
            () -> DevicePairing.exchangePairing(result.pairingId, result.code, "dev", store, GUARD, null, NOW));
    }

    @Test
    void revokeDeviceMarksRevoked() {
        PairingResult result = pair();
        DevicePairing.ExchangeResult exchanged = DevicePairing.exchangePairing(result.pairingId, result.code,
            "dev", store, GUARD, null, NOW);
        DevicePairing.revokeDevice(SCOPE, ACTOR, exchanged.device.id, store);
        assertEquals("REVOKED", store.findById(exchanged.device.id).status);
        assertTrue(store.audits.contains("lili.device.revoked"));
        assertEquals(1, DevicePairing.listDevices(SCOPE, ACTOR, parentId, store).size());
    }
}

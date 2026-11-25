package com.wimoor.ai.lili;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 移植自 src/lib/lili/device-pairing.ts：一次性配对码的生成、校验、设备签发与撤销。
 * 32 字节 base64url 码只存 SHA-256；比较用 timingSafeEqual；错误 5 次后撤销；
 * 配对设备从父凭证继承 tools/scopes，且父凭证工具集与范围变更即拒绝配对。
 * DB 操作通过 PairingStore 端口注入，模块内给出可运行的内存实现。
 */
public final class DevicePairing {

    private DevicePairing() {}

    public static final class Scope {
        public final String organizationId, workspaceId;
        public Scope(String organizationId, String workspaceId) { this.organizationId = organizationId; this.workspaceId = workspaceId; }
    }

    /** 可变的内部凭证行（对应 AmazonMcpCredential）。 */
    public static final class Credential {
        public String id, name, status, tokenHash, tokenPrefix, rotatedFromId;
        public String organizationId, workspaceId, userId;
        public long expiresAt, lastUsedAt, createdAt, revision;
        public Object tools, scopes;
        public Credential() {}
        public Credential copy() {
            Credential c = new Credential();
            c.id = id; c.name = name; c.status = status; c.tokenHash = tokenHash; c.tokenPrefix = tokenPrefix;
            c.rotatedFromId = rotatedFromId; c.organizationId = organizationId; c.workspaceId = workspaceId;
            c.userId = userId; c.expiresAt = expiresAt; c.lastUsedAt = lastUsedAt;
            c.createdAt = createdAt; c.revision = revision; c.tools = tools; c.scopes = scopes;
            return c;
        }
        String scopeOrganization() { return organizationId; }
        String scopeWorkspace() { return workspaceId; }
        String userId() { return userId; }
    }

    /** 宿主数据访问端口，可用内存 Map 实现。 */
    public interface PairingStore {
        Credential findById(String id);
        Credential findActiveParent(String id, Scope scope, String userId, long now);
        void create(Credential credential);
        void update(Credential credential);
        List<Credential> list(Scope scope, String actor, String parentId);
        void audit(String organizationId, String userId, String action, String entityType, String entityId, Map<String, Object> metadata);
        /** pg_advisory_xact_lock 的等价串行化点。 */
        default void lock(String key) { }
    }

    /** assertAmazonAction(edit, settings) + explicitAmazonDataAccess(view, true)。 */
    public interface PairingGuard {
        void assertSettingsEdit(Scope scope, String actor);
        void assertDataAccess(Scope scope, String actor);
    }

    /** onPaired(tx, device) 回调端口。 */
    public interface PairedHook {
        void onPaired(PairingStore tx, Credential device) throws Exception;
    }

    public static final class PairingResult {
        public final String pairingId, code, authentication;
        public final long expiresAt;
        public final boolean oauth = false;
        PairingResult(String pairingId, String code, long expiresAt) {
            this.pairingId = pairingId; this.code = code; this.expiresAt = expiresAt; this.authentication = "bearer-transition";
        }
    }

    public static final class ExchangeResult {
        public final Credential device;
        public final String accessToken, tokenType;
        public final long expiresAt;
        public final boolean oauth = false;
        ExchangeResult(Credential device, String accessToken) {
            this.device = device; this.accessToken = accessToken; this.tokenType = "Bearer"; this.expiresAt = device.expiresAt;
        }
    }

    private static final long PAIRING_TTL_MS = 300_000L;
    private static final long DEVICE_MAX_TTL_MS = 30L * 86_400_000L;

    private static SyncInputException invalid() {
        return new SyncInputException("配对码无效、过期、已使用或已撤销。", 401);
    }

    public static PairingResult createPairing(Scope scope, String actor, String credentialId,
                                              PairingStore store, PairingGuard guard, long now) {
        guard.assertSettingsEdit(scope, actor);
        guard.assertDataAccess(scope, actor);
        Credential parent = store.findActiveParent(credentialId, scope, actor, now);
        if (parent == null) throw new SyncInputException("请选择本人有效凭证。", 403);
        String id = CryptoSupport.uuid();
        String code = CryptoSupport.randomToken();
        long expiresAt = now + PAIRING_TTL_MS;
        Credential pending = new Credential();
        pending.id = id; pending.name = "Lili pending device"; pending.status = "DISABLED";
        pending.organizationId = scope.organizationId; pending.workspaceId = scope.workspaceId; pending.userId = actor;
        pending.tokenHash = CryptoSupport.sha256Hex(code); pending.tokenPrefix = "pairing";
        pending.tools = JsonSupport.copy(parent.tools); pending.scopes = JsonSupport.copy(parent.scopes);
        pending.rotatedFromId = parent.id; pending.expiresAt = expiresAt; pending.createdAt = now;
        store.create(pending);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("workspaceId", scope.workspaceId); meta.put("parentId", parent.id);
        store.audit(scope.organizationId, actor, "lili.pairing.created", "AmazonMcpCredential", id, meta);
        return new PairingResult(id, code, expiresAt);
    }

    /** exchangePairing：失败一律抛 401 invalid()，不泄露具体原因。 */
    public static ExchangeResult exchangePairing(String pairingId, String code, String deviceName,
                                                 PairingStore store, PairingGuard guard, PairedHook hook, long now) {
        if (!CryptoSupport.isPairingId(pairingId) || !CryptoSupport.isToken43(code)
            || deviceName == null || deviceName.trim().isEmpty() || deviceName.length() > 100) throw invalid();
        store.lock("lili-pair:" + pairingId);
        Credential pair = store.findById(pairingId);
        if (pair == null || !("DISABLED".equals(pair.status) && "pairing".equals(pair.tokenPrefix) && pair.revision <= 5)
            || pair.expiresAt <= now) throw invalid();
        if (!CryptoSupport.constantTimeHexEquals(pair.tokenHash, CryptoSupport.sha256Hex(code))) {
            long attempts = pair.revision;
            Credential updated = pair.copy();
            updated.status = attempts >= 5 ? "REVOKED" : "DISABLED";
            updated.revision++;
            store.update(updated);
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("workspaceId", pair.scopeWorkspace()); meta.put("attempts", attempts);
            store.audit(pair.scopeOrganization(), pair.userId(), "lili.pairing.denied", "AmazonMcpCredential", pair.id, meta);
            throw invalid();
        }
        Scope scope = new Scope(pair.scopeOrganization(), pair.scopeWorkspace());
        Credential parent = store.findActiveParent(pair.rotatedFromId, scope, pair.userId(), now);
        if (parent == null || !JsonSupport.stableStringify(parent.scopes).equals(JsonSupport.stableStringify(pair.scopes))
            || !JsonSupport.stableStringify(parent.tools).equals(JsonSupport.stableStringify(pair.tools))) throw invalid();
        guard.assertDataAccess(scope, pair.userId());
        String token = CryptoSupport.accessToken();
        Credential device = new Credential();
        device.id = CryptoSupport.uuid(); device.name = "Lili device: " + deviceName.trim();
        device.organizationId = scope.organizationId; device.workspaceId = scope.workspaceId; device.userId = pair.userId();
        device.tokenHash = CryptoSupport.sha256Hex(token); device.tokenPrefix = CryptoSupport.tokenPrefix(token);
        device.tools = JsonSupport.copy(parent.tools); device.scopes = JsonSupport.copy(parent.scopes);
        device.expiresAt = Math.min(parent.expiresAt, now + DEVICE_MAX_TTL_MS);
        device.createdAt = now; device.rotatedFromId = parent.id;
        store.create(device);
        Credential used = pair.copy(); used.status = "REVOKED"; used.revision++;
        store.update(used);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("workspaceId", scope.workspaceId); meta.put("pairingId", pair.id); meta.put("parentId", parent.id);
        store.audit(scope.organizationId, pair.userId(), "lili.device.paired", "AmazonMcpCredential", device.id, meta);
        try { if (hook != null) hook.onPaired(store, device); }
        catch (Exception e) { throw new IllegalStateException("LILI_PAIRING_HOOK_FAILED", e); }
        return new ExchangeResult(device, token);
    }

    public static List<Credential> listDevices(Scope scope, String actor, String parentId, PairingStore store) {
        List<Credential> out = new ArrayList<>();
        for (Credential c : store.list(scope, actor, parentId)) {
            boolean device = c.name != null && c.name.startsWith("Lili device: ");
            boolean pending = "pairing".equals(c.tokenPrefix) && "DISABLED".equals(c.status);
            if (device || pending) out.add(c.copy());
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        if (out.size() > 100) return new ArrayList<>(out.subList(0, 100));
        return out;
    }

    public static void revokeDevice(Scope scope, String actor, String id, PairingStore store) {
        store.lock("lili-pair:" + id);
        Credential row = null;
        for (Credential c : store.list(scope, actor, null)) {
            if (!id.equals(c.id)) continue;
            boolean device = c.name != null && c.name.startsWith("Lili device: ");
            boolean pending = "pairing".equals(c.tokenPrefix) && "DISABLED".equals(c.status);
            if (device || pending) { row = c; break; }
        }
        if (row == null) throw new SyncInputException("设备不可访问。", 403);
        Credential revoked = row.copy(); revoked.status = "REVOKED"; revoked.revision++;
        store.update(revoked);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("workspaceId", scope.workspaceId);
        store.audit(scope.organizationId, actor, "lili.device.revoked", "AmazonMcpCredential", id, meta);
    }
}

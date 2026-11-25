package com.wimoor.ai.lili;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.wimoor.ai.lili.Ports.AuditSink;
import com.wimoor.ai.lili.ToolConfig.XiyouSettings;

/**
 * 移植自 src/lib/lili/tool-config-server.ts：使用宿主的 integration 表保存加密配置，
 * 无 dotenv 写入、无明文回退。seal/unseal 用 JDK AES-GCM 实现（对应 @/lib/amazon-sync/approval-policy）。
 */
public final class ToolConfigServer {

    private ToolConfigServer() {}

    private static final String PURPOSE = "lili-xiyou-configuration.v1";

    public static final class ToolConfigScope {
        public final String organizationId, workspaceId, userId;
        public ToolConfigScope(String organizationId, String workspaceId, String userId) {
            this.organizationId = organizationId; this.workspaceId = workspaceId; this.userId = userId;
        }
        String key() { return organizationId + "\u0000" + workspaceId + "\u0000" + userId + "\u0000" + ToolConfig.XIYOU_PROVIDER; }
    }

    /** 加密端口；identity = JSON.stringify([userId, provider])。 */
    public interface Sealer {
        String seal(String plaintext, ToolConfigScope scope, String identity, String purpose);
        String unseal(String ciphertext, ToolConfigScope scope, String identity, String purpose);
    }

    /** 真实 AES-GCM 实现，密钥由宿主注入（32 字节）。 */
    public static final class AesGcmSealer implements Sealer {
        private final SecretKeySpec key;
        private final SecureRandom random = new SecureRandom();
        public AesGcmSealer(byte[] key) {
            if (key == null || (key.length != 16 && key.length != 24 && key.length != 32))
                throw new IllegalArgumentException("INVALID_ENCRYPTION_KEY");
            this.key = new SecretKeySpec(key, "AES");
        }
        @Override public String seal(String plaintext, ToolConfigScope scope, String identity, String purpose) {
            try {
                byte[] iv = new byte[12];
                random.nextBytes(iv);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                cipher.updateAAD((identity + "\u0000" + purpose).getBytes(StandardCharsets.UTF_8));
                byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                byte[] combined = new byte[iv.length + ct.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(ct, 0, combined, iv.length, ct.length);
                return Base64.getEncoder().encodeToString(combined);
            } catch (Exception e) { throw new IllegalStateException("LILI_CONFIG_SEAL_FAILED", e); }
        }
        @Override public String unseal(String ciphertext, ToolConfigScope scope, String identity, String purpose) {
            try {
                byte[] combined = Base64.getDecoder().decode(ciphertext);
                byte[] iv = new byte[12];
                byte[] ct = new byte[combined.length - iv.length];
                System.arraycopy(combined, 0, iv, 0, iv.length);
                System.arraycopy(combined, iv.length, ct, 0, ct.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                cipher.updateAAD((identity + "\u0000" + purpose).getBytes(StandardCharsets.UTF_8));
                return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
            } catch (Exception e) { throw new IllegalStateException("Invalid saved configuration", e); }
        }
    }

    public static final class EncryptedConfig {
        public final int version;
        public final String ciphertext;
        public EncryptedConfig(int version, String ciphertext) { this.version = version; this.ciphertext = ciphertext; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", version); m.put("ciphertext", ciphertext);
            return m;
        }
    }

    private static String identity(ToolConfigScope scope) {
        return JsonSupport.stringify(java.util.Arrays.asList(scope.userId, ToolConfig.XIYOU_PROVIDER));
    }

    public static EncryptedConfig encryptXiyouSettings(XiyouSettings settings, ToolConfigScope scope, Sealer sealer) {
        return new EncryptedConfig(1, sealer.seal(JsonSupport.stringify(settings.toMap()), scope, identity(scope), PURPOSE));
    }

    public static XiyouSettings decryptXiyouSettings(Object config, ToolConfigScope scope, Sealer sealer) {
        Map<String, Object> value = JsonSupport.obj(config);
        if (value == null || !Integer.valueOf(1).equals(value.get("version")) || !(value.get("ciphertext") instanceof String))
            throw new IllegalStateException("Invalid saved configuration");
        String json = sealer.unseal((String) value.get("ciphertext"), scope, identity(scope), PURPOSE);
        return ToolConfig.mergeXiyouSettings(JsonSupport.parseObject(json));
    }

    /** ToolConfigStore 端口。 */
    public interface ToolConfigStore {
        XiyouSettings read(ToolConfigScope scope);
        XiyouSettings save(ToolConfigScope scope, Map<String, Object> patch);
    }

    /** 进程内实现：复用 integration 表的语义，保留版本递增与审计。 */
    public static final class InMemoryToolConfigStore implements ToolConfigStore {
        private final Map<String, Object> rows = new ConcurrentHashMap<>();
        private final Map<String, Integer> versions = new ConcurrentHashMap<>();
        private final Sealer sealer;
        private final AuditSink audit;
        public InMemoryToolConfigStore(Sealer sealer, AuditSink audit) {
            this.sealer = sealer; this.audit = audit == null ? Ports.NOOP_AUDIT : audit;
        }
        @Override public XiyouSettings read(ToolConfigScope scope) {
            Object row = rows.get(scope.key());
            return row == null ? new XiyouSettings(ToolConfig.DEFAULT.enabled, ToolConfig.DEFAULT.mcpUrl,
                ToolConfig.DEFAULT.mcpToken, ToolConfig.DEFAULT.openapiBase, ToolConfig.DEFAULT.clientId, ToolConfig.DEFAULT.clientSecret)
                : decryptXiyouSettings(row, scope, sealer);
        }
        @Override public XiyouSettings save(ToolConfigScope scope, Map<String, Object> patch) {
            synchronized (scope.key().intern()) {
                XiyouSettings previous = read(scope);
                XiyouSettings settings = ToolConfig.mergeXiyouSettings(patch, previous);
                rows.put(scope.key(), encryptXiyouSettings(settings, scope, sealer).toMap());
                int version = versions.merge(scope.key(), 1, Integer::sum);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("workspaceId", scope.workspaceId);
                meta.put("provider", ToolConfig.XIYOU_PROVIDER);
                meta.put("version", version);
                meta.put("enabled", settings.enabled);
                meta.put("configured", ToolConfig.publicXiyouSettings(settings).configured);
                meta.put("tokenReplaced", patch != null && patch.containsKey("mcpToken"));
                meta.put("clientSecretReplaced", patch != null && patch.containsKey("clientSecret"));
                audit.record(scope.organizationId, scope.userId, "external_integration_setting_save",
                    "external_integration_setting", scope.key(), meta);
                return settings;
            }
        }
    }
}

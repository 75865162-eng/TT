package com.wimoor.ai.report;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Ported from {@code amazon-ads/credentials.ts}.
 *
 * <p>AES-256-GCM encryption for checkpoints and refresh tokens. The additional
 * authenticated data binds ciphertext to tenant/workspace/authorization and to a task
 * ID (or a concrete part index), so a checkpoint or signed URL cannot be swapped
 * across tenants, authorizations or tasks. A missing/invalid 32-byte Base64 key
 * fails closed before any Amazon request.
 */
public final class AdsCredentials {

    private static final String KEY_NAME = "AMAZON_ADS_ENCRYPTION_KEY";
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AdsCredentials() {}

    public static final class Scope {
        public String organizationId;
        public String workspaceId;
        public String authorizationId;

        public Scope() {}

        public Scope(String organizationId, String workspaceId, String authorizationId) {
            this.organizationId = organizationId;
            this.workspaceId = workspaceId;
            this.authorizationId = authorizationId;
        }
    }

    private static byte[] key(Map<String, String> env) {
        String encoded = env.get(KEY_NAME);
        if (encoded == null) encoded = env.get(KEY_NAME.toLowerCase());
        encoded = encoded == null ? null : encoded.trim();
        if (encoded == null || !encoded.matches("[A-Za-z0-9+/]{43}=")) {
            throw new IllegalStateException("服务器缺少有效的 " + KEY_NAME + "（32 字节 Base64 密钥）。");
        }
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != 32) throw new IllegalStateException("服务器缺少有效的 " + KEY_NAME + "（32 字节 Base64 密钥）。");
        return key;
    }

    private static byte[] scopeAad(Scope scope) {
        return AdsCanonical.json(java.util.Arrays.asList(
                scope.organizationId, scope.workspaceId, scope.authorizationId))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Encrypts any JSON-serialisable checkpoint value. */
    public static String encryptCheckpoint(Object value, Scope scope, String taskId, Map<String, String> env) {
        byte[] key = key(env);
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        byte[] aad = concat(scopeAad(scope), taskId.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = seal(key, iv, aad, AdsCanonical.json(value).getBytes(StandardCharsets.UTF_8));
        return format(iv, sealed);
    }

    public static String encryptCheckpoint(Object value, Scope scope, String taskId) {
        return encryptCheckpoint(value, scope, taskId, System.getenv());
    }

    /** Decrypts a checkpoint; any malformed/tampered ciphertext fails closed. */
    public static String decryptCheckpoint(String value, Scope scope, String taskId, Map<String, String> env) {
        if (value == null) return null;
        byte[] key = key(env);
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4 || !"v1".equals(parts[0]) || parts[1].isEmpty()
                    || parts[2].isEmpty() || parts[3].isEmpty()) throw new IllegalArgumentException();
            byte[] iv = B64URL_DECODER.decode(parts[1]);
            byte[] tag = B64URL_DECODER.decode(parts[3]);
            byte[] aad = concat(scopeAad(scope), taskId.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = open(key, iv, aad, B64URL_DECODER.decode(parts[2]), tag);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Amazon Ads 同步检查点无法解密。未向 Amazon 发起请求。", e);
        }
    }

    /** Refresh tokens use AAD without a task ID and a strict length bound on decrypt. */
    public static String encryptRefreshToken(String refreshToken, Scope scope, Map<String, String> env) {
        byte[] key = key(env);
        if (refreshToken == null || refreshToken.trim().isEmpty() || refreshToken.length() > 16384) {
            throw new IllegalArgumentException("Amazon Ads refresh token 无效。");
        }
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        byte[] aad = scopeAad(scope);
        byte[] plaintext = refreshToken.getBytes(StandardCharsets.UTF_8);
        return format(iv, seal(key, iv, aad, plaintext));
    }

    public static String decryptRefreshToken(String value, Scope scope, Map<String, String> env) {
        byte[] key = key(env);
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            byte[] iv = B64URL_DECODER.decode(parts[1]);
            byte[] tag = B64URL_DECODER.decode(parts[3]);
            byte[] plaintext = open(key, iv, scopeAad(scope), B64URL_DECODER.decode(parts[2]), tag);
            String token = new String(plaintext, StandardCharsets.UTF_8);
            if (token.isEmpty() || token.length() > 16384) throw new IllegalArgumentException();
            return token;
        } catch (RuntimeException e) {
            throw new IllegalStateException("已保存的 Amazon Ads 凭证无法解密；请检查 Ads 加密主密钥。未向 Amazon 发起请求。", e);
        }
    }

    public static boolean hasKey(Map<String, String> env) {
        String value = env.getOrDefault(KEY_NAME, env.get(KEY_NAME.toLowerCase()));
        return value != null && value.trim().matches("[A-Za-z0-9+/]{43}=");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static Cipher init(int mode, byte[] key, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad);
            return cipher;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] seal(byte[] key, byte[] iv, byte[] aad, byte[] plaintext) {
        try {
            Cipher cipher = init(Cipher.ENCRYPT_MODE, key, iv, aad);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Java GCM appends the 16-byte auth tag to the ciphertext; split it into v1 format. */
    private static String format(byte[] iv, byte[] sealed) {
        int tagLength = 16;
        byte[] ciphertext = java.util.Arrays.copyOf(sealed, sealed.length - tagLength);
        byte[] tag = java.util.Arrays.copyOfRange(sealed, sealed.length - tagLength, sealed.length);
        return "v1." + B64URL.encodeToString(iv) + "." + B64URL.encodeToString(ciphertext)
                + "." + B64URL.encodeToString(tag);
    }

    private static byte[] open(byte[] key, byte[] iv, byte[] aad, byte[] ciphertext, byte[] tag) {
        try {
            Cipher cipher = init(Cipher.DECRYPT_MODE, key, iv, aad);
            return cipher.doFinal(concat(ciphertext, tag));
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }
}

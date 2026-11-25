package com.wimoor.ai.lili;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 移植辅助类：对应 device-pairing.ts / oauth.ts / call-quota-server.ts 中的
 * createHash('sha256')、randomBytes(32).toString('base64url')、randomUUID 与 timingSafeEqual。
 */
public final class CryptoSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private CryptoSupport() {}

    /** sha256(value).digest('hex') */
    public static String sha256Hex(String value) {
        byte[] d = digest(value);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** createHash('sha256').update(v).digest('base64url') —— PKCE S256 challenge。 */
    public static String sha256Base64Url(String value) {
        return URL_ENCODER.encodeToString(digest(value));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** randomBytes(32).toString('base64url')，固定 43 个字符。 */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    public static String uuid() { return UUID.randomUUID().toString(); }

    /** 生成 amcp_ 前缀访问令牌（保留前缀 12 字符供 tokenPrefix 使用）。 */
    public static String accessToken() { return "amcp_" + randomToken(); }

    public static String tokenPrefix(String token) {
        return token == null || token.length() <= 12 ? token : token.substring(0, 12);
    }

    /** timingSafeEqual(Buffer.from(a,'hex'), Buffer.from(b,'hex')) 的等价实现。 */
    public static boolean constantTimeHexEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] left = decodeHex(a), right = decodeHex(b);
        return MessageDigest.isEqual(left, right);
    }

    private static byte[] decodeHex(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("invalid hex");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 校验 base64url 且恰好 43 字符（32 字节编码）。 */
    public static boolean isToken43(String value) {
        if (value == null || value.length() != 43) return false;
        if (!value.matches("[A-Za-z0-9_-]{43}")) return false;
        try { return URL_DECODER.decode(value).length == 32; }
        catch (IllegalArgumentException e) { return false; }
    }

    /** /^[a-f0-9-]{36}$/ （pairing id 格式，与 TS 正则一致，不强制标准 UUID 变体位）。 */
    public static boolean isPairingId(String value) {
        return value != null && value.matches("[a-f0-9-]{36}");
    }

    /** PKCE verifier: /^[A-Za-z0-9._~-]{43,128}$/ */
    public static boolean isPkceVerifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._~-]{43,128}");
    }
}

package com.nexusforge.ai.client;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * 用户私 Key 加解密工具。
 *
 * <p>算法:AES-256-GCM(认证加密,自带完整性校验)。
 * 密文格式:iv(12B) || ciphertext(原文长度) || tag(16B)。
 *
 * <p>主密钥派生:从 {@code spring.ai.preference.master-key} 读取明文;
 * 缺省时降级为 {@code JWT_SECRET} (从 {@code jwt.secret} 取) 的 SHA-256。
 * 派生结果固定 32 字节(256-bit),无需 PKCS#5 padding。
 *
 * <p>线程安全:每次 encrypt/decrypt 新建 Cipher 实例(无状态);{@link SecureRandom}
 * 实例共用(JDK SecureRandom 是线程安全的)。
 *
 * <p>降级策略:任何解密异常(IV 长度错 / tag 校验失败 / 原文被篡改)抛
 * {@link IllegalStateException},由调用方映射为 500。
 */
@Slf4j
public class ApiKeyCipher {

    /** AES-GCM 推荐 IV 长度 12 字节(96-bit) */
    private static final int IV_LEN = 12;
    /** GCM tag 长度 128-bit */
    private static final int TAG_LEN_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param masterKey 主密钥明文;若为空则使用 {@link #deriveFromJwtSecret(String)} 的结果;
     *                  两者都为空时,使用空字符串派生(测试场景可启动,真实 encrypt/decrypt 会抛错)
     * @param jwtSecret 当 {@code masterKey} 为空时作为派生源
     */
    public ApiKeyCipher(String masterKey, String jwtSecret) {
        String source;
        if (masterKey != null && !masterKey.isBlank()) {
            source = masterKey;
        } else if (jwtSecret != null && !jwtSecret.isBlank()) {
            source = jwtSecret;
            log.warn("[ApiKeyCipher] spring.ai.preference.master-key 未配置,降级用 JWT_SECRET 派生主密钥(建议生产显式配置独立密钥)");
        } else {
            // 测试场景:无密钥也能启动,但实际 encrypt/decrypt 会失败 → 业务方应保证至少配置一个
            source = "nexus-forge-test-fallback-key";
            log.warn("[ApiKeyCipher] spring.ai.preference.master-key 与 jwt.secret 均未配置,使用内置测试兜底(生产严禁出现此情况)");
        }
        this.key = new SecretKeySpec(sha256(source), "AES");
    }

    /**
     * 加密 API Key。
     *
     * @param plaintext 用户输入的 API Key 明文
     * @return iv(12B) || ciphertext || tag(16B)
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /**
     * 解密 API Key。
     *
     * @param packed {@link #encrypt(String)} 返回的字节数组
     * @return 明文;若 packed 为 null 返回 null
     */
    public String decrypt(byte[] packed) {
        if (packed == null) return null;
        if (packed.length < IV_LEN + 16) {
            throw new IllegalStateException("API Key 密文长度异常");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(packed, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[packed.length - IV_LEN];
            System.arraycopy(packed, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败(可能主密钥已轮换 / 原文被篡改)", e);
        }
    }

    /**
     * 计算 Key 指纹(展示用,不暴露真值)。
     * 形如 {@code sk-12••••a3b4}:前 4 字符原文 + sha256 前 4 hex。
     */
    public String fingerprint(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        String head = plaintext.length() <= 4 ? plaintext : plaintext.substring(0, 4);
        String hex = bytesToHex(sha256(plaintext)).substring(0, 8);
        return head + "••••" + hex;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String bytesToHex(byte[] bs) {
        StringBuilder sb = new StringBuilder(bs.length * 2);
        for (byte b : bs) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
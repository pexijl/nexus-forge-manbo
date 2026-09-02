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
 * 用户私有 API Key 的加解密工具。
 *
 * <p><b>算法</b>:AES-256-GCM(认证加密,AEAD;同时保证机密性与完整性)。
 * 密文布局:iv(12B) || ciphertext(原文 UTF-8 字节) || tag(16B),全部拼接为单字节数组存储。
 *
 * <p><b>IV 随机性</b>:每次 {@link #encrypt(String)} 都用 {@link SecureRandom} 生成新 IV(96-bit),
 * 因此同一明文两次加密会得到不同密文——这是 GCM 安全的关键前提,严禁复用 (key, IV) 对。
 *
 * <p><b>主密钥派生</b>(三档降级):① {@code spring.ai.preference.master-key} 明文 →
 * ② {@code jwt.secret} → ③ 内置测试兜底。三档统一截取 SHA-256 前 32 字节(256-bit),
 * 满足 AES-256 密钥长度,无需 PKCS#5 padding。生产严禁落到兜底档。
 *
 * <p><b>线程安全</b>:实例无状态;每次 {@code encrypt/decrypt} 新建 {@link Cipher}
 * (JDK 的 {@code Cipher} 非线程安全,且 ENCRYPT/DECRYPT 模式不能复用),
 * {@link SecureRandom} 复用同一实例(JDK SecureRandom 线程安全)。
 *
 * <p><b>失败语义</b>:解密异常(IV 长度错 / tag 校验失败 / 原文被篡改 / 主密钥已轮换)
 * 抛 {@link IllegalStateException},由调用方映射为 500,不要把这类错误暴露给前端。
 *
 * <p><b>轮换注意</b>:轮换 master-key 后,旧密文无法解密;若需平滑轮换,
 * 必须在 {@code user_ai_preference.encrypted_api_key} 上保留旧密钥副本做重加密。
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
     * 构造 ApiKeyCipher 并立即派生 32 字节主密钥(三档降级见类 Javadoc)。
     *
     * @param masterKey 主密钥明文(通常来自 {@code spring.ai.preference.master-key});
     *                  为空时降级到 {@code jwtSecret},仍为空则使用内置测试兜底
     * @param jwtSecret 当 {@code masterKey} 为空时作为派生源
     *                  (通常来自 {@code jwt.secret},即 {@link com.nexusforge.auth.config.JwtProperties} 配置)
     */
    public ApiKeyCipher(String masterKey, String jwtSecret) {
        String source;
        if (masterKey != null && !masterKey.isBlank()) {
            source = masterKey;
        } else if (jwtSecret != null && !jwtSecret.isBlank()) {
            source = jwtSecret;
            // 降级到 JWT 密钥:常见于开发/单服务本地部署;生产建议显式配独立密钥以隔离风险域
            log.warn("[ApiKeyCipher] spring.ai.preference.master-key 未配置,降级用 JWT_SECRET 派生主密钥(建议生产显式配置独立密钥)");
        } else {
            // 两源皆空:仅保证应用可启动(单元测试 / 本地开发场景);
            // 生产若走到这里说明配置缺失,必须立即修复而非依赖兜底
            source = "nexus-forge-test-fallback-key";
            log.warn("[ApiKeyCipher] spring.ai.preference.master-key 与 jwt.secret 均未配置,使用内置测试兜底(生产严禁出现此情况)");
        }
        // SHA-256 输出 32 字节,AES-256 正好需要 32 字节密钥,无需再做 KDF
        this.key = new SecretKeySpec(sha256(source), "AES");
    }

    /**
     * 加密 API Key。每次调用生成新随机 IV,保证同一明文每次密文不同。
     *
     * @param plaintext 用户输入的 API Key 明文;为 {@code null} 时直接返回 {@code null}
     *                  (与 {@code user_ai_preference.encrypted_api_key} 允许为 NULL 兼容)
     * @return iv(12B) || ciphertext || tag(16B) 拼接的字节数组,可直接存入 {@code BYTEA} 列
     * @throws IllegalStateException 加密失败(底层 JCE 不可用 / 密钥长度错等)
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            // 1) 生成 96-bit 随机 IV:GCM 严禁复用 (key, IV) 对,否则对攻击者等于明文
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            // 2) 每次新建 Cipher:JDK Cipher 非线程安全,且 ENCRYPT/DECRYPT 模式不同不能复用实例
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            // 3) GCM/NoPadding:AES-GCM 是流式 AEAD,无需 PKCS#5 padding;doFinal 输出 = ciphertext || tag(16B)
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // 4) 输出拼接:IV 在前 → 解密时先取前 12B 还原;ct(cipher||tag)紧跟其后
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
     * @param packed {@link #encrypt(String)} 返回的字节数组;为 {@code null} 时返回 {@code null}
     * @return 解密后的明文
     * @throws IllegalStateException 密文格式错(过短)/ GCM tag 校验失败(原文被篡改)/
     *                               主密钥已轮换(密钥与密文不匹配)
     */
    public String decrypt(byte[] packed) {
        if (packed == null) return null;
        // 长度下界:IV 12B + GCM tag 16B = 28B;不到 28B 必然是脏数据(列被截断 / 写入前未完整加密)
        if (packed.length < IV_LEN + 16) {
            throw new IllegalStateException("API Key 密文长度异常");
        }
        try {
            // 拆分:前 12B 是 IV,之后所有字节是 ciphertext || tag
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(packed, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[packed.length - IV_LEN];
            System.arraycopy(packed, IV_LEN, ct, 0, ct.length);
            // doFinal 内部会校验末尾 16B tag,失败抛 AEADBadTagException(GCM 完整性保证的关键)
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LEN_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 错误信息有意暴露两种最常见根因(主密钥轮换 / 原文被篡改),便于排查但不区分返回(都是 500)
            throw new IllegalStateException("API Key 解密失败(可能主密钥已轮换 / 原文被篡改)", e);
        }
    }

    /**
     * 计算 API Key 指纹,用于 UI 列表/详情页展示。
     * <p>格式:前 4 字符原文 + 中点 + sha256 前 8 hex(4 字节),例 {@code sk-12••••a3b4c5d6}。
     * <p><b>仅用于人眼区分</b>:碰撞概率约 2^-32,不要用于认证或精确比对——
     * "两个指纹相同"不等价于"两个 key 相同","指纹不同"则肯定不同。
     *
     * @param plaintext 用户输入的明文;为 {@code null}/空时返回 {@code null}
     * @return 形如 {@code <head>••••<hex>} 的展示串;若原文 ≤ 4 字符则 head 为原文全量
     */
    public String fingerprint(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        // 短 key 兜底:原文 ≤ 4 字符时直接全量展示(无法再截前 4)
        String head = plaintext.length() <= 4 ? plaintext : plaintext.substring(0, 4);
        // sha256 前 4 字节 → 8 hex,够肉眼区分,又远少于真 key 长度(防误读)
        String hex = bytesToHex(sha256(plaintext)).substring(0, 8);
        return head + "••••" + hex;
    }

    /**
     * 计算字符串的 SHA-256 摘要(UTF-8 字节)。
     * <p>用于主密钥派生(取 32 字节)与指纹生成(取前若干字节);JDK 必带 SHA-256,
     * 此处的 {@link NoSuchAlgorithmException} 实际不可触发,留作防御性兜底。
     */
    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 字节数组转小写 hex 字符串(无分隔符)。
     * <p>JDK 17+ 可用 {@code HexFormat.of().formatHex(bs)} 替代;本项目用 JDK 26 但保留手写实现,
     * 以避免被 Spring Boot starter 默认 lockstep 的 JDK 版本耦合住,后续降级到 17/21 时零改动。
     */
    private static String bytesToHex(byte[] bs) {
        StringBuilder sb = new StringBuilder(bs.length * 2);
        for (byte b : bs) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
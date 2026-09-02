package com.nexusforge.password;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.UserStatus;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.mail.EmailSender;
import com.nexusforge.password.dto.ConfirmResetDto;
import com.nexusforge.ratelimit.RedisRateLimiter;
import com.nexusforge.service.AuthService;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * 密码重置(邮箱验证码)主业务。
 *
 * <p><b>Redis 键设计</b>({@code pwd:reset:*} 命名空间,与认证 {@code auth:*} 解耦):</p>
 * <ul>
 *   <li>{@code pwd:reset:code:{emailHash}} —— SHA-256(验证码) 值,TTL = codeTtlSeconds</li>
 *   <li>{@code pwd:reset:attempts:{emailHash}} —— 验证失败计数(首次 INCR 设 TTL),≥ maxAttempts 验证码失效</li>
 *   <li>{@code pwd:reset:rate:{emailHash}} —— 邮箱维度限流,TTL = rateLimitWindowSeconds,max = rateLimitEmailMax</li>
 *   <li>{@code pwd:reset:ip-rate:{ip}} —— IP 维度限流,TTL = rateLimitWindowSeconds,max = rateLimitIpMax</li>
 * </ul>
 *
 * <p><b>安全设计</b>:</p>
 * <ul>
 *   <li>邮箱大小写不敏感(全链路 {@code toLowerCase()}),DB 查询走 {@code findByEmailIgnoreCase}</li>
 *   <li>验证码存 hash 不存明文(Redis 泄露也无所谓)</li>
 *   <li>{@link MessageDigest#isEqual(byte[], byte[])} 防止 timing attack</li>
 *   <li>不论邮箱是否存在 / 用户是否被封禁,响应一律 200(防枚举);真实状态在 server log</li>
 *   <li>改密后调 {@code authService.logoutAllRefreshTokens} 踢所有 refresh,access 自然到期(≤15min)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String NS_CODE = "pwd:reset:code:";
    private static final String NS_ATTEMPTS = "pwd:reset:attempts:";
    private static final String NS_RATE_EMAIL = "pwd:reset:rate:";
    private static final String NS_RATE_IP = "pwd:reset:ip-rate:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final RedisRateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final PasswordResetProperties props;
    private final AuthService authService;
    private final TemplateEngine templateEngine;

    private final SecureRandom random = new SecureRandom();

    // ====================== 申请验证码 ======================

    /**
     * 申请密码重置 —— 生成 6 位验证码,发邮件。
     *
     * <p>响应一律正常返回(200 OK + Result.success),不论邮箱是否存在或用户被封禁;
     * 真实状态在 server log 体现,防止攻击者通过响应差异枚举有效邮箱。</p>
     *
     * <p>限流顺序(任意一项超限即拒绝,返回 429):</p>
     * <ol>
     *   <li>邮箱维度:60s 内 1 次(防用户狂点)</li>
     *   <li>IP 维度:60s 内 3 次(防邮箱枚举扫描)</li>
     * </ol>
     *
     * @param emailRaw 原始邮箱(不区分大小写,内部 normalize)
     * @param clientIp 客户端 IP(直接传 IP 字符串;Controller 侧解析 X-Forwarded-For)
     */
    public void requestReset(String emailRaw, String clientIp) {
        String email = normalizeEmail(emailRaw);
        String emailHash = sha256Hex(email);

        // 1. 邮箱维度限流
        if (!rateLimiter.tryAcquire(NS_RATE_EMAIL + emailHash,
                props.getRateLimitEmailMax(),
                Duration.ofSeconds(props.getRateLimitWindowSeconds()))) {
            log.info("[password-reset] rate-limited by email emailHash={}", emailHash);
            throw new BusinessException(ResultCode.RESET_CODE_SEND_TOO_FREQUENT);
        }

        // 2. IP 维度限流
        if (!rateLimiter.tryAcquire(NS_RATE_IP + (clientIp == null ? "unknown" : clientIp),
                props.getRateLimitIpMax(),
                Duration.ofSeconds(props.getRateLimitWindowSeconds()))) {
            log.info("[password-reset] rate-limited by ip ip={}", clientIp);
            throw new BusinessException(ResultCode.RESET_CODE_SEND_TOO_FREQUENT);
        }

        // 3. 查用户;不存在 / 被封禁均静默 return
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            log.debug("[password-reset] requested for unknown email, ignored emailHash={}", emailHash);
            return;
        }
        if (user.getStatus() == UserStatus.BANNED) {
            log.warn("[password-reset] requested for banned user, ignored userId={}", user.getId());
            return;
        }

        // 4. 生成 + 存 hash
        String code = generateCode();
        String codeHash = sha256Hex(code);
        redis.opsForValue().set(NS_CODE + emailHash, codeHash,
                Duration.ofSeconds(props.getCodeTtlSeconds()));
        // attempts key 不预创建,首次 confirm 时 INCR 出来

        // 5. 发邮件(失败仅 log —— 业务侧假设"尽力送达")
        try {
            String body = renderEmailBody(code, props.getCodeTtlSeconds() / 60);
            emailSender.send(user.getEmail(), "重置你的 Nexus Forge 密码", body);
        } catch (Exception e) {
            log.warn("[password-reset] failed to send reset email to userId={}: {}",
                    user.getId(), e.getMessage());
        }

        log.info("[password-reset] code sent userId={} emailHash={}", user.getId(), emailHash);
    }

    // ====================== 校验 + 改密 ======================

    /**
     * 校验验证码并改密。
     *
     * <p>改密成功后:</p>
     * <ol>
     *   <li>清掉 code + attempts Redis key</li>
     *   <li>{@code authService.logoutAllRefreshTokens(userId)} 踢所有 refresh</li>
     *   <li>用户当前持有的 access 在 ≤15min TTL 内自然过期(不额外吊销,避免扩 access 黑名单)</li>
     * </ol>
     *
     * @throws BusinessException {@code 2013 / 2014 / 2012} 详见 {@link ConfirmResetDto}
     */
    @Transactional
    public void confirmReset(ConfirmResetDto dto) {
        String email = normalizeEmail(dto.email());
        String emailHash = sha256Hex(email);
        String attemptsKey = NS_ATTEMPTS + emailHash;
        String codeKey = NS_CODE + emailHash;

        // 1. 失败次数自增(首次 INCR 设 TTL)
        Long attempts = redis.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1L) {
            redis.expire(attemptsKey, Duration.ofSeconds(props.getCodeTtlSeconds()));
        }
        if (attempts != null && attempts > props.getMaxAttempts()) {
            // 超过最大失败次数 —— 验证码自动失效
            redis.delete(codeKey);
            redis.delete(attemptsKey);
            log.info("[password-reset] too many attempts, code invalidated emailHash={}", emailHash);
            throw new BusinessException(ResultCode.RESET_CODE_TOO_MANY_ATTEMPTS);
        }

        // 2. 取验证码 hash
        String storedHash = redis.opsForValue().get(codeKey);
        if (storedHash == null) {
            // 已过期 / 已被使用 / 从未申请
            throw new BusinessException(ResultCode.RESET_CODE_INVALID);
        }

        // 3. 比对(MessageDigest.isEqual 防 timing attack)
        String inputHash = sha256Hex(dto.code());
        byte[] stored = storedHash.getBytes(StandardCharsets.UTF_8);
        byte[] input = inputHash.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(stored, input)) {
            throw new BusinessException(ResultCode.RESET_CODE_INVALID);
        }

        // 4. 改密
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> {
                    // 验证码有效但用户已被删除 —— 静默处理(防止异常响应嗅探)
                    log.warn("[password-reset] code valid but user gone emailHash={}", emailHash);
                    return new BusinessException(ResultCode.RESET_CODE_INVALID);
                });

        if (passwordEncoder.matches(dto.newPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.NEW_PASSWORD_SAME_AS_OLD);
        }
        user.setPassword(passwordEncoder.encode(dto.newPassword()));
        userRepository.save(user);

        // 5. 清理 + 踢 refresh
        redis.delete(codeKey);
        redis.delete(attemptsKey);
        authService.logoutAllRefreshTokens(user.getId());

        log.info("[password-reset] password reset successful userId={}", user.getId());
    }

    // ====================== helpers ======================

    /** 邮箱大小写不敏感 + trim,空值抛 INVALID_PARAMS */
    static String normalizeEmail(String email) {
        if (email == null) {
            throw new BusinessException(ResultCode.INVALID_PARAMS, "email 不能为空");
        }
        String normalized = email.trim().toLowerCase();
        if (normalized.isEmpty()) {
            throw new BusinessException(ResultCode.INVALID_PARAMS, "email 不能为空");
        }
        return normalized;
    }

    /** 6 位纯数字验证码,前导补 0 */
    String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /** SHA-256 → hex 小写,用于邮箱 hash / 验证码 hash */
    static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Thymeleaf 渲染邮件正文;模板路径 classpath:/templates/reset-code.html */
    String renderEmailBody(String code, long ttlMinutes) {
        Context ctx = new Context();
        ctx.setVariable("code", code);
        ctx.setVariable("ttlMinutes", ttlMinutes);
        return templateEngine.process("reset-code", ctx);
    }
}

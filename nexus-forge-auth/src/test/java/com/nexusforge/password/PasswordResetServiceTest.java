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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IContext;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PasswordResetService} 单元测试 —— Mockito 隔离所有依赖,验证:
 *
 * <ul>
 *   <li>{@code requestReset} 正常路径:限流 → 查 user → 存 code hash → 发邮件</li>
 *   <li>未知邮箱 / 被封禁用户:静默 return,不发邮件,不限流拒绝</li>
 *   <li>邮箱维度 / IP 维度限流触发:抛 {@code RESET_CODE_SEND_TOO_FREQUENT}</li>
 *   <li>{@code confirmReset} 正常路径:比对 hash → 改密 → 踢 refresh</li>
 *   <li>验证码错误:自增 attempts,抛 {@code RESET_CODE_INVALID}</li>
 *   <li>失败次数超限:清掉 code,抛 {@code RESET_CODE_TOO_MANY_ATTEMPTS}</li>
 *   <li>邮箱大小写不敏感</li>
 *   <li>验证码已过期(redis 无 code key):抛 {@code RESET_CODE_INVALID}</li>
 * </ul>
 *
 * <p>PasswordResetService 8 个依赖 + 1 个 {@code @RequiredArgsConstructor} 全部 final,
 * 用手动 {@code new} 注入而非 {@code @InjectMocks},避开 Mockito 5.x 在某些
 * JDK 26 场景下对 final 字段的 inline-mock 兼容性问题。</p>
 */
class PasswordResetServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private EmailSender emailSender;
    private RedisRateLimiter rateLimiter;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private PasswordResetProperties props;
    private AuthService authService;
    private TemplateEngine templateEngine;
    private PasswordResetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailSender = mock(EmailSender.class);
        rateLimiter = mock(RedisRateLimiter.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        authService = mock(AuthService.class);
        templateEngine = mock(TemplateEngine.class);

        props = new PasswordResetProperties();
        // 默认值即可(codeTtl=300, maxAttempts=5, rateWindow=60, emailMax=1, ipMax=3)

        when(redis.opsForValue()).thenReturn(ops);
        when(templateEngine.process(eq("reset-code"), any(IContext.class)))
                .thenReturn("<html>rendered</html>");

        service = new PasswordResetService(
                userRepository, passwordEncoder, emailSender, rateLimiter,
                redis, props, authService, templateEngine);
    }

    private User activeUser(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setEmail(email);
        u.setStatus(UserStatus.ACTIVE);
        u.setPassword("ENCODED_OLD");
        return u;
    }

    // ====================== requestReset ======================

    @Test
    @DisplayName("活跃用户:限流通过,存 codeHash,发邮件")
    void request_reset_active_user_persists_code_and_sends_email() {
        User u = activeUser(1L, "alice@example.com");
        when(rateLimiter.tryAcquire(anyString(), eq(1), any(Duration.class))).thenReturn(true);
        when(rateLimiter.tryAcquire(eq("pwd:reset:ip-rate:1.2.3.4"), eq(3), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(u));

        service.requestReset("alice@example.com", "1.2.3.4");

        // 邮件 + codeHash 都被持久化 / 调用
        verify(ops).set(eq("pwd:reset:code:" + PasswordResetService.sha256Hex("alice@example.com")),
                anyString(),
                eq(Duration.ofSeconds(300)));
        verify(emailSender, times(1)).send(eq("alice@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("未知邮箱:静默 return,不发邮件,不抛异常")
    void request_reset_unknown_email_silently_returns() {
        when(rateLimiter.tryAcquire(anyString(), eq(1), any(Duration.class))).thenReturn(true);
        when(rateLimiter.tryAcquire(anyString(), eq(3), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        service.requestReset("ghost@example.com", "1.2.3.4");

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("被封禁用户:静默 return,不发邮件")
    void request_reset_banned_user_silently_returns() {
        User u = activeUser(2L, "eve@example.com");
        u.setStatus(UserStatus.BANNED);
        when(rateLimiter.tryAcquire(anyString(), eq(1), any(Duration.class))).thenReturn(true);
        when(rateLimiter.tryAcquire(anyString(), eq(3), any(Duration.class))).thenReturn(true);
        when(userRepository.findByEmailIgnoreCase("eve@example.com")).thenReturn(Optional.of(u));

        service.requestReset("eve@example.com", "1.2.3.4");

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("邮箱维度限流触发:抛 RESET_CODE_SEND_TOO_FREQUENT")
    void request_reset_email_rate_limited() {
        when(rateLimiter.tryAcquire(eq("pwd:reset:rate:" + PasswordResetService.sha256Hex("x@y.com")),
                eq(1), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> service.requestReset("x@y.com", "1.2.3.4"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_SEND_TOO_FREQUENT.getCode());

        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("IP 维度限流触发:抛 RESET_CODE_SEND_TOO_FREQUENT")
    void request_reset_ip_rate_limited() {
        when(rateLimiter.tryAcquire(anyString(), eq(1), any(Duration.class))).thenReturn(true);
        when(rateLimiter.tryAcquire(eq("pwd:reset:ip-rate:9.9.9.9"), eq(3), any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.requestReset("a@b.com", "9.9.9.9"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_SEND_TOO_FREQUENT.getCode());
    }

    // ====================== confirmReset ======================

    @Test
    @DisplayName("正确验证码:改密 + 踢所有 refresh")
    void confirm_reset_valid_code_updates_password_and_revokes_refresh() {
        User u = activeUser(1L, "alice@example.com");
        String code = "123456";
        String codeHash = PasswordResetService.sha256Hex(code);
        String emailHash = PasswordResetService.sha256Hex("alice@example.com");

        when(ops.increment("pwd:reset:attempts:" + emailHash)).thenReturn(1L);
        when(ops.get("pwd:reset:code:" + emailHash)).thenReturn(codeHash);
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("newSecret789", "ENCODED_OLD")).thenReturn(false);
        when(passwordEncoder.encode("newSecret789")).thenReturn("ENCODED_NEW");

        service.confirmReset(new ConfirmResetDto("alice@example.com", code, "newSecret789"));

        verify(userRepository).save(u);
        assertThat(u.getPassword()).isEqualTo("ENCODED_NEW");
        verify(authService, times(1)).logoutAllRefreshTokens(1L);
        verify(redis).delete("pwd:reset:code:" + emailHash);
        verify(redis).delete("pwd:reset:attempts:" + emailHash);
    }

    @Test
    @DisplayName("错误验证码:自增 attempts,抛 RESET_CODE_INVALID")
    void confirm_reset_wrong_code_increments_attempts() {
        String emailHash = PasswordResetService.sha256Hex("alice@example.com");
        when(ops.increment("pwd:reset:attempts:" + emailHash)).thenReturn(1L);
        when(ops.get("pwd:reset:code:" + emailHash)).thenReturn(PasswordResetService.sha256Hex("999999"));

        assertThatThrownBy(() -> service.confirmReset(
                new ConfirmResetDto("alice@example.com", "000000", "newSecret789")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_INVALID.getCode());

        verify(userRepository, never()).save(any());
        verify(authService, never()).logoutAllRefreshTokens(anyLong());
    }

    @Test
    @DisplayName("attempts 超过 maxAttempts(5):清掉 code + 抛 RESET_CODE_TOO_MANY_ATTEMPTS")
    void confirm_reset_over_max_attempts_rejects_and_clears_code() {
        String emailHash = PasswordResetService.sha256Hex("alice@example.com");
        when(ops.increment("pwd:reset:attempts:" + emailHash)).thenReturn(6L);

        assertThatThrownBy(() -> service.confirmReset(
                new ConfirmResetDto("alice@example.com", "000000", "newSecret789")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_TOO_MANY_ATTEMPTS.getCode());

        verify(redis).delete("pwd:reset:code:" + emailHash);
        verify(redis).delete("pwd:reset:attempts:" + emailHash);
    }

    @Test
    @DisplayName("验证码已过期(redis 无 code key):抛 RESET_CODE_INVALID")
    void confirm_reset_expired_code_throws_invalid() {
        String emailHash = PasswordResetService.sha256Hex("alice@example.com");
        when(ops.increment("pwd:reset:attempts:" + emailHash)).thenReturn(1L);
        when(ops.get("pwd:reset:code:" + emailHash)).thenReturn(null);

        assertThatThrownBy(() -> service.confirmReset(
                new ConfirmResetDto("alice@example.com", "123456", "newSecret789")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.RESET_CODE_INVALID.getCode());
    }

    @Test
    @DisplayName("邮箱大小写不敏感:ALICE@x.com 与 alice@x.com 命中同一 user")
    void confirm_reset_email_case_insensitive() {
        User u = activeUser(1L, "Alice@Example.com");
        String code = "123456";
        String codeHash = PasswordResetService.sha256Hex(code);
        String emailHashLower = PasswordResetService.sha256Hex("alice@example.com");

        when(ops.increment(anyString())).thenReturn(1L);
        when(ops.get("pwd:reset:code:" + emailHashLower)).thenReturn(codeHash);
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("ENCODED_NEW");

        service.confirmReset(new ConfirmResetDto("ALICE@Example.COM", code, "newSecret789"));

        verify(userRepository).findByEmailIgnoreCase("alice@example.com");
        verify(userRepository).save(u);
    }

    @Test
    @DisplayName("新密码与旧密码相同:抛 NEW_PASSWORD_SAME_AS_OLD")
    void confirm_reset_new_password_same_as_old() {
        User u = activeUser(1L, "alice@example.com");
        String code = "123456";
        String codeHash = PasswordResetService.sha256Hex(code);
        String emailHash = PasswordResetService.sha256Hex("alice@example.com");

        when(ops.increment(anyString())).thenReturn(1L);
        when(ops.get("pwd:reset:code:" + emailHash)).thenReturn(codeHash);
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("newSecret789", "ENCODED_OLD")).thenReturn(true);

        assertThatThrownBy(() -> service.confirmReset(
                new ConfirmResetDto("alice@example.com", code, "newSecret789")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.NEW_PASSWORD_SAME_AS_OLD.getCode());

        verify(userRepository, never()).save(any());
        verify(authService, never()).logoutAllRefreshTokens(anyLong());
    }

    // ====================== 静态工具方法 ======================

    @Test
    @DisplayName("normalizeEmail:trim + lowerCase;null / 空白抛 INVALID_PARAMS")
    void normalize_email() {
        assertThat(PasswordResetService.normalizeEmail("  ALICE@X.COM  ")).isEqualTo("alice@x.com");
        assertThat(PasswordResetService.normalizeEmail("Alice@x.com")).isEqualTo("alice@x.com");
        assertThatThrownBy(() -> PasswordResetService.normalizeEmail(null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.INVALID_PARAMS.getCode());
        assertThatThrownBy(() -> PasswordResetService.normalizeEmail("   "))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ResultCode.INVALID_PARAMS.getCode());
    }

    @Test
    @DisplayName("generateCode:6 位数字字符串")
    void generate_code_6_digits() {
        for (int i = 0; i < 50; i++) {
            String c = service.generateCode();
            assertThat(c).hasSize(6).matches("\\d{6}");
        }
    }
}

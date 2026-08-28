package com.nexusforge.client;

import com.nexusforge.config.AiProperties;
import com.nexusforge.ratelimit.RateLimit;
import com.nexusforge.ratelimit.RateLimitException;
import com.nexusforge.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitGuard")
class RateLimitGuardTest {

    private static final Long USER_ID = 42L;
    private static final String IP = "10.0.0.1";

    RateLimiter rateLimiter;
    AiProperties props;
    RateLimitGuard guard;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        props = new AiProperties();
        // 默认:enabled=true, userQps=1.0, userBurst=5, ipQps=5.0, ipBurst=20
        guard = new RateLimitGuard(rateLimiter, props);
    }

    // ──────────────────────────────────────────────
    // 开关
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("enabled=false 时")
    class Disabled {

        @Test
        @DisplayName("跳过所有校验,不调用 RateLimiter")
        void skips_check() {
            props.getRateLimit().setEnabled(false);

            guard.check(USER_ID, IP);

            verifyNoInteractions(rateLimiter);
        }
    }

    // ──────────────────────────────────────────────
    // 用户维度
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("user 维度")
    class UserDimension {

        @Test
        @DisplayName("tryAcquire 返回 false → 抛 RateLimitException")
        void user_qps_exceeded_throws() {
            when(rateLimiter.tryAcquire(anyString(), any(RateLimit.class)))
                    .thenReturn(false);

            assertThatThrownBy(() -> guard.check(USER_ID, IP))
                    .isInstanceOf(RateLimitException.class);
        }

        @Test
        @DisplayName("tryAcquire 返回 true → 放行")
        void user_qps_passes() {
            when(rateLimiter.tryAcquire(anyString(), any(RateLimit.class)))
                    .thenReturn(true);

            assertThatCode(() -> guard.check(USER_ID, IP))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("userId=null → 跳过 user 维度,只检查 IP")
        void null_user_skips_user_dimension() {
            when(rateLimiter.tryAcquire(anyString(), any(RateLimit.class)))
                    .thenReturn(true);

            guard.check(null, IP);

            // 只调了 1 次(IP 维度)
            verify(rateLimiter, times(1)).tryAcquire(anyString(), any(RateLimit.class));
        }
    }

    // ──────────────────────────────────────────────
    // IP 维度
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("IP 维度")
    class IpDimension {

        @Test
        @DisplayName("IP 超限 → 抛 RateLimitException")
        void ip_qps_exceeded_throws() {
            // user 维度通过,IP 维度超限
            when(rateLimiter.tryAcquire(anyString(), any(RateLimit.class)))
                    .thenReturn(true)   // user 通过
                    .thenReturn(false); // IP 超限

            assertThatThrownBy(() -> guard.check(USER_ID, IP))
                    .isInstanceOf(RateLimitException.class);
        }

        @Test
        @DisplayName("ip=null → 跳过 IP 维度")
        void null_ip_skips() {
            when(rateLimiter.tryAcquire(anyString(), any(RateLimit.class)))
                    .thenReturn(true);

            guard.check(USER_ID, null);

            // 只调了 1 次(user 维度)
            verify(rateLimiter, times(1)).tryAcquire(anyString(), any(RateLimit.class));
        }

        @Test
        @DisplayName("ipQps=0 → 跳过 IP 维度")
        void zero_ip_qps_skips() {
            props.getRateLimit().setIpQps(0);
            when(rateLimiter.tryAcquire(anyString(), any(RateLimit.class)))
                    .thenReturn(true);

            guard.check(USER_ID, IP);

            // 只调了 1 次(user 维度)
            verify(rateLimiter, times(1)).tryAcquire(anyString(), any(RateLimit.class));
        }
    }

    // ──────────────────────────────────────────────
    // 两维度都通过
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("user + IP 都通过 → 两次 tryAcquire 都调用")
    void both_pass_through() {
        when(rateLimiter.tryAcquire(anyString(), any(RateLimit.class)))
                .thenReturn(true);

        guard.check(USER_ID, IP);

        verify(rateLimiter, times(2)).tryAcquire(anyString(), any(RateLimit.class));
    }
}

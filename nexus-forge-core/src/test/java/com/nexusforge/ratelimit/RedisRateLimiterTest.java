package com.nexusforge.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisRateLimiter} 单元测试 —— 隔离 {@link StringRedisTemplate},验证
 * INCR + EXPIRE 语义。
 *
 * <p><b>关键不变式</b>:</p>
 * <ul>
 *   <li>首次调用 INCR 后 current=1,设 EXPIRE;后续 INCR 不刷新窗口</li>
 *   <li>current ≤ max 返回 true;current &gt; max 返回 false</li>
 *   <li>Redis 抛异常时透传(不静默吞 — 调用方决定降级)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> ops;

    @InjectMocks
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    @DisplayName("首次调用返回 true,设 EXPIRE")
    void first_call_returns_true_and_sets_expire() {
        when(ops.increment("pwd:reset:rate:abc")).thenReturn(1L);

        boolean allowed = limiter.tryAcquire("pwd:reset:rate:abc", 1, Duration.ofSeconds(60));

        assertThat(allowed).isTrue();
        verify(redis).expire(eq("pwd:reset:rate:abc"), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("current=1..max 都返回 true,均不再次设 EXPIRE")
    void within_max_returns_true_without_reexpire() {
        when(ops.increment("key")).thenReturn(1L, 2L, 3L);

        assertThat(limiter.tryAcquire("key", 3, Duration.ofSeconds(60))).isTrue();
        assertThat(limiter.tryAcquire("key", 3, Duration.ofSeconds(60))).isTrue();
        assertThat(limiter.tryAcquire("key", 3, Duration.ofSeconds(60))).isTrue();

        // EXPIRE 只在第一次(返回 1L)调用,后续 current > 1 不刷窗口
        verify(redis, times(1)).expire(eq("key"), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("current > max 返回 false,不再次设 EXPIRE")
    void over_max_returns_false_without_reexpire() {
        // 模拟:第一次(返回 1)设 EXPIRE;第二次 INCR(返回 4 = max+1)拒绝
        when(ops.increment("k"))
                .thenReturn(1L)   // 首次:current=1 ≤ max=3 → true
                .thenReturn(2L)
                .thenReturn(3L)
                .thenReturn(4L);  // 第 4 次:current=4 > max=3 → false

        assertThat(limiter.tryAcquire("k", 3, Duration.ofSeconds(60))).isTrue();
        assertThat(limiter.tryAcquire("k", 3, Duration.ofSeconds(60))).isTrue();
        assertThat(limiter.tryAcquire("k", 3, Duration.ofSeconds(60))).isTrue();
        assertThat(limiter.tryAcquire("k", 3, Duration.ofSeconds(60))).isFalse();

        // EXPIRE 仍只在第一次设
        verify(redis, times(1)).expire(eq("k"), eq(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("current=null(Redis 异常 / 写入 null)时返回 false,防御式处理")
    void redis_returns_null_treated_as_over_max() {
        when(ops.increment("k")).thenReturn(null);

        // null 不会抛 NullPointerException,而是返回 false(更安全的失败语义)
        assertThat(limiter.tryAcquire("k", 1, Duration.ofSeconds(60))).isFalse();

        // 短路:不会调到 expire
        verify(redis, never()).expire(eq("k"), eq(Duration.ofSeconds(60)));
    }
}

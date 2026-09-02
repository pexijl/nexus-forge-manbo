package com.nexusforge.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Lock Commit 1 单测 —— {@link RedisDistributedLock} 隔离 {@link StringRedisTemplate},
 * 验证 SET NX PX / Lua 释放两条路径的语义。
 *
 * <h3>覆盖矩阵</h3>
 * <ol>
 *   <li>{@code Acquire}     setIfAbsent 返 true → 返 Optional(token)</li>
 *   <li>{@code Acquire}     setIfAbsent 返 false → 返 Optional.empty</li>
 *   <li>{@code Acquire}     setIfAbsent 返 null(网络异常边界)→ 返 Optional.empty</li>
 *   <li>{@code Acquire}     lease 透传给 setIfAbsent(用 Duration,不丢精度)</li>
 *   <li>{@code Release}     Lua 返 1 → 返 true</li>
 *   <li>{@code Release}     Lua 返 0(lease 过期 / 别人持有)→ 返 false</li>
 *   <li>{@code Release}     Lua 返 null → 返 false(不抛)</li>
 *   <li>{@code Release}     透传 token 字符串给 Lua(不是 token.toString())</li>
 *   <li>{@code KeyPrefix}   key 不带前缀 → 自动加 "lock:"</li>
 *   <li>{@code KeyPrefix}   key 已带 "lock:" 前缀 → 不重复加</li>
 *   <li>{@code KeyPrefix}   空 key → IllegalArgumentException</li>
 *   <li>{@code Token}       每次 tryLock 生成不同 token(并发场景基础)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RedisDistributedLockTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> ops;

    private DistributedLockProperties props;
    private RedisDistributedLock lock;

    @BeforeEach
    void setUp() {
        props = new DistributedLockProperties();
        props.setKeyPrefix("lock:");
        lock = new RedisDistributedLock(redis, props);
        // lenient:Release / Token / KeyPrefix 子测试不用 ops,但 Acquire 全用
        org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(ops);
    }

    // ─────────────────────────────────────────────
    //  Acquire
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Acquire")
    class Acquire {

        @Test
        @DisplayName("setIfAbsent 返 true → 返 Optional(token)")
        void acquire_success() {
            when(ops.setIfAbsent(eq("lock:upload:1"), anyString(), eq(Duration.ofSeconds(30))))
                    .thenReturn(true);

            Optional<LockToken> result = lock.tryLock("upload:1", Duration.ofSeconds(30));

            assertThat(result).isPresent();
            assertThat(result.get().value()).isNotBlank();
        }

        @Test
        @DisplayName("setIfAbsent 返 false → 返 Optional.empty(非阻塞)")
        void acquire_busy() {
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

            Optional<LockToken> result = lock.tryLock("busy", Duration.ofSeconds(30));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("setIfAbsent 返 null → 返 Optional.empty(不抛 NPE)")
        void acquire_null_response_treated_as_busy() {
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

            Optional<LockToken> result = lock.tryLock("null", Duration.ofSeconds(30));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("lease 透传为 Duration(精度到毫秒,不走 EX 秒级)")
        void acquire_lease_passes_duration() {
            when(ops.setIfAbsent(anyString(), anyString(), eq(Duration.ofMillis(500))))
                    .thenReturn(true);

            lock.tryLock("sub-sec", Duration.ofMillis(500));

            verify(ops, times(1)).setIfAbsent(eq("lock:sub-sec"), anyString(),
                    eq(Duration.ofMillis(500)));
        }
    }

    // ─────────────────────────────────────────────
    //  Release
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Release")
    class Release {

        @Test
        @DisplayName("Lua 返 1 → 返 true(本 holder 成功释放)")
        void release_match() {
            LockToken t = LockToken.random();
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(1L);

            assertThat(lock.unlock("upload:1", t)).isTrue();
        }

        @Test
        @DisplayName("Lua 返 0(lease 过期 / 别人持有)→ 返 false,不抛")
        void release_miss() {
            LockToken t = LockToken.random();
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(0L);

            assertThat(lock.unlock("upload:1", t)).isFalse();
        }

        @Test
        @DisplayName("Lua 返 null(网络异常边界)→ 返 false,不抛")
        void release_null_response() {
            LockToken t = LockToken.random();
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(null);

            assertThat(lock.unlock("upload:1", t)).isFalse();
        }

        @Test
        @DisplayName("token.value() 透传给 Lua(不是 toString 包装)")
        void release_passes_token_value_not_toString() {
            LockToken t = new LockToken("plain-uuid-1234");
            when(redis.execute(any(RedisScript.class), anyList(), eq("plain-uuid-1234")))
                    .thenReturn(1L);

            lock.unlock("upload:1", t);

            // value 匹配,toString 会是 "LockToken[plain-uuid-1234]" —— 不能这样
            verify(redis, times(1)).execute(any(RedisScript.class), anyList(), eq("plain-uuid-1234"));
        }
    }

    // ─────────────────────────────────────────────
    //  KeyPrefix
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("KeyPrefix")
    class KeyPrefix {

        @Test
        @DisplayName("key 不带 'lock:' 前缀 → 自动加 'lock:'")
        void auto_prefix() {
            when(ops.setIfAbsent(eq("lock:upload:1"), anyString(), any(Duration.class)))
                    .thenReturn(true);

            lock.tryLock("upload:1", Duration.ofSeconds(30));

            verify(ops, times(1)).setIfAbsent(eq("lock:upload:1"), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("key 已带 'lock:' 前缀 → 不重复加")
        void already_prefixed_not_doubled() {
            when(ops.setIfAbsent(eq("lock:upload:1"), anyString(), any(Duration.class)))
                    .thenReturn(true);

            lock.tryLock("lock:upload:1", Duration.ofSeconds(30));

            // 关键:不能变成 "lock:lock:upload:1"
            verify(ops, times(1)).setIfAbsent(eq("lock:upload:1"), anyString(), any(Duration.class));
            verify(ops, never()).setIfAbsent(eq("lock:lock:upload:1"), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("空 key → IllegalArgumentException")
        void empty_key_rejected() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> lock.tryLock("", Duration.ofSeconds(30)));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> lock.tryLock(null, Duration.ofSeconds(30)));
        }
    }

    // ─────────────────────────────────────────────
    //  Token uniqueness
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Token")
    class Token {

        @Test
        @DisplayName("连续两次 tryLock 返回不同 token(UUID 随机性)")
        void unique_tokens() {
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

            LockToken a = lock.tryLock("k", Duration.ofSeconds(30)).orElseThrow();
            LockToken b = lock.tryLock("k", Duration.ofSeconds(30)).orElseThrow();

            assertThat(a.value()).isNotEqualTo(b.value());
        }
    }
}

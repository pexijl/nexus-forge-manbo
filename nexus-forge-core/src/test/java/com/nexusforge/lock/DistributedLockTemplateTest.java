package com.nexusforge.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Lock Commit 2 单测 —— {@link DistributedLockTemplate} 帮手层。
 *
 * <p>Mockito 隔离 {@link DistributedLock},用 {@link SimpleMeterRegistry} 验证
 * Micrometer 指标。覆盖:</p>
 * <ul>
 *   <li>tryLockOrThrow 失败 → 抛 {@link LockAcquireException} + 指标 miss</li>
 *   <li>业务抛异常 → 锁仍释放(try-finally)</li>
 *   <li>unlock 返 false(lease 过期)→ log warn 不抛 + 指标 release miss</li>
 *   <li>runWithLock 无返回值场景</li>
 *   <li>tryLockWithWait 立即拿到 → 返 supplier 结果</li>
 *   <li>tryLockWithWait 轮询 N 次后拿到 → 返 supplier 结果,等待时长计入 latency</li>
 *   <li>tryLockWithWait 等待超时 → 抛 LockAcquireException + 指标 acquire miss</li>
 *   <li>MeterRegistry=null 时不抛(降级路径)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockTemplateTest {

    @Mock private DistributedLock lock;

    private MeterRegistry meterRegistry;
    private DistributedLockTemplate template;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        template = new DistributedLockTemplate(lock, meterRegistry);
    }

    // ─────────────────────────────────────────────
    //  lock(key, lease, supplier)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("lock")
    class LockHelper {

        @Test
        @DisplayName("拿不到 → 抛 LockAcquireException,不调 unlock")
        void acquire_failure_throws() {
            when(lock.tryLock(eq("k"), any(Duration.class))).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    template.lock("k", Duration.ofSeconds(30), () -> "result"))
                    .isInstanceOf(LockAcquireException.class)
                    .hasMessageContaining("k");

            verify(lock, never()).unlock(any(), any());
            // 指标 acquire{busy=1,miss=1} 都累加(busy 是每次轮询,miss 是最终失败)
            // 简单起见只验证 miss ≥ 1
            assertThat(counterCount("lock.acquire", "result", "miss")).isGreaterThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("拿得到 + supplier 抛异常 → unlock 仍调(try-finally)")
        void supplier_throws_still_releases() {
            LockToken t = LockToken.random();
            when(lock.tryLock(eq("k"), any(Duration.class))).thenReturn(Optional.of(t));
            when(lock.unlock("k", t)).thenReturn(true);

            assertThatThrownBy(() ->
                    template.lock("k", Duration.ofSeconds(30), () -> {
                        throw new IllegalStateException("biz fail");
                    }))
                    .isInstanceOf(IllegalStateException.class);

            verify(lock, times(1)).unlock("k", t);
            // 指标 acquire.success + release.success
            assertThat(counterCount("lock.acquire", "result", "success")).isEqualTo(1.0);
            assertThat(counterCount("lock.release", "result", "success")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("unlock 返 false(lease 过期)→ log warn,主业务不挂")
        void unlock_miss_does_not_throw() {
            LockToken t = LockToken.random();
            when(lock.tryLock(eq("k"), any(Duration.class))).thenReturn(Optional.of(t));
            when(lock.unlock("k", t)).thenReturn(false);  // lease expired

            // 主业务正常返回
            String result = template.lock("k", Duration.ofSeconds(30), () -> "ok");
            assertThat(result).isEqualTo("ok");

            // 指标 release.miss
            assertThat(counterCount("lock.release", "result", "miss")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("unlock 自身抛异常 → log warn 不外抛,主业务返值不变")
        void unlock_throws_does_not_propagate() {
            LockToken t = LockToken.random();
            when(lock.tryLock(eq("k"), any(Duration.class))).thenReturn(Optional.of(t));
            when(lock.unlock("k", t)).thenThrow(new RuntimeException("redis down"));

            String result = template.lock("k", Duration.ofSeconds(30), () -> "ok");
            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("runWithLock 无返回值场景")
        void run_with_lock_void() {
            LockToken t = LockToken.random();
            when(lock.tryLock(eq("k"), any(Duration.class))).thenReturn(Optional.of(t));
            when(lock.unlock(eq("k"), any())).thenReturn(true);
            AtomicBoolean ran = new AtomicBoolean(false);

            template.runWithLock("k", Duration.ofSeconds(30), () -> ran.set(true));

            assertThat(ran).isTrue();
            verify(lock, times(1)).unlock(eq("k"), any());
        }
    }

    // ─────────────────────────────────────────────
    //  tryLockWithWait
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("tryLockWithWait")
    class TryLockWithWait {

        @Test
        @DisplayName("立即拿到 → 返 supplier 结果,只轮询 1 次")
        void acquire_immediate() {
            LockToken t = LockToken.random();
            when(lock.tryLock(eq("k"), any(Duration.class))).thenReturn(Optional.of(t));
            when(lock.unlock(eq("k"), any())).thenReturn(true);
            AtomicInteger calls = new AtomicInteger();

            String result = template.tryLockWithWait("k",
                    Duration.ofSeconds(2), Duration.ofSeconds(30),
                    () -> {
                        calls.incrementAndGet();
                        return "fast";
                    });

            assertThat(result).isEqualTo("fast");
            assertThat(calls).hasValue(1);
            verify(lock, times(1)).tryLock(eq("k"), any(Duration.class));
        }

        @Test
        @DisplayName("第 3 次轮询才拿到 → 返 supplier 结果,记录 latency")
        void acquire_after_polling() {
            LockToken t = LockToken.random();
            // 前两次 busy,第三次成功
            when(lock.tryLock(eq("k"), any(Duration.class)))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(t));
            when(lock.unlock(eq("k"), any())).thenReturn(true);

            String result = template.tryLockWithWait("k",
                    Duration.ofSeconds(2), Duration.ofSeconds(30),
                    () -> "polled");

            assertThat(result).isEqualTo("polled");
            verify(lock, times(3)).tryLock(eq("k"), any(Duration.class));
            // 指标:acquire success 1 + busy 2 + miss 不计
            assertThat(counterCount("lock.acquire", "result", "success")).isEqualTo(1.0);
            assertThat(counterCount("lock.acquire", "result", "busy")).isEqualTo(2.0);
            // acquire.latency timer 至少有一次记录
            Timer latency = meterRegistry.find(DistributedLockTemplate.METRIC_ACQUIRE_LATENCY).timer();
            assertThat(latency).isNotNull();
            assertThat(latency.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("等待超时(始终 busy)→ 抛 LockAcquireException + 指标 miss")
        void acquire_timeout() {
            when(lock.tryLock(eq("k"), any(Duration.class))).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    template.tryLockWithWait("k",
                            Duration.ofMillis(150),  // 短超时,3 轮 polling 后超时
                            Duration.ofSeconds(30),
                            () -> "never"))
                    .isInstanceOf(LockAcquireException.class)
                    .hasMessageContaining("timeout");

            verify(lock, never()).unlock(any(), any());
            assertThat(counterCount("lock.acquire", "result", "miss")).isEqualTo(1.0);
        }
    }

    // ─────────────────────────────────────────────
    //  MeterRegistry 降级
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("NullMeterRegistry")
    class NullMeterRegistry {

        @Test
        @DisplayName("meterRegistry=null 时不抛(主业务正常)")
        void works_without_metrics() {
            DistributedLockTemplate tmpl = new DistributedLockTemplate(lock, null);
            LockToken t = LockToken.random();
            when(lock.tryLock(any(), any(Duration.class))).thenReturn(Optional.of(t));
            when(lock.unlock(any(), any())).thenReturn(true);

            String result = tmpl.lock("k", Duration.ofSeconds(30), () -> "ok");

            assertThat(result).isEqualTo("ok");
        }
    }

    // ─────────────────────────────────────────────
    //  Helper
    // ─────────────────────────────────────────────

    private double counterCount(String name, String tagKey, String tagValue) {
        Counter c = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0.0 : c.count();
    }
}

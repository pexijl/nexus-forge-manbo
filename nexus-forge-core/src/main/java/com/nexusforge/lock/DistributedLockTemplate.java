package com.nexusforge.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁的高阶门面 —— 在 {@link DistributedLock} SPI 之上提供:
 * <ul>
 *   <li><b>自动释放</b>{@link #lock(String, Duration, Supplier)} —— try-finally
 *       兜底,业务只写 supplier,不必关心 unlock</li>
 *   <li><b>带等待</b>{@link #tryLockWithWait(String, Duration, Duration, Supplier)}
 *       —— 50ms 轮询,简单可靠,适合短业务</li>
 *   <li><b>Micrometer 指标</b> —— 4 counter + 1 timer,接 Prometheus / Actuator</li>
 * </ul>
 *
 * <h3>使用模式</h3>
 * <pre>{@code
 * // 模式 1:有返回值的同步锁
 * FileMetadata entity = lockTemplate.lock(
 *     "upload:avatar:" + userId, Duration.ofSeconds(30),
 *     () -> fileService.uploadByBiz(AVATAR, userId, ...));
 *
 * // 模式 2:无返回值的同步锁
 * lockTemplate.runWithLock(
 *     "ai:chat:" + userId, Duration.ofSeconds(10),
 *     () -> chatService.process(userId, msg));
 *
 * // 模式 3:带等待(最多等 2s,lease 30s)
 * lockTemplate.tryLockWithWait(
 *     "ai:chat:" + userId, Duration.ofSeconds(2), Duration.ofSeconds(30),
 *     () -> chatService.process(userId, msg));
 * }</pre>
 *
 * <h3>指标</h3>
 * <ul>
 *   <li>{@code lock.acquire{result=success|busy|miss}} —— 拿锁结果</li>
 *   <li>{@code lock.release{result=success|miss}} —— 释放结果
 *       (miss 意味着 lease 过期被 Redis 回收)</li>
 *   <li>{@code lock.acquire.latency} —— 拿锁耗时(含等待轮询)timer</li>
 *   <li>{@code lock.contention{result=hit|miss}} —— 实际竞争程度</li>
 * </ul>
 *
 * <p><b>注意</b>:{@link MeterRegistry} 走构造器注入,允许 {@code null}
 * (单测 / 没启 actuator 时不埋点,主链路不受影响)。生产 web 模块有 actuator,
 * 自动配 {@code SimpleMeterRegistry},指标会进 /actuator/metrics。</p>
 */
@Slf4j
@Component
public class DistributedLockTemplate {

    public static final String METRIC_ACQUIRE = "lock.acquire";
    public static final String METRIC_RELEASE = "lock.release";
    public static final String METRIC_CONTENTION = "lock.contention";
    public static final String METRIC_ACQUIRE_LATENCY = "lock.acquire.latency";

    private static final String TAG_RESULT = "result";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_BUSY = "busy";
    private static final String RESULT_MISS = "miss";
    private static final String RESULT_HIT = "hit";

    /** 50ms 轮询间隔 —— 短延迟 + 少 Redis 压力 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final DistributedLock lock;
    private final MeterRegistry meterRegistry;

    public DistributedLockTemplate(DistributedLock lock, MeterRegistry meterRegistry) {
        this.lock = lock;
        this.meterRegistry = meterRegistry;
    }

    // ─────────────────────────────────────────────
    //  公开 API
    // ─────────────────────────────────────────────

    /**
     * 拿不到立即抛(常用模式)—— try-finally 自动释放。
     *
     * @param key       锁 key
     * @param lease     持有时长
     * @param supplier  受保护的业务
     * @throws LockAcquireException 拿不到锁
     */
    public <T> T lock(String key, Duration lease, Supplier<T> supplier) {
        long startNs = System.nanoTime();
        Optional<LockToken> tokenOpt = lock.tryLock(key, lease);
        if (tokenOpt.isEmpty()) {
            recordAcquire(key, RESULT_MISS, System.nanoTime() - startNs);
            throw new LockAcquireException("acquire lock failed: " + key);
        }
        recordAcquire(key, RESULT_SUCCESS, System.nanoTime() - startNs);
        LockToken token = tokenOpt.get();
        try {
            return supplier.get();
        } finally {
            releaseIfHeld(key, token);
        }
    }

    /**
     * 无返回值的便捷方法。
     */
    public void runWithLock(String key, Duration lease, Runnable runnable) {
        lock(key, lease, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 带等待的 tryLock —— 拿不到时轮询,直到 waitTime 到期或拿到。
     *
     * <p>轮询间隔 50ms;超 waitTime 抛 {@link LockAcquireException}。</p>
     */
    public <T> T tryLockWithWait(String key, Duration waitTime, Duration lease,
                                 Supplier<T> supplier) {
        long startNs = System.nanoTime();
        Optional<LockToken> tokenOpt = acquireWithWait(key, waitTime, lease, startNs);
        LockToken token = tokenOpt.orElseThrow(() ->
                new LockAcquireException("acquire lock timeout: " + key
                        + " (waited " + waitTime.toMillis() + "ms)"));
        try {
            return supplier.get();
        } finally {
            releaseIfHeld(key, token);
        }
    }

    // ─────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────

    private Optional<LockToken> acquireWithWait(String key, Duration waitTime,
                                                Duration lease, long startNs) {
        long deadlineNs = startNs + waitTime.toNanos();
        while (true) {
            Optional<LockToken> t = lock.tryLock(key, lease);
            if (t.isPresent()) {
                recordAcquire(key, RESULT_SUCCESS, System.nanoTime() - startNs);
                return t;
            }
            // miss 这一轮
            recordAcquire(key, RESULT_BUSY, 0L);
            if (System.nanoTime() >= deadlineNs) {
                recordAcquire(key, RESULT_MISS, System.nanoTime() - startNs);
                return Optional.empty();
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordAcquire(key, RESULT_MISS, System.nanoTime() - startNs);
                return Optional.empty();
            }
        }
    }

    private void releaseIfHeld(String key, LockToken token) {
        try {
            boolean released = lock.unlock(key, token);
            recordRelease(key, released ? RESULT_SUCCESS : RESULT_MISS);
        } catch (Exception e) {
            // 释放失败不应影响主业务(锁会 lease 过期自动回收)
            log.warn("[lock] unlock threw, key={} token={} err={}", key, token, e.getMessage());
        }
    }

    private void recordAcquire(String key, String result, long latencyNs) {
        if (meterRegistry == null) return;
        Counter.builder(METRIC_ACQUIRE)
                .tag(TAG_RESULT, result)
                .description("Distributed lock acquisition attempts")
                .register(meterRegistry)
                .increment();
        if (latencyNs > 0) {
            Timer.builder(METRIC_ACQUIRE_LATENCY)
                    .description("Lock acquisition latency including wait polling")
                    .register(meterRegistry)
                    .record(latencyNs, TimeUnit.NANOSECONDS);
        }
        if (RESULT_SUCCESS.equals(result)) {
            Counter.builder(METRIC_CONTENTION)
                    .tag(TAG_RESULT, RESULT_HIT)
                    .register(meterRegistry)
                    .increment();
        } else if (RESULT_MISS.equals(result)) {
            Counter.builder(METRIC_CONTENTION)
                    .tag(TAG_RESULT, RESULT_MISS)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void recordRelease(String key, String result) {
        if (meterRegistry == null) return;
        Counter.builder(METRIC_RELEASE)
                .tag(TAG_RESULT, result)
                .description("Distributed lock release outcomes (miss = lease expired)")
                .register(meterRegistry)
                .increment();
    }
}

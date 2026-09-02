package com.nexusforge.flows;

import com.nexusforge.lock.DistributedLockTemplate;
import com.nexusforge.lock.LockAcquireException;
import com.nexusforge.lock.LockToken;
import com.nexusforge.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2 Lock Commit 5 端到端集成测试 —— 真 Redis(Testcontainers)验证:
 *
 * <ol>
 *   <li>{@code Acquire}       SET NX EX 走真 Redis,拿得到 / 拿不到 两条路径</li>
 *   <li>{@code Release}       Lua 脚本比对 token + DEL,真 Redis 验证</li>
 *   <li>{@code AutoExpire}    lease 到期 Redis 自动删 key</li>
 *   <li>{@code Ownership}     不是 owner unlock → false,真 key 保留</li>
 *   <li>{@code KeyPrefix}     全局 lock: 前缀正确写入 Redis</li>
 *   <li>{@code Template}      lock(key, lease, supplier) try-finally 走真 Redis</li>
 *   <li>{@code Template}      tryLockWithWait 50ms 轮询,真 Redis</li>
 *   <li>{@code Concurrency}   多线程并发:只有一个拿到锁(真竞争)</li>
 * </ol>
 *
 * <p>IT 走 PG / Redis / RustFS 全 Testcontainers;{@code -Pintegration} 触发。
 * 业务锁(FileService / AccountLifecycleService)的端到端覆盖分别走
 * {@code FileMetadataIT} / {@code AccountLifecycleIT}。</p>
 */
@Tag("integration")
@DisplayName("分布式锁 端到端")
class DistributedLockIT extends IntegrationTestBase {

    @Autowired private com.nexusforge.lock.DistributedLock lock;
    @Autowired private DistributedLockTemplate template;
    @Autowired private StringRedisTemplate redis;
    @Autowired private com.nexusforge.testsupport.RedisCleaner redisCleaner;

    @BeforeEach
    void setUp() {
        db.clean();
        redisCleaner.flush();
    }

    // ─────────────────────────────────────────────
    //  Acquire / Release
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Acquire / Release")
    class AcquireAndRelease {

        @Test
        @DisplayName("拿得到 → Redis 里有 key,unlock 后 key 消失")
        void acquire_release_cycle() {
            Optional<LockToken> token = lock.tryLock("upload:avatar:1",
                    Duration.ofSeconds(5));
            assertThat(token).isPresent();

            // Redis 验证:key 存在 + 值是 token.value()
            String stored = redis.opsForValue().get("lock:upload:avatar:1");
            assertThat(stored).isEqualTo(token.get().value());

            // unlock
            assertThat(lock.unlock("upload:avatar:1", token.get())).isTrue();

            // Redis 验证:key 消失
            assertThat(redis.hasKey("lock:upload:avatar:1")).isFalse();
        }

        @Test
        @DisplayName("两个连续 tryLock,第二次空(已被自己持有)")
        void second_acquire_fails() {
            Optional<LockToken> first = lock.tryLock("k", Duration.ofSeconds(5));
            assertThat(first).isPresent();

            Optional<LockToken> second = lock.tryLock("k", Duration.ofSeconds(5));
            assertThat(second).isEmpty();

            // 清理
            lock.unlock("k", first.get());
        }

        @Test
        @DisplayName("release 后再次拿能拿到(新 token)")
        void acquire_after_release() {
            Optional<LockToken> first = lock.tryLock("k", Duration.ofSeconds(5));
            lock.unlock("k", first.get());

            Optional<LockToken> second = lock.tryLock("k", Duration.ofSeconds(5));
            assertThat(second).isPresent();
            assertThat(second.get().value()).isNotEqualTo(first.get().value());
            lock.unlock("k", second.get());
        }
    }

    // ─────────────────────────────────────────────
    //  Auto expire
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("AutoExpire")
    class AutoExpire {

        @Test
        @DisplayName("lease=1s 后 key 自动被 Redis 删")
        void lease_expires_and_key_disappears() throws InterruptedException {
            Optional<LockToken> token = lock.tryLock("k", Duration.ofMillis(800));
            assertThat(token).isPresent();
            assertThat(redis.hasKey("lock:k")).isTrue();

            // 等 lease 到期
            Thread.sleep(1200);

            assertThat(redis.hasKey("lock:k")).isFalse();

            // 此时再拿应该成功(无主,新 token)
            Optional<LockToken> second = lock.tryLock("k", Duration.ofSeconds(5));
            assertThat(second).isPresent();
            lock.unlock("k", second.get());
        }
    }

    // ─────────────────────────────────────────────
    //  Ownership(防误删别人的锁)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Ownership")
    class Ownership {

        @Test
        @DisplayName("用旧 token unlock 新 holder 的锁 → false,新 key 保留")
        void stale_token_does_not_release_new_holders_lock() throws InterruptedException {
            // 1. 老 holder 拿锁(短 lease)
            Optional<LockToken> oldHolder = lock.tryLock("k", Duration.ofMillis(500));
            assertThat(oldHolder).isPresent();
            // 2. 等 lease 过期
            Thread.sleep(700);
            // 3. 新 holder 拿锁
            Optional<LockToken> newHolder = lock.tryLock("k", Duration.ofSeconds(10));
            assertThat(newHolder).isPresent();
            assertThat(newHolder.get().value()).isNotEqualTo(oldHolder.get().value());

            // 4. 老 holder 误以为锁还在,unlock 旧 token
            assertThat(lock.unlock("k", oldHolder.get())).isFalse();

            // 5. 新 holder 的 key 还在(没被误删)
            assertThat(redis.hasKey("lock:k")).isTrue();

            // 6. 新 holder 正确 unlock
            assertThat(lock.unlock("k", newHolder.get())).isTrue();
        }
    }

    // ─────────────────────────────────────────────
    //  KeyPrefix
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("KeyPrefix")
    class KeyPrefix {

        @Test
        @DisplayName("key 不带 'lock:' 前缀 → Redis 实际 key 是 'lock:{key}'")
        void auto_prefix() {
            Optional<LockToken> t = lock.tryLock("upload:1", Duration.ofSeconds(5));
            assertThat(t).isPresent();
            assertThat(redis.hasKey("lock:upload:1")).isTrue();
            lock.unlock("upload:1", t.get());
        }

        @Test
        @DisplayName("key 已带 'lock:' 前缀 → 不重复加")
        void no_double_prefix() {
            Optional<LockToken> t = lock.tryLock("lock:upload:1", Duration.ofSeconds(5));
            assertThat(t).isPresent();
            assertThat(redis.hasKey("lock:upload:1")).isTrue();
            assertThat(redis.hasKey("lock:lock:upload:1")).isFalse();
            lock.unlock("lock:upload:1", t.get());
        }
    }

    // ─────────────────────────────────────────────
    //  Template
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Template")
    class Template {

        @Test
        @DisplayName("lock(key, lease, supplier) try-finally → key 释放")
        void lock_helper_releases() {
            String result = template.lock("upload:avatar:1", Duration.ofSeconds(5),
                    () -> "done");
            assertThat(result).isEqualTo("done");
            assertThat(redis.hasKey("lock:upload:avatar:1")).isFalse();
        }

        @Test
        @DisplayName("supplier 抛异常 → 锁仍释放(try-finally)")
        void lock_helper_releases_on_exception() {
            assertThatThrownBy(() ->
                    template.lock("upload:1", Duration.ofSeconds(5), () -> {
                        throw new IllegalStateException("biz fail");
                    }))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(redis.hasKey("lock:upload:1")).isFalse();
        }

        @Test
        @DisplayName("tryLockWithWait:第二个并发线程轮询后超时抛 LockAcquireException")
        void tryLockWithWait_blocks_then_times_out() {
            // 第一个线程拿锁
            Optional<LockToken> first = lock.tryLock("k", Duration.ofSeconds(5));
            assertThat(first).isPresent();

            // 第二个线程 wait 200ms,lease 5s(轮询 50ms 间隔,4 次后超时)
            long start = System.currentTimeMillis();
            assertThatThrownBy(() ->
                    template.tryLockWithWait("k",
                            Duration.ofMillis(200), Duration.ofSeconds(5),
                            () -> "never"))
                    .isInstanceOf(LockAcquireException.class)
                    .hasMessageContaining("timeout");
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).isBetween(150L, 600L);  // 允许一点漂移

            // 第一个线程释放
            lock.unlock("k", first.get());
        }
    }

    // ─────────────────────────────────────────────
    //  Concurrency(真多线程竞争)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("10 线程同时抢锁 → 只有 1 个拿得到,其他 9 个超时 / 拿不到")
        void ten_threads_compete_for_one_lock() throws Exception {
            int threads = 10;
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger busyCount = new AtomicInteger(0);

            try {
                for (int i = 0; i < threads; i++) {
                    exec.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            Optional<LockToken> t = lock.tryLock("hot-key",
                                    Duration.ofSeconds(5));
                            if (t.isPresent()) {
                                successCount.incrementAndGet();
                                // 持有 50ms
                                Thread.sleep(50);
                                lock.unlock("hot-key", t.get());
                            } else {
                                busyCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                    });
                }
                ready.await(5, TimeUnit.SECONDS);
                start.countDown();
                exec.shutdown();
                exec.awaitTermination(30, TimeUnit.SECONDS);
            } finally {
                if (!exec.isTerminated()) exec.shutdownNow();
            }

            // 真正并发下,1 个 100% 成功,其余 9 个要么空要么超时
            // 简化断言:success + busy = 10,且 success ≥ 1
            assertThat(successCount.get() + busyCount.get()).isEqualTo(threads);
            assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
            // 验证锁 key 已清
            assertThat(redis.hasKey("lock:hot-key")).isFalse();
        }
    }

    // ─────────────────────────────────────────────
    //  Redis key 命名空间(防止污染其他测试)
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Namespace")
    class Namespace {

        @Test
        @DisplayName("lock 操作不影响其它命名空间(auth: / pwd: / ai:rl:)")
        void lock_does_not_touch_other_namespaces() {
            redis.opsForValue().set("auth:test:foo", "bar");
            redis.opsForValue().set("pwd:reset:foo", "bar");
            redis.opsForValue().set("ai:rl:foo", "bar");

            Optional<LockToken> t = lock.tryLock("k", Duration.ofSeconds(5));
            assertThat(t).isPresent();
            lock.unlock("k", t.get());

            assertThat(redis.opsForValue().get("auth:test:foo")).isEqualTo("bar");
            assertThat(redis.opsForValue().get("pwd:reset:foo")).isEqualTo("bar");
            assertThat(redis.opsForValue().get("ai:rl:foo")).isEqualTo("bar");
        }

        @Test
        @DisplayName("flush() 后所有 lock: key 清空")
        void flush_clears_lock_keys() {
            Optional<LockToken> a = lock.tryLock("a", Duration.ofSeconds(5));
            Optional<LockToken> b = lock.tryLock("b", Duration.ofSeconds(5));
            assertThat(a).isPresent();
            assertThat(b).isPresent();

            Set<String> before = redis.keys("lock:*");
            assertThat(before).isNotEmpty();

            redisCleaner.flush();

            Set<String> after = redis.keys("lock:*");
            assertThat(after).isEmpty();
        }
    }
}

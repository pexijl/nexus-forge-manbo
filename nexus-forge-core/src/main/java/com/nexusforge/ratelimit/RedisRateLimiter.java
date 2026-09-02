package com.nexusforge.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 实现的固定窗口限流器 —— INCR + EXPIRE NX 模式。
 *
 * <p>与 {@link RateLimit} 注解(本地令牌桶)互补:
 * 本地令牌桶适合 QPS 维度(秒级突发),本类适合"窗口内总次数"(如 60s 内只 1 次,
 * 同 IP 60s 内最多 3 次)。</p>
 *
 * <p><b>并发模型</b>:Redis 单线程执行 INCR,故并发请求下计数严格自增。
 * 仅当计数为 1 时调用 EXPIRE —— 后续 INCR 不刷新窗口,避免"每次请求都重置窗口"
 * 导致限流形同虚设。</p>
 *
 * <p><b>失败语义</b>:Redis 抛异常时 {@code tryAcquire} 直接让异常透传,
 * 由调用方决定降级(密码重置场景倾向"宁可让用户发不了,也不能让攻击者绕过限流")。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate redis;

    /**
     * 尝试获取一次"许可"。窗口内累计请求数 ≤ max 时返回 true,超过 max 返回 false。
     *
     * @param key    Redis key(建议带业务前缀,如 {@code pwd:reset:rate:xxx})
     * @param max    窗口内允许的最大次数(包含本次请求)
     * @param window 窗口大小;仅首次 INCR 时设置 TTL
     * @return true 允许通过;false 已超限
     */
    public boolean tryAcquire(String key, int max, Duration window) {
        Long current = redis.opsForValue().increment(key);
        if (current != null && current == 1L) {
            // 首次写入,设置 TTL;后续 INCR 不刷新窗口
            redis.expire(key, window);
        }
        boolean allowed = current != null && current <= max;
        if (!allowed) {
            log.debug("[rate-limit] blocked key={} current={} max={}", key, current, max);
        }
        return allowed;
    }
}

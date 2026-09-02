package com.nexusforge.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Redis 的分布式锁实现 —— {@code SET key token NX PX leaseMs} + Lua 比对释放。
 *
 * <h3>Redis 命令</h3>
 * <ul>
 *   <li><b>拿锁</b>:{@code SET key token NX PX leaseMs} —— NX 保证互斥,
 *       PX 毫秒级 TTL(比 EX 秒级更精细,适合短业务),token 是
 *       {@link LockToken#random()} 生成的 UUID,保证唯一性</li>
 *   <li><b>放锁</b>:Lua 脚本比对 token 后 DEL,保证只释放自己的锁;
 *       比对失败静默返 0(本 holder 可能 lease 过期已被别人持有,
 *       删了别人的锁会破坏互斥语义)</li>
 * </ul>
 *
 * <h3>Lua 放锁脚本</h3>
 * <pre>
 * if redis.call("get", KEYS[1]) == ARGV[1] then
 *     return redis.call("del", KEYS[1])
 * else
 *     return 0
 * end
 * </pre>
 *
 * <h3>failure 模式</h3>
 * <ul>
 *   <li>Redis 连不上 → 透传,业务决定降级</li>
 *   <li>SET NX 拿不到 → 返 {@link Optional#empty()}(非阻塞)</li>
 *   <li>unlock 比对失败 → 返 false,不抛(上层可 log warn)</li>
 * </ul>
 *
 * <h3>未实现(留 TODO)</h3>
 * <ul>
 *   <li>重入(同线程可重入,需 ThreadLocal token 栈)</li>
 *   <li>Watchdog 自动续约(类似 Redisson 锁续期)</li>
 *   <li>PubSub 通知(目前 acquire 失败立即返,不阻塞等;wait + 轮询在
 *       {@code DistributedLockTemplate} 帮手层补)</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisDistributedLock implements DistributedLock {

    /**
     * 比对 + 删除的原子 Lua 脚本。KEYS[1] = lock key,ARGV[1] = token。
     * 返 1 表示删除成功(本 holder 释放),0 表示 key 不存在或 token 不匹配
     * (lease 过期 / 已被别人持有)。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final DistributedLockProperties props;

    public RedisDistributedLock(StringRedisTemplate redis, DistributedLockProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public Optional<LockToken> tryLock(String key, Duration lease) {
        String fullKey = fullKey(key);
        LockToken token = LockToken.random();
        // setIfAbsent 底层是 SET key value NX PX milliseconds(实际 Spring 6+
        // 走 setIfAbsent(K, V, Duration) 重载,语义等同)。
        // 注意 Spring 6 把 "EX 秒" 改成 "PX 毫秒" 才能在 1s 内精度生效,这里
        // lease.toMillis() 走 PX,够用。
        Boolean ok = redis.opsForValue().setIfAbsent(fullKey, token.value(), lease);
        boolean acquired = Boolean.TRUE.equals(ok);
        if (acquired) {
            log.debug("[lock] acquired key={} lease={}ms", fullKey, lease.toMillis());
            return Optional.of(token);
        }
        log.debug("[lock] busy key={}", fullKey);
        return Optional.empty();
    }

    @Override
    public boolean unlock(String key, LockToken token) {
        String fullKey = fullKey(key);
        List<String> keys = Collections.singletonList(fullKey);
        Long result = redis.execute(UNLOCK_SCRIPT, keys, token.value());
        boolean released = result != null && result == 1L;
        if (released) {
            log.debug("[lock] released key={}", fullKey);
        } else {
            // 常见原因:lease 过期被 Redis 回收,新 holder 已拿锁;本 holder
            // 不应再假设自己独占。log warn 不抛(无副作用)。
            log.warn("[lock] unlock miss key={} token={} (lease expired or not owner)",
                    fullKey, token);
        }
        return released;
    }

    private String fullKey(String key) {
        String prefix = props.getKeyPrefix();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("lock key must not be blank");
        }
        // 业务侧 key 已带业务前缀(如 "upload:avatar:user-100"),本类只加
        // 全局 lock: 命名空间;不重复加 ":"
        if (key.startsWith(prefix)) {
            return key;
        }
        return prefix + key;
    }

    /**
     * 让 {@link DistributedLockProperties} 的 @ConfigurationProperties 生效
     * —— 单独加 @Configuration 是为了让 {@code @SpringBootApplication}
     * 之外的场景(如 unit test)也能扫到。本类只用 @Component 已经够了,
     * 但 @EnableConfigurationProperties 在没显式 @ConfigurationPropertiesScan
     * 的项目里更稳。
     */
    @Configuration
    @EnableConfigurationProperties(DistributedLockProperties.class)
    static class PropertiesConfig {
    }
}

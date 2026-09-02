package com.nexusforge.lock;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分布式锁配置 —— key 在 Redis 里的统一前缀,与
 * {@code auth:*} / {@code ai:rl:*} / {@code pwd:*} 隔离。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "nexus-forge.lock")
public class DistributedLockProperties {

    /**
     * Redis key 前缀。默认 {@code "lock:"} —— 与现有命名空间隔离
     * (auth:blacklist / auth:refresh / ai:rl: / pwd:reset: / pwd:delete: /
     *  pwd:restore: 都不冲突)。
     */
    private String keyPrefix = "lock:";

    /**
     * 默认 lease 时长(秒)。业务调 {@code tryLock(key)} 不传 lease 时
     * 走此值。生产建议 30s,过短易误释放,过长易占资源。
     */
    private long defaultLeaseSeconds = 30L;
}

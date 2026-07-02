package com.nexusforge.ratelimit;

/**
 * 限流器接口，定义限流核心操作
 */
public interface RateLimiter {

    /**
     * 尝试获取令牌，获取成功则放行，否则触发限流
     *
     * @param key 限流维度标识（如用户ID、IP等）
     * @return true 允许通过，false 触发限流
     */
    boolean tryAcquire(String key, RateLimit anno);
}
package com.nexusforge.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 通用「Redis 缓存 → Supplier 回源」加载器。
 *
 * <p>职责单一：读 key，命中即返回；未命中调 loader，把结果写回 Redis 并设 TTL。</p>
 * <p>不知道缓存的是什么业务，也不知道 loader 怎么取数。</p>
 * <p>不放任何业务依赖，所有模块都可以注入。</p>
 */
@Component
@RequiredArgsConstructor
public class CachedValueLoader {

    private final StringRedisTemplate redis;

    /**
     * 读缓存，缺失则调 loader 回源并写回。
     *
     * @param key     Redis key
     * @param ttl     缓存有效期
     * @param loader  回源函数，返回字符串形式（空字符串表示"无数据"，也会被缓存以防穿透）
     */
    public String loadOrCompute(String key, Duration ttl, Supplier<String> loader) {
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return cached;
        }
        String fresh = loader.get();
        redis.opsForValue().set(key, fresh == null ? "" : fresh, ttl);
        return fresh;
    }

    /** 主动失效缓存 */
    public void evict(String key) {
        redis.delete(key);
    }
}
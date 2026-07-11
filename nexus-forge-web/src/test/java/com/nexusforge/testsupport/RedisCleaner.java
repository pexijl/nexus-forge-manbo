package com.nexusforge.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 测试间隔离:清空认证相关的 Redis key。
 *
 * - auth:*:test:*     → JwtProperties.blacklistPrefix / refreshPrefix(test profile 下)
 * - auth:roles:*      → PermissionLoader/UserRoleProvider 缓存(无 test 前缀,统一清)
 *
 * 用 KEYS 在小数据集上 OK;测试场景数据量可控。
 */
@Component
public class RedisCleaner {

    @Autowired
    private StringRedisTemplate redis;

    public void flush() {
        Set<String> keys = redis.keys("auth:*:test:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        keys = redis.keys("auth:roles:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
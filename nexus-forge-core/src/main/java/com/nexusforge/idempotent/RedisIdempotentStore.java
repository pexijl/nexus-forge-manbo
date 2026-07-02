package com.nexusforge.idempotent;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 实现的幂等性存储
 * <p>
 * 功能描述：
 * 1. 基于 Redis SET NX EX 原子命令实现分布式幂等锁
 * 2. 利用 Redis 的单线程特性和原子性操作，保证高并发场景下的数据一致性
 * 3. 支持自动过期（TTL），无需手动清理过期键
 * 4. 适用于分布式系统、微服务架构中的幂等性控制
 * <p>
 * 技术原理：
 * <ul>
 *     <li><b>SET NX</b>：仅在键不存在时设置，保证互斥性</li>
 *     <li><b>EX</b>：设置过期时间，自动释放锁，防止死锁</li>
 *     <li><b>原子性</b>：NX 和 EX 在同一命令中执行，避免并发竞态</li>
 * </ul>
 * <p>
 * 优势：
 * <ul>
 *     <li>高性能：Redis 内存操作，毫秒级响应</li>
 *     <li>分布式支持：多实例共享同一 Redis，实现全局幂等</li>
 *     <li>自动清理：TTL 机制无需额外维护</li>
 *     <li>原子安全：SET NX EX 保证并发安全性</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *     <li>存储的 Value 固定为 "1"，仅作为占位标记，不包含业务信息</li>
 *     <li>依赖 Spring Data Redis 的 StringRedisTemplate</li>
 *     <li>Redis 连接异常时会抛出异常，建议上层做降级处理</li>
 *     <li>键的命名建议包含业务前缀，便于管理和监控，如 "idempotent:order:xxx"</li>
 *     <li>TTL 设置需大于业务逻辑最大执行时间，避免业务未完成锁已释放</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * @Service
 * public class OrderService {
 *     private final IdempotentStore idempotentStore;
 *
 *     public void createOrder(String orderId) {
 *         String key = "order:" + orderId;
 *         if (!idempotentStore.tryAcquire(key, 120)) {
 *             throw new IdempotentException("订单正在处理，请勿重复提交");
 *         }
 *         // 执行业务逻辑
 *     }
 * }
 * }</pre>
 *
 */
@Component
@RequiredArgsConstructor
public class RedisIdempotentStore implements IdempotentStore {

    private final StringRedisTemplate redis;

    /**
     * 尝试获取 Redis 幂等锁
     * <p>
     * 使用 Redis SET NX EX 原子命令实现分布式锁。
     * <p>
     * 执行流程：
     * 1. 检查 Redis 中是否存在指定 key
     * 2. 若不存在，设置 key 并返回成功
     * 3. 若已存在，返回失败
     * 4. 无论是否成功，key 都会在 ttlSeconds 后自动过期
     * <p>
     * 并发安全性：
     * Redis 单线程执行 SET NX EX 命令，保证原子性，
     * 多个并发请求同时执行时，仅有一个能设置成功。
     *
     * @param key        幂等键，建议格式为 "{业务前缀}:{业务标识}"，
     *                   例如 "order:ORD20260702001"
     * @param ttlSeconds 锁过期时间（单位：秒），
     *                   建议值 60-300 秒，需大于业务执行时间
     * @return {@code true} 首次请求，获取锁成功；{@code false} 重复请求，获取锁失败
     */
    @Override
    public boolean tryAcquire(String key, int ttlSeconds) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(ok);
    }
}

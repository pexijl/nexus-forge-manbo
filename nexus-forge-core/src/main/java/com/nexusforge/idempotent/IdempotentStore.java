package com.nexusforge.idempotent;

/**
 * 幂等性存储接口
 * <p>
 * 功能描述：
 * 1. 定义幂等性控制的存储层契约，用于管理幂等键的原子性获取与释放
 * 2. 提供分布式环境下的锁机制，确保幂等操作的原子性和一致性
 * 3. 支持可配置的过期时间（TTL），自动清理过期的幂等记录
 * <p>
 * 设计理念：
 * 采用策略模式，允许不同的存储实现适应各种场景：
 * <ul>
 *     <li>Redis 实现：适用于分布式高并发场景（推荐）</li>
 *     <li>本地缓存实现：适用于单机应用或开发测试环境</li>
 *     <li>数据库实现：适用于需要持久化幂等记录的场景</li>
 * </ul>
 * <p>
 * 核心机制：
 * - 通过原子性操作（如 Redis SETNX）实现互斥锁
 * - 获取成功表示首次请求，获取失败表示重复请求
 * - TTL 机制自动控制幂等窗口，到期后自动释放，允许重新请求
 * <p>
 * 实现要求：
 * <ul>
 *     <li>必须保证 tryAcquire 方法的原子性，防止并发竞态</li>
 *     <li>必须支持 TTL 自动过期，避免内存泄漏</li>
 *     <li>建议实现类提供前缀配置，便于多环境隔离（如 dev:prod:）</li>
 *     <li>建议实现类支持自定义序列化方式，适应不同数据类型的存储</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * @Service
 * public class IdempotentService {
 *     private final IdempotentStore store;
 *
 *     public boolean checkAndAcquire(String businessKey, int ttlSeconds) {
 *         String key = "idempotent:" + businessKey;
 *         return store.tryAcquire(key, ttlSeconds);
 *     }
 * }
 * }</pre>
 *
 */
public interface IdempotentStore {
    /**
     * 尝试获取幂等锁
     * <p>
     * 在指定时间窗口内，为指定的业务键获取唯一执行权。
     * 该操作必须满足以下特性：
     * <ul>
     *     <li><b>原子性</b>：检查键是否存在 + 设置键值必须为原子操作，避免并发问题</li>
     *     <li><b>互斥性</b>：同一时刻，同一键只能被一个线程/请求成功获取</li>
     *     <li><b>自动过期</b>：ttlSeconds 超时后键自动删除，无需手动释放</li>
     * </ul>
     * <p>
     * 返回值说明：
     * <ul>
     *     <li>{@code true}：键不存在，获取锁成功，允许执行业务逻辑</li>
     *     <li>{@code false}：键已存在，获取锁失败，表示重复请求</li>
     * </ul>
     * <p>
     * 实现注意事项：
     * <ul>
     *     <li>使用 SET NX EX 或 SETNX + EXPIRE 原子命令</li>
     *     <li>避免使用 GET + SET 两步操作，防止并发竞态</li>
     *     <li>建议存储值为时间戳或请求ID，便于追溯和调试</li>
     *     <li>处理异常情况（如 Redis 连接失败）时应有降级策略</li>
     * </ul>
     *
     * @param key        业务唯一键，建议格式为 "idempotent:{业务类型}:{业务标识}"，
     *                   例如 "idempotent:order:ORD20260702001"
     * @param ttlSeconds 锁的过期时间（单位：秒），
     *                   值需大于业务逻辑的最大执行时间，建议值 30-300 秒
     * @return {@code true} 首次请求，获取锁成功；{@code false} 重复请求，获取锁失败
     * @throws IdempotentStoreException 存储层异常时抛出，如连接失败、超时等
     */
    boolean tryAcquire(String key, int ttlSeconds);
}

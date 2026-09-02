package com.nexusforge.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * 分布式锁 SPI —— 业务层通过本接口拿锁,实现可以是 Redis / ZK / DB。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 1. 拿不到立即返(非阻塞)
 * Optional<LockToken> t = lock.tryLock("upload:avatar:user-100", Duration.ofSeconds(30));
 * if (t.isEmpty()) throw new TooManyRequests();
 * try {
 *     // 执行业务
 * } finally {
 *     lock.unlock("upload:avatar:user-100", t.get());
 * }
 *
 * // 2. 拿不到抛异常(常用)
 * LockToken t = lock.tryLockOrThrow("ai:chat:user-100", Duration.ofSeconds(10));
 * try { ... } finally { lock.unlock(..., t); }
 * }</pre>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>key 建议带业务前缀(由业务方定,本接口不强制),例如
 *       {@code upload:avatar:user-100} / {@code ai:chat:user-100}</li>
 *   <li>lease 必须设 —— 防止持锁线程 crash 后锁永不释放</li>
 *   <li>lease 应当 <b>大于</b> 业务最大耗时;否则中途自动过期,新 holder
 *       拿到锁,本 holder 调 unlock 时 Lua 比对失败,静默返回 false</li>
 *   <li>同一 key 的 unlock 只释放自己 token 持有的锁;别人的锁不动</li>
 *   <li>本接口<b>不</b>做 watchdog 自动续约(留 TODO);业务超 lease 自认</li>
 *   <li>本接口<b>不</b>做重入(留 TODO);同线程重入会死锁自己</li>
 * </ul>
 *
 * <h3>失败语义</h3>
 * <ul>
 *   <li>{@link #tryLock} 拿不到 → {@link Optional#empty()}(业务可 try-catch 转 429)</li>
 *   <li>{@link #tryLockOrThrow} 拿不到 → 抛 {@link LockAcquireException}</li>
 *   <li>Redis 异常 → 透传,业务可降级(限流建议同样处理)</li>
 * </ul>
 */
public interface DistributedLock {

    /**
     * 尝试拿锁 —— 拿不到立即返空,不阻塞。
     *
     * @param key  锁 key
     * @param lease 持有时长(lease 到期 Redis 自动释放,防死锁)
     * @return 非空 {@link LockToken} 表示成功;空表示失败
     */
    Optional<LockToken> tryLock(String key, Duration lease);

    /**
     * 尝试拿锁 —— 拿不到抛 {@link LockAcquireException}。
     */
    default LockToken tryLockOrThrow(String key, Duration lease) {
        return tryLock(key, lease).orElseThrow(() ->
                new LockAcquireException("acquire lock failed: " + key));
    }

    /**
     * 释放锁 —— 用 Lua 原子比对 token,匹配才删,避免误删别人的锁。
     *
     * @return true 表示成功释放;false 表示 lease 已过期 / token 不匹配
     *         (本 holder 不应再调业务后续步骤,可能锁已被别人持有)
     */
    boolean unlock(String key, LockToken token);
}

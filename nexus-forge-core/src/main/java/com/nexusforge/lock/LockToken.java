package com.nexusforge.lock;

import java.util.Objects;
import java.util.UUID;

/**
 * 分布式锁的持有凭证 —— 每次 {@link DistributedLock#tryLock} 成功时生成,
 * 唯一标识某次锁的 owner。
 *
 * <p>实现要点:</p>
 * <ul>
 *   <li>锁 key 对应 Redis 里的 String 值,value 是本 token 的字符串形式</li>
 *   <li>解锁时用 Lua 脚本比对 token,匹配才删 —— 防止误删别人的锁
 *       (例如本 holder 业务跑超 lease,锁已自动过期,新 holder 拿到锁,
 *        老 holder 此时 unlock 不会误删新 holder 的锁)</li>
 *   <li>token 一律用 {@link UUID} —— 全局唯一,无需协调</li>
 * </ul>
 *
 * <p><b>不要</b>在业务侧直接拼接字符串当 token,必须经 {@link #random()}
 * 生成;也不要复用旧 token 来"续约" —— 续约/重入是后续 TODO(本类不实现)。</p>
 */
public record LockToken(String value) {

    public LockToken {
        Objects.requireNonNull(value, "token value must not be null");
    }

    /** 生成新的随机 token(每次 tryLock 时调) */
    public static LockToken random() {
        return new LockToken(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return "LockToken[" + value + "]";
    }
}

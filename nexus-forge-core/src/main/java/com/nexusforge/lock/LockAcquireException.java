package com.nexusforge.lock;

/**
 * 拿不到锁抛的异常 —— 业务可捕获后转 429(限流)或重试。
 *
 * <p>区别于 Redis 抛的连接异常:本异常是"竞争失败"的预期错误,
 * 而 Redis 连接异常是基础设施问题 —— 两者失败语义不同,业务侧
 * catch 后处理路径也不同。</p>
 */
public class LockAcquireException extends RuntimeException {
    public LockAcquireException(String message) {
        super(message);
    }
}

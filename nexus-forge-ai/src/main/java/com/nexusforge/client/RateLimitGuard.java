package com.nexusforge.client;

import com.nexusforge.config.AiProperties;
import com.nexusforge.ratelimit.RateLimit;
import com.nexusforge.ratelimit.RateLimitException;
import com.nexusforge.ratelimit.RateLimiter;
import com.nexusforge.ai.service.PreferenceResolver.KeySource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;

/**
 * P5 Step 7 — AI 端动态限流守卫。
 *
 * <p>与 {@code @RateLimit} 注解(编译期固定 qps/capacity)不同,
 * 本组件读 {@link AiProperties.RateLimitConfig} 运行时配置,
 * 运维可在 application.yaml 中热调 userQps / userBurst / ipQps / ipBurst,
 * 无需改代码重新部署。
 *
 * <p>限流维度:userId(主要) + IP(防未登录爆破)。
 * 底层复用 {@link RateLimiter}(Caffeine + Bucket4j 令牌桶),
 * key 前缀 {@code ai:rl:} 与 {@code RateLimitAspect} 的 {@code rl:} 命名空间隔离。
 *
 * <p>用法:在 Controller 方法体首行调用
 * {@code rateLimitGuard.check(userId, request.getRemoteAddr())}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitGuard {

    private final RateLimiter rateLimiter;
    private final AiProperties props;

    /**
     * 校验当前请求是否超过限流。超限抛 {@link RateLimitException}(→ HTTP 429)。
     *
     * <p>兼容旧调用,默认 {@link KeySource#SYSTEM} 模式。
     *
     * @param userId 用户 ID(匿名传 null)
     * @param ip     客户端 IP(可选,传 null 跳过 IP 维度)
     */
    public void check(Long userId, String ip) {
        check(userId, ip, KeySource.SYSTEM);
    }

    /**
     * P7 个性化版本。
     * <ul>
     *   <li>私 Key 模式:仅按 userId 限流(IP 维度跳过,避免代理/NAT 误伤)</li>
     *   <li>系统 Key 模式:userId + IP 双维度</li>
     * </ul>
     */
    public void check(Long userId, String ip, KeySource keySource) {
        AiProperties.RateLimitConfig cfg = props.getRateLimit();
        if (!cfg.isEnabled()) return;

        // ── 用户维度 ──(所有模式都走)
        if (userId != null && cfg.getUserQps() > 0) {
            int qps = (int) Math.ceil(cfg.getUserQps());
            int burst = cfg.getUserBurst() > 0 ? cfg.getUserBurst() : qps;
            String key = "ai:rl:user:" + userId;
            if (!rateLimiter.tryAcquire(key, annotation(qps, burst,
                    "AI 对话请求过于频繁,请稍后再试"))) {
                log.warn("[RateLimit] BLOCKED userId={} dimension=user mode={}", userId, keySource);
                throw new RateLimitException("AI 对话请求过于频繁,请稍后再试");
            }
        }

        // ── IP 维度 ──(仅系统 Key 模式)
        if (keySource == KeySource.USER_PRIVATE_KEY) {
            // 私 Key 模式:跳过 IP 维度(防 NAT/代理误伤)
            return;
        }
        if (ip != null && cfg.getIpQps() > 0) {
            int qps = (int) Math.ceil(cfg.getIpQps());
            int burst = cfg.getIpBurst() > 0 ? cfg.getIpBurst() : qps;
            String key = "ai:rl:ip:" + ip;
            if (!rateLimiter.tryAcquire(key, annotation(qps, burst,
                    "来自该 IP 的 AI 请求过于频繁"))) {
                log.warn("[RateLimit] BLOCKED ip={} dimension=ip", ip);
                throw new RateLimitException("来自该 IP 的 AI 请求过于频繁");
            }
        }
    }

    /**
     * 构造运行时 {@link RateLimit} 注解实例(限流器接口要求)。
     */
    private static RateLimit annotation(int qps, int capacity, String message) {
        return new RateLimit() {
            @Override public Class<? extends Annotation> annotationType() { return RateLimit.class; }
            @Override public String key()      { return ""; }
            @Override public int qps()         { return qps; }
            @Override public int capacity()    { return capacity; }
            @Override public String message()  { return message; }
        };
    }
}

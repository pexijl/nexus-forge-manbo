package com.nexusforge.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级限流注解，基于令牌桶算法
 * <p>
 * 支持通过 SpEL 表达式动态提取限流维度（如用户ID、IP等），
 * 可自定义 QPS、突发容量和限流提示信息。
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 每秒允许的最大请求数，默认 5
     */
    int qps() default 5;

    /**
     * 令牌桶容量，-1 表示使用 qps 值，允许突发流量
     */
    int capacity() default -1;

    /**
     * 限流维度的 SpEL 表达式，例如 "#userId" 或 "#ip"
     */
    String key();

    /**
     * 触发限流时的提示信息
     */
    String message() default "请求过于频繁，请稍后再试";
}
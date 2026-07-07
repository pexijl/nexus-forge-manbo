package com.nexusforge.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等性控制注解
 * <p>
 * 功能描述：
 * 1. 基于 Redis 分布式锁或缓存实现接口幂等性校验
 * 2. 通过 SpEL 表达式动态提取业务唯一标识（如订单号、交易流水号）
 * 3. 在指定时间窗口内，相同业务标识的请求仅允许成功执行一次
 * 4. 有效防止网络重试、表单重复提交、消息重复消费等场景
 * <p>
 * 使用场景：
 * - 订单创建接口（防止用户多次点击导致重复下单）
 * - 支付回调接口（防止支付平台重复通知导致重复处理）
 * - MQ 消费者（防止消息重复消费）
 * - 表单提交接口（防止刷新页面重复提交）
 * <p>
 * 实现原理：
 * 1. 拦截被注解的方法，解析 SpEL 表达式获取业务唯一键
 * 2. 尝试向 Redis 写入该键，并设置过期时间（ttlSeconds）
 * 3. 写入成功：首次请求，执行业务逻辑
 * 4. 写入失败（键已存在）：重复请求，抛出异常或返回错误提示
 * <p>
 * 注意事项：
 * - 业务唯一键必须能从请求参数中通过 SpEL 表达式正确解析
 * - 时间窗口（ttlSeconds）需根据业务场景合理设置，不宜过长或过短
 * - 幂等性控制需结合分布式锁使用，避免并发场景下的竞态条件
 * - 该注解仅标注方法，具体切面逻辑由 {@code IdempotentAspect} 实现
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 示例1：基于订单号
 * @Idempotent(key = "#orderId", ttlSeconds = 120, message = "订单正在处理，请勿重复提交")
 * public void createOrder(String orderId) {
 *     // 业务逻辑
 * }
 *
 * // 示例2：基于请求对象的字段
 * @Idempotent(key = "#req.transactionNo", ttlSeconds = 60)
 * public Result pay(PayRequest req) {
 *     // 业务逻辑
 * }
 *
 * // 示例3：基于多个字段组合
 * @Idempotent(key = "#userId + ':' + #productId")
 * public void addToCart(Long userId, Long productId) {
 *     // 业务逻辑
 * }
 * }</pre>
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /**
     * 业务唯一标识的 SpEL 表达式
     * <p>
     * 支持从方法参数中提取值，常用示例：
     * <ul>
     *     <li>#{orderId} - 直接提取参数</li>
     *     <li>#{req.orderNo} - 提取对象属性</li>
     *     <li>#{userId + ':' + productId} - 多字段组合</li>
     *     <li>#{#root.args[0]} - 提取第一个参数</li>
     * </ul>
     *
     * @return SpEL 表达式字符串
     */
    String key();

    /**
     * 幂等时间窗口（单位：秒）
     * <p>
     * 在该时间窗口内，相同业务标识的重复请求将被拦截。
     * 窗口到期后，该业务标识的幂等记录失效，允许再次提交。
     * <p>
     * 建议值：
     * <ul>
     *     <li>短时操作（如验证码发送）：30-60 秒</li>
     *     <li>常规业务（如订单创建）：60-120 秒</li>
     *     <li>长时操作（如报表生成）：300-600 秒</li>
     * </ul>
     *
     * @return 时间窗口（秒），默认 60 秒
     */
    int ttlSeconds() default 60;

    /**
     * 幂等性校验失败时的提示信息
     * <p>
     * 当检测到重复请求时，将返回该提示给调用方。
     * 建议使用用户友好的提示语，而非技术性描述。
     * <p>
     * 示例：
     * <ul>
     *     <li>"订单正在处理，请勿重复提交"</li>
     *     <li>"支付请求已受理，请勿重复操作"</li>
     *     <li>"操作过于频繁，请稍后再试"</li>
     * </ul>
     *
     * @return 错误提示信息，默认 "重复提交"
     */
    String message() default "重复提交";

}

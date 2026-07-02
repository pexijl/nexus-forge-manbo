package com.nexusforge.log;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Web 请求日志切面
 * <p>
 * 功能描述：
 * 1. 拦截 com.nexusforge..controller 包下所有 Controller 层的方法调用
 * 2. 自动记录每个请求的执行耗时（毫秒）
 * 3. 结合 MDC（Mapped Diagnostic Context）输出 traceId，便于分布式链路追踪
 * <p>
 * 使用场景：
 * - 接口性能监控与慢查询分析
 * - 请求链路追踪与问题定位
 * - 系统运行状态审计
 * <p>
 * 注意事项：
 * - 该切面仅记录方法执行耗时，不记录请求参数和响应结果，避免日志过大
 * - traceId 需在请求入口处（如 Filter/Interceptor）通过 MDC.put("traceId", id) 设置
 * - 若需扩展记录参数/响应，可在此类中添加环绕通知的前置/后置处理逻辑
 *
 */
@Slf4j
@Aspect
@Component
public class WebLogAspect {

    /**
     * 环绕通知：记录 Controller 层方法执行耗时
     * <p>
     * 切入点：匹配 com.nexusforge 包及其子包下所有 controller 包中的所有类的所有方法
     *
     * @param pjp 切点连接点，用于执行目标方法
     * @return 目标方法的执行结果
     * @throws Throwable 目标方法抛出的异常
     */
    @Around("execution(* com.nexusforge..controller..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[{}] {}.{} cost={}ms", MDC.get("traceId"),
                    pjp.getSignature().getDeclaringTypeName(),
                    pjp.getSignature().getName(), ms);
        }
    }

    /**
     * 扩展版：增加请求参数和响应结果记录（谨慎使用，注意敏感信息脱敏）
     */
//    @Around("execution(* com.nexusforge..controller..*(..))")
//    public Object around(ProceedingJoinPoint pjp) throws Throwable {
//        // 获取请求参数
//        Object[] args = pjp.getArgs();
//        String methodName = pjp.getSignature().getName();
//        String className = pjp.getSignature().getDeclaringTypeName();
//
//        // 前置日志（可选）
//        if (args.length > 0) {
//            log.debug("[{}] {}.{} 请求参数: {}", MDC.get("traceId"), className, methodName, Arrays.toString(args));
//        }
//
//        long t0 = System.nanoTime();
//        try {
//            Object result = pjp.proceed();
//            return result;
//        } finally {
//            long ms = (System.nanoTime() - t0) / 1_000_000;
//            log.info("[{}] {}.{} cost={}ms", MDC.get("traceId"), className, methodName, ms);
//
//            // 后置日志（可选，注意响应体可能很大）
//            // log.debug("[{}] {}.{} 响应结果: {}", MDC.get("traceId"), className, methodName, result);
//        }
//    }
}

package com.nexusforge.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimiter rateLimiter;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(anno)")
    public Object around(ProceedingJoinPoint pjp, RateLimit anno) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        String[] paramNames = signature.getParameterNames();
        Object[] args = pjp.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }
        HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        ctx.setVariable("ip", req.getRemoteAddr());
        String raw = parser.parseExpression(anno.key()).getValue(ctx, String.class);
        String key = "rl:" + raw;
        log.info("[RateLimit] key={} path={} method={}",
                key, req.getRequestURI(), req.getMethod());  // ← 加这行
        if (!rateLimiter.tryAcquire(key, anno)) {
            log.warn("[RateLimit] BLOCKED key={} path={} ip={}",
                    key, req.getRequestURI(), req.getRemoteAddr());  // ← 拦截时再打一条
            throw new RateLimitException(anno.message());
        }
        if (!rateLimiter.tryAcquire(key, anno)) {
            throw new RateLimitException(anno.message());
        }
        return pjp.proceed();
    }
}

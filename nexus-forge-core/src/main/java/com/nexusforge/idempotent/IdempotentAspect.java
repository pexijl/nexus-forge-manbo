package com.nexusforge.idempotent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final IdempotentStore store;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(anno)")
    public Object around(ProceedingJoinPoint pjp, Idempotent anno) throws Throwable {
        String raw = resolveKey(pjp, anno.key());
        String key = "idem:" + sha256(raw);

        if (!store.tryAcquire(key, anno.ttlSeconds())) {
            log.info("[idempotent] duplicate request blocked, key={}", key);
            throw new IdempotentException(anno.message());
        }
        return pjp.proceed();
    }

    private String resolveKey(ProceedingJoinPoint pjp, String spel) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        String[] paramNames = signature.getParameterNames();
        Object[] args = pjp.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }
        Object value = parser.parseExpression(spel).getValue(ctx);
        return value == null ? "" : value.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
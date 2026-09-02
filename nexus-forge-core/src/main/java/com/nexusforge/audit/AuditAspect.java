package com.nexusforge.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作审计 AOP 切面 —— 拦 {@code @Audited} 注解方法,自动写
 * {@link OperationAuditLog} 行。
 *
 * <h3>捕获内容</h3>
 * <ul>
 *   <li>user_id    —— 从 SecurityContext 拿当前登录用户</li>
 *   <li>action     —— @Audited.value()</li>
 *   <li>resource   —— @Audited.resource()</li>
 *   <li>resource_id —— @Audited.resourceId() SpEL 求值</li>
 *   <li>method     —— HTTP method(GET / POST ...)</li>
 *   <li>path       —— HttpServletRequest.getRequestURI()</li>
 *   <li>ip         —— X-Forwarded-For 头 → 兜底 getRemoteAddr()</li>
 *   <li>user_agent —— User-Agent 头(截断 255)</li>
 *   <li>result     —— SUCCESS / FAILURE(根据是否抛异常)</li>
 *   <li>status_code —— 从 HttpServletResponse 取(默认 200,异常时 500)</li>
 *   <li>latency_ms —— System.nanoTime() 计算耗时</li>
 *   <li>error_code —— 失败时从 BusinessException / ResultCode 取</li>
 *   <li>metadata   —— @Audited(recordArgs=true) 时存入参;否则空</li>
 * </ul>
 *
 * <h3>容错</h3>
 * 审计写库失败 log warn 不抛 —— 主链路不能因审计挂。
 *
 * <h3>未实现(留 TODO)</h3>
 * <ul>
 *   <li>支持 {@code recordResult=true} 取返回值 — 当前只支持 recordArgs</li>
 *   <li>异步写(线程池) — 同步写保证强一致但拖慢 RT,P3 接入 @Async 再优化</li>
 * </ul>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    public static final String METRIC_RECORD = "audit.record";

    private static final String TAG_RESULT = "result";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILURE = "failure";

    /** SpEL 解析器(resourceId="#userId" 等) */
    private final ExpressionParser parser = new SpelExpressionParser();

    private final OperationAuditLogRepository repo;
    private final MeterRegistry meterRegistry;

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        long startNs = System.nanoTime();
        OperationAuditLog.AuditResult result = OperationAuditLog.AuditResult.SUCCESS;
        Integer errorCode = null;
        Object returned = null;
        try {
            returned = pjp.proceed();
            return returned;
        } catch (Throwable t) {
            result = OperationAuditLog.AuditResult.FAILURE;
            errorCode = extractErrorCode(t);
            throw t;
        } finally {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            try {
                persistAudit(pjp, audited, result, errorCode, latencyMs, returned);
                recordMetric(result);
            } catch (Exception ex) {
                // 审计写库失败不抛 — 主链路已 commit/throw,审计只是 observability
                log.warn("[audit] persist failed action={} err={}", audited.value(), ex.getMessage());
            }
        }
    }

    private void persistAudit(ProceedingJoinPoint pjp, Audited audited,
                              OperationAuditLog.AuditResult result, Integer errorCode,
                              long latencyMs, Object returned) {
        OperationAuditLog log = new OperationAuditLog();
        log.setUserId(currentUserId());
        log.setAction(audited.value());
        log.setResource(audited.resource().isBlank() ? null : audited.resource());
        log.setResourceId(evalResourceId(pjp, audited.resourceId()));
        log.setMethod(currentHttpMethod());
        log.setPath(currentPath());
        log.setIp(currentIp());
        log.setUserAgent(currentUserAgent());
        log.setResult(result);
        log.setStatusCode(currentStatusCode(result));
        log.setLatencyMs(latencyMs);
        log.setErrorCode(errorCode);
        if (audited.recordArgs()) {
            log.setMetadata(extractArgs(pjp));
        }
        // recordResult=true 留 TODO
        repo.save(log);
    }

    private void recordMetric(OperationAuditLog.AuditResult result) {
        if (meterRegistry == null) return;
        Counter.builder(METRIC_RECORD)
                .tag(TAG_RESULT, result == OperationAuditLog.AuditResult.SUCCESS ? RESULT_SUCCESS : RESULT_FAILURE)
                .description("Operation audit records (success / failure)")
                .register(meterRegistry)
                .increment();
    }

    // ─────────────────────────────────────────────
    //  Context extraction
    // ─────────────────────────────────────────────

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof com.nexusforge.security.UserPrincipal p) {
            return p.userId();
        }
        return null;
    }

    private String currentHttpMethod() {
        HttpServletRequest req = currentRequest();
        return req == null ? "UNKNOWN" : req.getMethod();
    }

    private String currentPath() {
        HttpServletRequest req = currentRequest();
        return req == null ? "" : req.getRequestURI();
    }

    private String currentIp() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        // 优先 X-Forwarded-For(反向代理场景);多级代理取最左
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma < 0 ? xff : xff.substring(0, comma)).trim();
        }
        return req.getRemoteAddr();
    }

    private String currentUserAgent() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String ua = req.getHeader("User-Agent");
        return ua == null || ua.length() <= 255 ? ua : ua.substring(0, 255);
    }

    private int currentStatusCode(OperationAuditLog.AuditResult result) {
        HttpServletRequest req = currentRequest();
        if (req instanceof org.springframework.web.context.request.ServletRequestAttributes attrs) {
            // 注:HttpServletResponse 在 RequestContextHolder 里也能拿
            try {
                org.springframework.web.context.request.RequestAttributes ra =
                        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
                if (ra instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                    int code = sra.getResponse().getStatus();
                    if (code > 0) return code;
                }
            } catch (Exception ignore) {
                // async dispatch / 异常路径下 response 拿不到
            }
        }
        return result == OperationAuditLog.AuditResult.SUCCESS ? HttpStatus.OK.value() : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) {
            return null;
        }
    }

    private Integer extractErrorCode(Throwable t) {
        // 业务异常(commit 5f368cf 模式):BaseException.getCode() 是 Integer
        if (t instanceof com.nexusforge.exception.BaseException be) {
            return be.getCode();
        }
        return null;
    }

    private String evalResourceId(ProceedingJoinPoint pjp, String spel) {
        if (spel == null || spel.isBlank()) return null;
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            Method method = sig.getMethod();
            String[] paramNames = sig.getParameterNames();
            Object[] args = pjp.getArgs();
            EvaluationContext ctx = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    ctx.setVariable(paramNames[i], args[i]);
                }
            }
            // 也支持 #root.methodName / #root.target 等标准 root 引用
            ctx.setVariable("methodName", method.getName());
            Expression exp = parser.parseExpression(spel);
            Object value = exp.getValue(ctx);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            log.debug("[audit] resourceId SpEL eval failed spel={} err={}", spel, e.getMessage());
            return null;
        }
    }

    /**
     * 从方法参数取基本类型 / String 入参 → Map。复杂对象(Object / 集合)跳过,
     * 防止误存大对象 / 敏感数据(JSONB 容量大,且 metadata 字段不该有"业务数据"角色)。
     */
    private Map<String, Object> extractArgs(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        if (paramNames == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < paramNames.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            // 只收基本类型 / String / Number / Boolean
            if (arg instanceof String || arg instanceof Number || arg instanceof Boolean) {
                out.put(paramNames[i], arg);
            }
            // 其它类型(HttpServletRequest / MultipartFile / DTO / 集合)跳过
        }
        return out;
    }

    /**
     * Spring 上下文里 HttpServletResponse 状态码拿不到时(异步 / 异常路径)
     * 兜底返回 200/500。{@link #currentStatusCode} 已优先走真实 response。
     */
    @SuppressWarnings("unused")
    private Map<String, Object> safeArgs() {
        // 占位,保留以备 recordArgs 不同过滤策略
        return new HashMap<>();
    }
}

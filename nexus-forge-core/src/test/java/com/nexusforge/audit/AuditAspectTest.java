package com.nexusforge.audit;

import com.nexusforge.audit.OperationAuditLog.AuditResult;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.security.UserPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Audit Commit 2 单测 —— {@link AuditAspect} 切面逻辑。
 *
 * <p>Mock 模式:不启 Spring 上下文,直接 mock {@link ProceedingJoinPoint}
 * + HttpServletRequest 上下文,验证切面写库的字段。@Audited 注解的解析
 * 由 Spring 处理(实际生产用 AOP 代理),单测里手动模拟 joinPoint 数据。</p>
 *
 * <h3>覆盖矩阵</h3>
 * <ol>
 *   <li>{@code Success}   正常调用 → repo.save 1 次,字段全对(SUCCESS / 200 / ip / ua)</li>
 *   <li>{@code Failure}   抛 BusinessException → repo.save 1 次,result=FAILURE, error_code 提取</li>
 *   <li>{@code Throwable} 抛 RuntimeException → repo.save 1 次,error_code=null(无业务码)</li>
 *   <li>{@code Auth}      SecurityContext 拿 userId 注入 user_id 字段</li>
 *   <li>{@code NoAuth}    匿名调用 → user_id=null</li>
 *   <li>{@code XffIp}     X-Forwarded-For 头优先级 > getRemoteAddr()</li>
 *   <li>{@code XffMulti}  X-Forwarded-For 多级代理取最左</li>
 *   <li>{@code UaLong}    User-Agent > 255 chars 截断</li>
 *   <li>{@code ResourceId} SpEL "#userId" 求值成功 → resource_id</li>
 *   <li>{@code ResourceIdBlank} SpEL 求值失败 → resource_id=null(不抛)</li>
 *   <li>{@code RecordArgs} recordArgs=true → metadata 含基本类型入参,跳过对象</li>
 *   <li>{@code RecordArgsOff} recordArgs=false(default)→ metadata=null</li>
 *   <li>{@code Metric}    Micrometer counter 增 1</li>
 *   <li>{@code PersistFail} 写库失败 log warn 不抛(主链路不挂)</li>
 *   <li>{@code Latency}   latency_ms 至少 0(不报负数)</li>
 * </ol>
 */
class AuditAspectTest {

    private OperationAuditLogRepository repo;
    private AuditAspect aspect;
    private SimpleMeterRegistry meterRegistry;

    // ─────────────────────────────────────────────
    //  目标对象:测试 controller,带 @Audited 注解
    // ─────────────────────────────────────────────

    /** 模拟 controller 方法,带 @Audited(走 SpEL) */
    static class TestController {
        @Audited(value = "user.update", resource = "user", resourceId = "#userId")
        public String updateUser(Long userId, String nickname, Object ignoreObj) {
            return "ok";
        }

        @Audited(value = "user.get", resource = "user", resourceId = "#userId")
        public String getUser(Long userId) {
            return "ok";
        }

        @Audited(value = "file.upload", resource = "file", recordArgs = true)
        public String uploadFile(String filename, Long size, Object multipartFile) {
            return "ok";
        }

        @Audited(value = "test.fail", resource = "x", resourceId = "#userId")
        public String failMethod(Long userId) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user=" + userId);
        }

        @Audited(value = "test.runtime", resource = "x", resourceId = "#userId")
        public String runtimeMethod(Long userId) {
            throw new RuntimeException("infra down");
        }
    }

    private TestController target;
    private Method updateMethod;
    private Method getMethod;
    private Method uploadMethod;
    private Method failMethod;
    private Method runtimeMethod;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(OperationAuditLogRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        aspect = new AuditAspect(repo, meterRegistry);

        target = new TestController();
        updateMethod = TestController.class.getMethod("updateUser", Long.class, String.class, Object.class);
        getMethod = TestController.class.getMethod("getUser", Long.class);
        uploadMethod = TestController.class.getMethod("uploadFile", String.class, Long.class, Object.class);
        failMethod = TestController.class.getMethod("failMethod", Long.class);
        runtimeMethod = TestController.class.getMethod("runtimeMethod", Long.class);

        // 默认未认证 + 无 HTTP request(各测试按需覆盖)
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ─────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────

    /**
     * 构造一个 mock ProceedingJoinPoint,Mock signature 返回 method + 参数名 + 参数值。
     */
    private ProceedingJoinPoint mockJoinPoint(Method method, Object[] args, Object returned) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getMethod()).thenReturn(method);
        when(sig.getParameterNames()).thenReturn(extractParamNames(method));
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(returned);
        return pjp;
    }

    private String[] extractParamNames(Method method) {
        // 名字顺序与 method 参数顺序一致
        java.util.List<String> names = new java.util.ArrayList<>();
        for (var p : method.getParameters()) {
            names.add(p.getName());
        }
        return names.toArray(new String[0]);
    }

    private Audited extractAudited(Method method) {
        return method.getAnnotation(Audited.class);
    }

    /** Mock 一个 HttpServletRequest + 把 SecurityContext 设上指定 user */
    private void mockRequestAndAuth(String ip, String xff, String userAgent, Long userId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (xff != null) req.addHeader("X-Forwarded-For", xff);
        if (userAgent != null) req.addHeader("User-Agent", userAgent);
        req.setRemoteAddr(ip == null ? "127.0.0.1" : ip);
        req.setMethod("PUT");
        req.setRequestURI("/api/test");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setStatus(200);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req, resp));

        if (userId != null) {
            UserPrincipal p = new UserPrincipal(userId, "u" + userId);
            SecurityContextHolder.setContext(new SecurityContextImpl(
                    new UsernamePasswordAuthenticationToken(
                            p, "n/a", Set.of(new SimpleGrantedAuthority("ROLE_USER")))));
        }
    }

    /** 从 repo.save 抓最后一次保存的实体(支持多次调用) */
    private OperationAuditLog lastSaved() {
        ArgumentCaptor<OperationAuditLog> cap = ArgumentCaptor.forClass(OperationAuditLog.class);
        verify(repo, atLeastOnce()).save(cap.capture());
        List<OperationAuditLog> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    // ─────────────────────────────────────────────
    //  Success / Failure
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Success / Failure")
    class SuccessAndFailure {

        @Test
        @DisplayName("正常调用 → SUCCESS / 200 / method=PUT / path 写入")
        void success_path() throws Throwable {
            mockRequestAndAuth("192.168.1.1", null, "Mozilla/5.0", 100L);
            ProceedingJoinPoint pjp = mockJoinPoint(updateMethod,
                    new Object[] { 100L, "alice", new Object() }, "ok");

            aspect.around(pjp, extractAudited(updateMethod));

            OperationAuditLog saved = lastSaved();
            assertThat(saved.getResult()).isEqualTo(AuditResult.SUCCESS);
            assertThat(saved.getStatusCode()).isEqualTo(200);
            assertThat(saved.getMethod()).isEqualTo("PUT");
            assertThat(saved.getPath()).isEqualTo("/api/test");
            assertThat(saved.getIp()).isEqualTo("192.168.1.1");
            assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(saved.getAction()).isEqualTo("user.update");
            assertThat(saved.getResource()).isEqualTo("user");
            assertThat(saved.getResourceId()).isEqualTo("100");  // SpEL "#userId"
            assertThat(saved.getUserId()).isEqualTo(100L);
            assertThat(saved.getLatencyMs()).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("抛 BusinessException → FAILURE / 500 / error_code 提取")
        void business_exception() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 100L);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            MethodSignature sig = mock(MethodSignature.class);
            when(pjp.getSignature()).thenReturn(sig);
            when(sig.getMethod()).thenReturn(failMethod);
            when(sig.getParameterNames()).thenReturn(extractParamNames(failMethod));
            when(pjp.getArgs()).thenReturn(new Object[] { 100L });
            doThrow(new BusinessException(ResultCode.USER_NOT_FOUND, "user=100"))
                    .when(pjp).proceed();

            try {
                aspect.around(pjp, extractAudited(failMethod));
            } catch (BusinessException expected) {
                // 切面把异常原样抛给上游
            }

            OperationAuditLog saved = lastSaved();
            assertThat(saved.getResult()).isEqualTo(AuditResult.FAILURE);
            assertThat(saved.getErrorCode()).isEqualTo(ResultCode.USER_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("抛 RuntimeException → FAILURE / error_code=null")
        void runtime_exception() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 100L);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            MethodSignature sig = mock(MethodSignature.class);
            when(pjp.getSignature()).thenReturn(sig);
            when(sig.getMethod()).thenReturn(runtimeMethod);
            when(sig.getParameterNames()).thenReturn(extractParamNames(runtimeMethod));
            when(pjp.getArgs()).thenReturn(new Object[] { 100L });
            doThrow(new RuntimeException("infra down")).when(pjp).proceed();

            try {
                aspect.around(pjp, extractAudited(runtimeMethod));
            } catch (RuntimeException expected) {
                // 透传
            }

            OperationAuditLog saved = lastSaved();
            assertThat(saved.getResult()).isEqualTo(AuditResult.FAILURE);
            assertThat(saved.getErrorCode()).isNull();
        }
    }

    // ─────────────────────────────────────────────
    //  Auth
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Auth")
    class Auth {

        @Test
        @DisplayName("已认证 → user_id 注入;未认证 → user_id=null")
        void auth_extraction() throws Throwable {
            // 已认证
            mockRequestAndAuth("127.0.0.1", null, "ua", 100L);
            ProceedingJoinPoint pjp1 = mockJoinPoint(getMethod, new Object[] { 100L }, "ok");
            aspect.around(pjp1, extractAudited(getMethod));
            assertThat(lastSaved().getUserId()).isEqualTo(100L);

            // 重置,未认证
            SecurityContextHolder.clearContext();
            ProceedingJoinPoint pjp2 = mockJoinPoint(getMethod, new Object[] { 200L }, "ok");
            aspect.around(pjp2, extractAudited(getMethod));
            assertThat(lastSaved().getUserId()).isNull();
        }
    }

    // ─────────────────────────────────────────────
    //  IP / UA
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Ip / Ua")
    class IpUa {

        @Test
        @DisplayName("X-Forwarded-For 头优先级 > getRemoteAddr()")
        void xff_priority() throws Throwable {
            mockRequestAndAuth("127.0.0.1", "10.0.0.1, 10.0.0.2", "ua", 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(getMethod, new Object[] { 1L }, "ok");
            aspect.around(pjp, extractAudited(getMethod));
            assertThat(lastSaved().getIp()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("User-Agent 超过 255 字符截断")
        void ua_truncated() throws Throwable {
            String longUa = "x".repeat(500);
            mockRequestAndAuth("127.0.0.1", null, longUa, 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(getMethod, new Object[] { 1L }, "ok");
            aspect.around(pjp, extractAudited(getMethod));
            assertThat(lastSaved().getUserAgent()).hasSize(255);
        }
    }

    // ─────────────────────────────────────────────
    //  resourceId SpEL
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("ResourceId")
    class ResourceId {

        @Test
        @DisplayName("SpEL '#userId' 求值成功 → resource_id 写值")
        void spel_success() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(updateMethod,
                    new Object[] { 42L, "alice", new Object() }, "ok");
            aspect.around(pjp, extractAudited(updateMethod));
            assertThat(lastSaved().getResourceId()).isEqualTo("42");
        }

        @Test
        @DisplayName("SpEL 求值失败(变量不存在)→ resource_id=null,不抛")
        void spel_failure_does_not_throw() throws Throwable {
            // SpEL 表达式 "#userId" 找不到变量时,evalValue 返回 null
            // 本实现 value==null 时返 null(不 .toString())
            mockRequestAndAuth("127.0.0.1", null, "ua", 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(getMethod, new Object[] { 1L }, "ok");
            when(pjp.getArgs()).thenReturn(new Object[0]);  // 模拟没有 userId 参数
            // 不抛
            aspect.around(pjp, extractAudited(getMethod));
            assertThat(lastSaved().getResourceId()).isNull();
        }
    }

    // ─────────────────────────────────────────────
    //  recordArgs
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("RecordArgs")
    class RecordArgs {

        @Test
        @DisplayName("recordArgs=true → metadata 含 String/Number,跳过 Object")
        void args_filter() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(uploadMethod,
                    new Object[] { "report.pdf", 1024L, new Object() /* ignored */ }, "ok");
            aspect.around(pjp, extractAudited(uploadMethod));
            Map<String, Object> md = lastSaved().getMetadata();
            assertThat(md).isNotNull();
            assertThat(md.get("filename")).isEqualTo("report.pdf");
            assertThat(md.get("size")).isEqualTo(1024L);
            // Object 类型参数不进 metadata(防存大对象 / 敏感 DTO)
            assertThat(md).doesNotContainKey("multipartFile");
        }

        @Test
        @DisplayName("recordArgs=false(default)→ metadata=null")
        void args_off() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(getMethod, new Object[] { 1L }, "ok");
            aspect.around(pjp, extractAudited(getMethod));
            assertThat(lastSaved().getMetadata()).isNull();
        }
    }

    // ─────────────────────────────────────────────
    //  Micrometer / 容错
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Metric / FaultTolerance")
    class MetricAndFault {

        @Test
        @DisplayName("Micrometer counter 增 1(SUCCESS)")
        void metric_success() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(getMethod, new Object[] { 1L }, "ok");
            aspect.around(pjp, extractAudited(getMethod));
            double count = meterRegistry.find("audit.record").tag("result", "success").counter().count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Micrometer counter 增 1(FAILURE,按 result 标签分)")
        void metric_failure() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 1L);
            ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
            MethodSignature sig = mock(MethodSignature.class);
            when(pjp.getSignature()).thenReturn(sig);
            when(sig.getMethod()).thenReturn(failMethod);
            when(sig.getParameterNames()).thenReturn(extractParamNames(failMethod));
            when(pjp.getArgs()).thenReturn(new Object[] { 1L });
            doThrow(new BusinessException(ResultCode.USER_NOT_FOUND, "x"))
                    .when(pjp).proceed();

            try {
                aspect.around(pjp, extractAudited(failMethod));
            } catch (BusinessException ignore) { }

            // 没调过 success counter → 找不到(返 null)
            io.micrometer.core.instrument.Counter successC = meterRegistry
                    .find("audit.record").tag("result", "success").counter();
            io.micrometer.core.instrument.Counter failureC = meterRegistry
                    .find("audit.record").tag("result", "failure").counter();
            assertThat(successC).isNull();
            assertThat(failureC).isNotNull();
            assertThat(failureC.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("repo.save 抛异常 → 主链路不挂")
        void persist_failure_does_not_throw() throws Throwable {
            mockRequestAndAuth("127.0.0.1", null, "ua", 1L);
            ProceedingJoinPoint pjp = mockJoinPoint(getMethod, new Object[] { 1L }, "ok");
            doThrow(new RuntimeException("db down")).when(repo).save(any());

            // 不抛
            Object result = aspect.around(pjp, extractAudited(getMethod));
            assertThat(result).isEqualTo("ok");
        }
    }
}

package com.nexusforge.handler;

import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 统一 JSON 格式返回 401 / 403 响应。
 *
 * <p><b>为什么需要</b>:Spring Security 6 默认对未认证请求跳登录页(HTML),
 * 对权限不足请求返 403 空白页;前端 API 调用需要统一的 JSON 响应,
 * 与项目内 {@link Result} 格式对齐,让 axios 拦截器统一处理。
 *
 * <p><b>两个 SPI</b>:
 * <ul>
 *   <li>{@link AuthenticationEntryPoint} — 401 入口,过滤器链检测到未认证时由
 *       {@code ExceptionTranslationFilter} 调用 {@link #commence}</li>
 *   <li>{@link AccessDeniedHandler} — 403 入口,已认证但权限不足时由
 *       {@code ExceptionTranslationFilter} 调用 {@link #handle}</li>
 * </ul>
 *
 * <p><b>触发场景</b>:
 * <ul>
 *   <li>401 — Token 缺失 / 无效 / 过期 / 黑名单命中 / 解析异常(由 {@code JwtAuthenticationFilter}
 *       透传"未认证"到链尾)</li>
 *   <li>403 — SecurityConfig 的 {@code .authorizeHttpRequests(...).hasRole(...)} 不匹配
 *       (URL 级授权;方法级 {@code @PreAuthorize} 走 AOP,不进 Filter chain,
 *       改由 {@code GlobalExceptionHandler} 兜底,见 AGENTS.md 经验法则 8.3)</li>
 * </ul>
 *
 * <p><b>安全细节</b>:错误文案固定("登录已过期或未登录" / "无访问权限"),
 * 不暴露具体失败原因(token 过期 vs 伪造 vs 黑名单 / 缺哪个 role),
 * 防止攻击者用响应差异探测。
 *
 * @see com.nexusforge.config.SecurityConfig 装配入口(.exceptionHandling(...))
 * @see com.nexusforge.base.Result 响应体格式
 */
@Component
@RequiredArgsConstructor
public class JsonAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * 401 入口。Spring Security 检测到未认证(无 Authentication / Authentication 异常)时
     * 由 {@code ExceptionTranslationFilter} 调用,统一返 401 + 标准 JSON。
     *
     * @param authException Spring Security 内部异常的基类,这里<b>不取其 message</b>
     *                      ——统一文案防账号枚举(攻击者无法区分"没登录"和"被踢")
     * @throws IOException 写响应失败(网络 / 客户端断开);冒泡给 Filter chain
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException
    ) throws IOException, ServletException {
        // 统一文案:不暴露具体失败原因(token 缺失 vs 无效 vs 过期 vs 黑名单),
        // 防攻击者用响应差异探测有效账号(与 AuthController.login 的"无差别响应"策略一致)
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                Result.fail(ResultCode.UNAUTHORIZED, "登录已过期或未登录"));
    }

    /**
     * 403 入口。已认证但权限不足时由 {@code ExceptionTranslationFilter} 调用,统一返 403 + 标准 JSON。
     *
     * <p>常见触发(URL 级授权):SecurityConfig 的 {@code .authorizeHttpRequests(...)}
     * 配置的 {@code .hasRole(...)} / {@code .hasAuthority(...)} / {@code .hasIpAddress(...)}
     * 不匹配时,Filter chain 抛 {@code AccessDeniedException} → ExceptionTranslationFilter 兜底到这里。
     *
     * <p><b>方法级 @PreAuthorize 不走这里</b>:Spring Security 6 的方法级授权抛
     * {@code AccessDeniedException} 走 AOP 拦截器,<b>不会</b>进入 Filter chain;
     * 那种情况由 {@code GlobalExceptionHandler} 的 {@code @ExceptionHandler(AccessDeniedException.class)}
     * 兜底(也返 403 + 1005)——AGENTS.md 经验法则 8.3。
     *
     * @param accessDeniedException 权限不足的异常,这里<b>不取其 message</b>
     *                              ——统一文案防"通过错误信息反向猜测权限配置"
     */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                Result.fail(ResultCode.FORBIDDEN, "无访问权限"));
    }

    /**
     * 把 {@link Result} 序列化为 JSON 并直接写入 HTTP 响应。
     *
     * <p><b>为什么手动序列化</b>:在 filter / handler 阶段写响应,不走 Spring MVC
     * 的 {@code HttpMessageConverter},所以必须自己调用 ObjectMapper。
     *
     * @param response Spring Security 提供的响应对象
     * @param status HTTP 状态码(401 / 403)
     * @param body {@link Result} 响应体
     * @throws IOException 序列化或写入失败(网络 / 客户端断开);冒泡给 Filter chain
     *                    让 ExceptionTranslationFilter 兜底(通常记日志后吞掉)
     */
    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        // 显式设 UTF-8:Tomcat 默认 getWriter() 可能用 ISO-8859-1,中文会乱码;
        // setCharacterEncoding 必须在 getWriter() 之前调用才生效(已遵守)
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // writeValueAsString 失败抛 JsonProcessingException(IOException 子类),让 Filter chain 处理
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

package com.nexusforge.filter;

import com.nexusforge.chat.StreamAuthorizationPolicy;
import com.nexusforge.config.JwtProperties;
import com.nexusforge.enums.TokenType;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.service.AuthService;
import com.nexusforge.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * SSE 专用鉴权过滤器:从 query string 取 {@code access_token},
 * 验证通过则写入 {@link SecurityContextHolder}。
 * <p>仅匹配 {@link #STREAM_PATH_PREFIX} 一个路径前缀,其它端点不受影响;
 * 主鉴权仍走 {@link JwtAuthenticationFilter} 的 {@code Authorization: Bearer} header。
 *
 * <p>为什么需要:浏览器原生 {@code EventSource} / {@code fetch + reader} 不便设置
 * 自定义请求头;query token 是 SSE 场景下唯一的实用 fallback。
 *
 * <p>安全取舍:
 * <ul>
 *   <li>query token 可能被浏览器历史 / 服务器日志记录 → 不应长期使用,生产应配短期 access token</li>
 *   <li>仅做 access token 验证,refresh token 一律拒绝(防止 refresh 被滥用做业务请求)</li>
 *   <li>黑名单检查:与主鉴权链路一致,被吊销的 token 同样拒绝</li>
 *   <li>角色 authorities 不加载(SSE 端点未来才需要 RBAC,先放空集合以减少 Redis 查询) ——
 *       这一点 P2.5 之前都成立,届时再补 {@code PermissionLoader}</li>
 * </ul>
 *
 * <p>无 token / 验证失败 / 解析异常 → 不设置 Authentication,直接放行;
 * 由下游的 {@code AuthorizationManager} 在请求进入业务方法时统一返回 401。
 * 这里**故意不直接 401** 是为了与主鉴权链路行为一致(主 filter 也只是 clearContext 后放行),
 * 同时让前端通过同一条 401 路径收到 session 失效信号。
 */
@Slf4j
@Component
@Order(90)   // 90 < JwtAuthenticationFilter(100):SSE 路径上
// query token 先被本 filter 尝试;若该请求同时
// 带 Authorization header,JwtAuthenticationFilter
// 后续会用 header 的 Authentication 覆盖(更安全)
@RequiredArgsConstructor
public class JwtQueryTokenFilter extends OncePerRequestFilter {

    /**
     * SSE 端点路径前缀;P2 仅 {@code /api/ai/chat/stream} 一个端点。
     */
    public static final String STREAM_PATH_PREFIX = "/api/ai/chat/stream";

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProps;
    private final AuthService authService;

    /**
     * 性能短路:不是 SSE 路径直接跳过,避免每个请求多走一遍 filter 逻辑。
     * {@link StreamAuthorizationPolicy#ALLOW_QUERY_TOKEN} 关掉时,全站关停 query token。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!StreamAuthorizationPolicy.ALLOW_QUERY_TOKEN) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(STREAM_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // 1) 从 query string 取 token(EventSource / fetch+reader 不能设自定义 header 的兜底)
        //    无 token 不阻塞 → 由 JwtAuthenticationFilter 走 Authorization header 兜底
        String token = request.getParameter(StreamAuthorizationPolicy.QUERY_PARAM);
        if (token == null || token.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2) 验签:无效 token 不直接 401 → 与主鉴权链路行为一致(主 filter 也只是放行),
        //    由后续 EntryPoint 统一返回 session 失效信号
        if (!jwtUtil.validateToken(token)) {
            log.warn("[JwtQueryTokenFilter] 无效的 query token");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);

            // 3) type claim 校验:必须是 access,refresh 一律拒绝
            //    ——防止 refresh 被滥用做 SSE 长连接请求(防 token 滥用)
            if (jwtUtil.extractType(claims) != TokenType.ACCESS) {
                log.debug("[JwtQueryTokenFilter] 拒绝非 access token 走 SSE 路径");
                filterChain.doFilter(request, response);
                return;
            }

            // 4) 黑名单:与 JwtAuthenticationFilter 一致;按 token 剩余 TTL 精准加入,
            //    jwt.enable-blacklist 开关可临时关掉做调试
            if (jwtProps.getEnableBlacklist() && authService.isBlacklisted(claims)) {
                log.debug("[JwtQueryTokenFilter] JWT 已被吊销: jti={}", claims.getId());
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            // 5) 从 Claims 拿 userId(sub) / username
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);

            // 6) 构造 Authentication:三参 = principal + credentials(null, JWT 已验) + 空 authorities
            //    空 authorities:P2 SSE 端点暂未引入 RBAC,先放空集合省 Redis 查询;
            //    List.of() 不可变空集合,避免下游误 add
            UserPrincipal principal = new UserPrincipal(userId, username);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("[JwtQueryTokenFilter] query token 鉴权成功: userId={}", userId);
        } catch (Exception e) {
            // 7) 异常兜底:解析/拉取/构建异常都吞掉,不冒泡 500;
            //    清空 SecurityContext 防 Tomcat 线程复用污染上一个请求的认证态
            log.error("[JwtQueryTokenFilter] JWT 解析异常", e);
            SecurityContextHolder.clearContext();
        }
        // 8) 无论成功失败都继续链:成功 → SSE 控制器;失败 → 后续 EntryPoint 决定 401
        filterChain.doFilter(request, response);
    }
}
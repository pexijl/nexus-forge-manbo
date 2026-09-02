package com.nexusforge.filter;

import com.nexusforge.config.JwtProperties;
import com.nexusforge.enums.TokenType;
import com.nexusforge.security.PermissionLoader;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 认证过滤器 —— 从 Authorization 头提取 Bearer Token,验签后把 Authentication
 * 写入 {@link SecurityContextHolder},供后续 Spring Security 过滤器与控制器使用。
 *
 * <p><b>链式鉴权模式</b>:本过滤器对"无效 token / 缺 token / 黑名单命中 / 解析异常"
 * 等情况<b>不直接 401</b>,而是透传"未认证"状态到过滤器链,由后续
 * {@code AuthenticationEntryPoint}({@code JsonAuthHandlers}) 决定 401/403。
 * 这种设计的优势:错误格式、过期、黑名单等多种失败原因可以在统一入口映射,
 * 而非每个 filter 各自处理;同时让公开端点(白名单)能正常通过。
 *
 * <p><b>Order(100) 含义</b>:SecurityConfig 装配顺序内的相对值,确保本过滤器在
 * {@code UsernamePasswordAuthenticationFilter} 之后、{@code ExceptionTranslationFilter} 之前
 * 运行——这样链式鉴权失败时由 ExceptionTranslationFilter 兜底抛 401/403。
 *
 * <p><b>关键设计点</b>:
 * <ul>
 *   <li><b>type claim 校验</b>:refresh token 不可进业务接口(仅用于 /api/auth/refresh)
 *       ——这是双轨制的安全实现,防 token 滥用</li>
 *   <li><b>角色走 Redis</b>(不嵌 token):角色变更立即生效,无需等旧 token 过期;
 *       由 {@link PermissionLoader} 拉取,带 TTL 缓存</li>
 *   <li><b>ROLE_ 前缀</b>:Spring Security 6 的 {@code hasRole('X')} 期望 {@code "ROLE_X"} —
 *       这是历史踩坑点(AGENTS.md 8.2),见 step 7</li>
 *   <li><b>黑名单</b>:Redis key {@code auth:blacklist:&lt;jti&gt;},由
 *       {@link AuthService#logout} 按 token 剩余 TTL 精准加入;
 *       {@code jwt.enable-blacklist} 开关可临时关掉做调试</li>
 *   <li><b>异常吞掉兜底</b>:catch (Exception) 仅 log + clearContext,不让一个坏 token 让服务 500</li>
 * </ul>
 *
 * <p>继承 {@link OncePerRequestFilter} 保证每个请求只跑一次(避免 forward / include 多次触发)。
 *
 * @see com.nexusforge.handler.JsonAuthHandlers 401/403 JSON 写入器
 * @see com.nexusforge.service.AuthService#logout 登出时把 access/refresh JTI 加黑名单
 * @see com.nexusforge.security.PermissionLoader 拉角色(Redis 缓存)
 */
@Slf4j
@Component
@Order(100)   // 100 = SecurityConfig 内的相对位置;UsernamePasswordAuthenticationFilter 之后、ExceptionTranslationFilter 之前
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProps;
    private final AuthService authService;
    private final PermissionLoader permissionLoader;

    /**
     * 8 步鉴权流程(详见内联注释):
     * <ol>
     *   <li>从 Authorization 头提取 Bearer token</li>
     *   <li>验签(签名 / 过期 / 签发方)</li>
     *   <li>解析 Claims</li>
     *   <li>校验 type claim(必须是 access)</li>
     *   <li>查黑名单</li>
     *   <li>从 Claims 拿 userId / username</li>
     *   <li>从 Redis 拉角色列表 + 加 ROLE_ 前缀</li>
     *   <li>构建 Authentication 写入 SecurityContext</li>
     * </ol>
     * <p>任一步失败均<b>不直接 401</b>:继续走过滤器链,由后续 EntryPoint 决定响应——
     * 这保证错误格式、过期、黑名单等多种失败原因能在统一入口映射,同时让公开端点(白名单)能正常通过。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 1) 从 Authorization 头提取 Bearer token;无 token 或前缀不匹配时
        // 透传"未认证"给后续 EntryPoint,由 SecurityConfig 白名单决定是否放过(公开端点能正常通过)
        String bearerToken = request.getHeader(jwtProps.getHeader());
        if (bearerToken == null || !bearerToken.startsWith(jwtProps.getPrefix() + " ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = bearerToken.substring(jwtProps.getPrefix().length()).trim();

        // 2) 验签:JwtUtil 内部验签名 + 过期时间 + issuer;失败说明伪造或过期
        // 不直接 401:可能是另一种合法 token(如其它服务的 token,误打到本服务),交给 EntryPoint 统一判
        if (!jwtUtil.validateToken(token)) {
            log.warn("无效的 JWT Token: {}", token);
            filterChain.doFilter(request, response);
            return;
        }

        // 3) 解析 Claims;后续所有步骤都在 try 块内,任何异常都兜底为"未认证"继续链
        // (避免一个坏 token 让整个服务 500;线程复用时也避免污染上一个请求的认证态)
        try {
            Claims claims = jwtUtil.parseToken(token);

            // 4) type claim 校验:必须是 access,refresh token 不可进业务接口
            // (refresh 仅用于 /api/auth/refresh,这是双轨制的安全实现,防 token 滥用场景)
            if (jwtUtil.extractType(claims) != TokenType.ACCESS) {
                log.debug("拒绝非 access token 进入业务接口");
                filterChain.doFilter(request, response);
                return;
            }

            // 5) 黑名单:Redis key auth:blacklist:<jti>,由 AuthService.logout 按 token 剩余 TTL 精准加入
            // jwt.enable-blacklist 开关可临时关掉(默认 true),调试场景(如大量 token 误踢)用
            if (jwtProps.getEnableBlacklist() && authService.isBlacklisted(claims)) {
                log.debug("JWT 已被吊销: jti={}", claims.getId());
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            // 6) 从 Claims 拿 userId(sub) / username;后续步骤用 userId 拉权限
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);

            // 7) 从 Redis 拉角色列表(不走 token 内嵌):角色变更立即生效,
            // PermissionLoader 内部带 TTL 缓存(redis auth:roles:<userId>)
            // —— ⚠️ 必须带 "ROLE_" 前缀:Spring Security 6 的 hasRole('X') 期望 "ROLE_X"
            //     不带前缀时 @PreAuthorize("hasRole('ADMIN')") 永远不匹配
            //     (AGENTS.md 经验法则 8.2 历史踩坑点)
            List<String> roles = permissionLoader.loadRoles(userId);
            List<GrantedAuthority> authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toList());

            // 8) 构建 Authentication:三参构造 = principal + credentials(null,JWT 已验过) + authorities
            // setDetails 注入 IP/sessionId,审计日志可追溯请求来源(WebAuthenticationDetailsSource.buildDetails)
            // 写入 SecurityContext 后,后续 @PreAuthorize / hasRole() 即可直接判断
            UserPrincipal principal = new UserPrincipal(userId, username);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("JWT 鉴权成功: userId={}, roles={}", userId, roles);
        } catch (Exception e) {
            // 任何解析/拉取/构建过程的异常都兜底为"未认证",不冒泡 500;
            // 清空 SecurityContext 避免下游误用上一个请求的认证态(Tomcat 线程复用)
            log.error("JWT 解析异常", e);
            SecurityContextHolder.clearContext();
        }
        // 无论成功失败都继续链:成功 → 业务接口执行;失败 → 后续 EntryPoint 决定 401/403
        filterChain.doFilter(request, response);
    }
}

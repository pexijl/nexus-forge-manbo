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
 * JWT 认证过滤器类，负责从 HTTP 请求中提取 JWT 令牌并进行验证，以实现基于 JWT 的认证机制
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(100)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProps;
    private final AuthService authService;
    private final PermissionLoader permissionLoader;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 1. 从请求头提取 Token
        String bearerToken = request.getHeader(jwtProps.getHeader());
        if (bearerToken == null || !bearerToken.startsWith(jwtProps.getPrefix() + " ")) {
            filterChain.doFilter(request, response);  // 没有 Token，继续处理其他过滤器
            return;
        }
        String token = bearerToken.substring(jwtProps.getPrefix().length()).trim();

        // 2. 校验 Token
        if (!jwtUtil.validateToken(token)) {
            log.warn("无效的 JWT Token: {}", token);
            filterChain.doFilter(request, response);  // 无效 Token，继续处理其他过滤器
            return;
        }

        // 3. 解析 Claims, 构建 Authentication 对象并设置到 SecurityContext
        try {
            Claims claims = jwtUtil.parseToken(token);

            // 4. 必须是 access token（防 refresh 滥用）
            if (jwtUtil.extractType(claims) != TokenType.ACCESS) {
                log.debug("拒绝非 access token 进入业务接口");
                filterChain.doFilter(request, response);
                return;
            }

            // 5. 黑名单检查
            if (jwtProps.getEnableBlacklist() && authService.isBlacklisted(claims)) {
                log.debug("JWT 已被吊销: jti={}", claims.getId());
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            // 6. 从 Redis 拉权限（替代 token 内嵌）
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            // 7. 从 Redis 拉取角色列表
            List<String> roles = permissionLoader.loadRoles(userId);
            List<GrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());

            // 8. 构建 Authentication 对象并设置到 SecurityContext
            UserPrincipal principal = new UserPrincipal(userId, username);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("JWT 鉴权成功: userId={}, roles={}", userId, roles);
        } catch (Exception e) {
            log.error("JWT 解析异常", e);
            SecurityContextHolder.clearContext(); // 解析异常，清除上下文，继续处理其他过滤器
        }
        filterChain.doFilter(request, response);
    }
}

package com.nexusforge.filter;

import com.nexusforge.config.JwtProperties;
import com.nexusforge.enums.Role;
import com.nexusforge.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 认证过滤器类，负责从 HTTP 请求中提取 JWT 令牌并进行验证，以实现基于 JWT 的认证机制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    private final JwtProperties jwtProps;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

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
            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);

            // 4. 从 Claims 中提取权限（生产建议从 Redis/DB 查询，避免 Token 过大）
            // TODO: 从Redis中提取权限
            List roles = claims.get("roles", List.class);
            @SuppressWarnings("unchecked")
            List authorities = Collections.singletonList(roles.stream()
                    .map(role -> new SimpleGrantedAuthority(((String) role)))  // ← 直接转换为字符串
                    .collect(Collectors.toList()));

            // 5. 构建 Authentication 对象并设置到 SecurityContext
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("JWT 认证成功: userId={}, username={}", userId, username);
        } catch (Exception e) {
            log.error("JWT 解析异常", e);
            SecurityContextHolder.clearContext(); // 解析异常，清除上下文，继续处理其他过滤器
        }
        filterChain.doFilter(request, response);
    }
}

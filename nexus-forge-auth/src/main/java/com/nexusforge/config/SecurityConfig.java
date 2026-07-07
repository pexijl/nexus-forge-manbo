package com.nexusforge.config;

import com.nexusforge.filter.JwtAuthenticationFilter;
import com.nexusforge.handler.JsonAuthHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 安全配置类，负责配置 Spring Security 的相关设置，例如认证和授权规则
 */
@Configuration
@EnableWebSecurity // 启用 Spring Security 的 Web 安全功能
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final JsonAuthHandlers jsonAuthHandlers;

    /**
     * 配置密码编码器，使用 BCrypt 算法进行密码加密
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 配置认证管理器，使用 Spring Security 的默认实现，通过 AuthenticationConfiguration 获取 AuthenticationManager 实例
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * 3. 核心过滤器链（前后端分离无状态配置）
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 启用 CORS（必须放在 csrf 之前）
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // 关闭 CSRF（前后端分离无 Session）
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态：不创建 Session
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 接口权限控制
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()  // SpringDoc
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(jsonAuthHandlers)  // 未认证 → 401
                        .accessDeniedHandler(jsonAuthHandlers) // 权限不足 → 403
                )
                // JWT 过滤器插在 UsernamePasswordAuthenticationFilter 之前
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

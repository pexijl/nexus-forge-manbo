package com.nexusforge.config;

import com.nexusforge.filter.JwtAuthenticationFilter;
import com.nexusforge.filter.JwtQueryTokenFilter;
import com.nexusforge.handler.JsonAuthHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import jakarta.servlet.DispatcherType;
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
@EnableMethodSecurity   // 启用 @PreAuthorize / @PostAuthorize 方法级鉴权
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JwtQueryTokenFilter jwtQueryTokenFilter;
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
                        // Spring Boot 内部错误派发:Tomcat 在 async dispatch 完成后,如果响应进入
                        // ErrorReportValve,会以 DispatcherType=ERROR 派发到 /error;ASyncContext.complete
                        // 触发的 ASYNC 重派发也可能再走一遍链。此类派发线程上没有 SecurityContext,
                        // AuthorizationFilter 会以匿名身份重跑整条链 → AuthorizationDeniedException
                        // (响应已 commit,客户端无感知,仅日志噪声)。按 DispatcherType 放行最稳,
                        // 不依赖 URL 匹配。
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh",
                                         "/api/auth/password/reset/**",
                                         // 账号生命周期:注销 / 撤销都靠邮件链接,
                                         // 公开端点由 controller 的 @SecurityRequirements 进一步限定
                                         "/api/users/me/delete/**", "/api/users/me/restore").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()  // SpringDoc
                        .requestMatchers("/actuator/health", "/actuator/metrics/**", "/actuator/prometheus/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(jsonAuthHandlers)  // 未认证 → 401
                        .accessDeniedHandler(jsonAuthHandlers) // 权限不足 → 403
                )
                // 必须先注册 JwtAuthenticationFilter(锚点是 UsernamePasswordAuthenticationFilter,
                // Spring Security 7 要求 anchor 在 FilterOrderRegistration 里存在),
                // 然后再用 JwtAuthenticationFilter 作 anchor 注册 JwtQueryTokenFilter。
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // SSE 专用:query token 鉴权,只对 /api/ai/chat/stream 生效
                .addFilterBefore(jwtQueryTokenFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}

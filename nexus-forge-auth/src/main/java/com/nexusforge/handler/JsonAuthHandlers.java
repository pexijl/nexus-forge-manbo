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
 * 统一 JSON 格式返回 401 / 403 响应
 * - AuthenticationException → 401 未认证（Token 缺失/无效/过期）
 * - AccessDeniedException    → 403 已认证但权限不足
 */
@Component
@RequiredArgsConstructor
public class JsonAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                Result.fail(ResultCode.UNAUTHORIZED, "登录已过期或未登录"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                Result.fail(ResultCode.FORBIDDEN, "无访问权限"));
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

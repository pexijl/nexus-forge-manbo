package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.dto.*;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.AuthException;
import com.nexusforge.security.LoginUser;
import com.nexusforge.service.AuthService;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import com.nexusforge.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证", description = "登录、注册等公开接口")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthService authService;
    private final UserService userService;

    @Operation(
            summary = "用户注册",
            description = "用户名、邮箱、密码需符合格式；用户名/邮箱不可重复"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注册成功"),
            @ApiResponse(responseCode = "2002", description = "用户名已存在", content = @Content),
            @ApiResponse(responseCode = "2003", description = "邮箱已存在", content = @Content)
    })
    @SecurityRequirements   // ← 公开接口
    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterRequest req) {
        UserVo userVo = userService.register(req);
        if (userVo == null) {
            return Result.fail(ResultCode.REGISTRATION_FAILED);
        }
        return Result.success();
    }

    @Operation(
            summary = "用户登录",
            description = "账号可为 username 或 email；成功后返回 JWT"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功，data.token 即为 JWT"),
            @ApiResponse(responseCode = "1003", description = "账号或密码错误", content = @Content)
    })
    @SecurityRequirements   // ← 覆盖 OpenApiConfig 的全局 bearer 声明
    @PostMapping("/login")
    public Result<TokenBundle> login(@Valid @RequestBody LoginRequest req) {
        try {
            // 1. 调用原生认证管理器
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getAccount(), req.getPassword()));
            // 2. 加载完整用户信息（userId, username）
            LoginUser user = (LoginUser) auth.getPrincipal();
            if (user == null) {
                // 用户不存在
                return Result.fail(ResultCode.USER_NOT_FOUND);
            }
            // 3. 签发 access + refresh
            return Result.success(authService.issueTokens(user));
        } catch (BadCredentialsException e) {
            // 账号或密码错误
            return Result.fail(ResultCode.INVALID_CREDENTIALS);
        }
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    public Result<TokenBundle> refresh(@Valid @RequestBody RefreshRequest req) {
        try {
            return Result.success(authService.refresh(req.refreshToken()));
        } catch (AuthException e) {
            return Result.fail(ResultCode.TOKEN_REFRESH_FAILED, e.getMessage());
        }
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request,
                               @RequestBody(required = false) LogoutRequest body) {
        // 从 header 拿当前 access
        String header = request.getHeader("Authorization");
        String accessToken = (header != null && header.startsWith("Bearer "))
                ? header.substring(7).trim() : null;
        String refreshToken = body != null ? body.refreshToken() : null;
        authService.logout(accessToken, refreshToken);
        return Result.success();
    }
}

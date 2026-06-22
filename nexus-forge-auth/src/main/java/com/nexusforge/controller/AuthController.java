package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.dto.LoginRequest;
import com.nexusforge.dto.RegisterRequest;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.security.LoginUser;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import com.nexusforge.util.JwtUtil;
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

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginRequest req){
        try{
            // 1. 调用原生认证管理器
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getAccount(), req.getPassword()));
            // 2. 加载完整用户信息（含 userId、roles）
            LoginUser user = (LoginUser) auth.getPrincipal();
            if(user == null){
                return Result.fail(ResultCode.USER_NOT_FOUND);
            }
            // 3. 生成 JWT（把 userId、roles 放进 claims）
            Map<String, Object> claims = new HashMap<>();
            claims.put("username", user.getUsername());
            claims.put("roles", user.getRoles());
            String token = jwtUtil.createToken(user.getUsername(), claims);

            // 4. 返回 token
            Map<String, String> data = new HashMap<>();
            data.put("token", token);
            return Result.success(data);
        } catch (BadCredentialsException e) {
            return Result.fail(ResultCode.INVALID_CREDENTIALS);
        }
    }

    @PostMapping("/register")
    public Result<UserVo> register(@Valid @RequestBody RegisterRequest req) {
        UserVo userVo = userService.register(req);
        return Result.success(userVo);
    }
}

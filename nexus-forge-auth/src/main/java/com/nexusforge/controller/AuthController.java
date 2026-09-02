package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.dto.*;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.AuthException;
import com.nexusforge.password.PasswordResetService;
import com.nexusforge.password.dto.ConfirmResetDto;
import com.nexusforge.password.dto.RequestResetDto;
import com.nexusforge.security.LoginUser;
import com.nexusforge.service.AuthService;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import com.nexusforge.util.ClientIpResolver;
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
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开认证端点(无需 JWT bearer,Spring Security 已配置白名单)。
 *
 * <p><b>主路径</b>({@code /api/auth}):
 * <ul>
 *   <li>{@code POST /register} — 用户注册</li>
 *   <li>{@code POST /login} — 用户登录(账号可为 username 或 email)</li>
 *   <li>{@code POST /refresh} — 用 refresh token 换新 access + refresh</li>
 *   <li>{@code POST /logout} — 撤销 access(可选同时撤销 refresh)</li>
 * </ul>
 *
 * <p><b>密码重置子路径</b>(邮箱验证码,独立流程):
 * <ul>
 *   <li>{@code POST /password/reset/request} — 申请发送 6 位验证码</li>
 *   <li>{@code POST /password/reset/confirm} — 校验验证码 + 改密 + 踢 refresh</li>
 * </ul>
 *
 * <p><b>安全原则(防枚举)</b>:login 把"账号不存在 / 密码错 / 被封禁 / 已禁用"统一映射为
 * {@link ResultCode#INVALID_CREDENTIALS},拒绝用响应差异探测有效账号;密码重置申请不论邮箱
 * 是否存在都返 200(具体结果落 server log)。
 *
 * @see com.nexusforge.service.AuthService 令牌签发 / 刷新 / 撤销
 * @see com.nexusforge.password.PasswordResetService 密码重置
 */
@Tag(name = "认证", description = "登录、注册等公开接口")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthService authService;
    private final UserService userService;
    private final PasswordResetService passwordResetService;

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
        // null 兜底:userService.register 内部已校验 username/email 唯一,理论上不会返回 null;
        // 这里保留以防上游契约变更,显式失败而非 NPE
        if (userVo == null) {
            return Result.fail(ResultCode.REGISTRATION_FAILED);
        }
        // data=null:注册成功不返回用户信息(避免泄露注册顺序/时间戳等内部细节),
        // 客户端需要时再 GET /api/users/me
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
            // 1) AuthenticationManager 走 UserDetailsService + PasswordEncoder 校验账号密码;
            //    account 字段接受 username 或 email(由 UserDetailsServiceImpl 适配)
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.getAccount(), req.getPassword()));
            // 2) 提取 LoginUser 主对象:已包含 userId / username / role,供 issueTokens 签发 JWT
            LoginUser user = (LoginUser) auth.getPrincipal();
            // 理论上不会为 null(认证失败走 BadCredentialsException),但保留兜底以防 SPI 异常
            if (user == null) {
                return Result.fail(ResultCode.USER_NOT_FOUND);
            }
            // 3) 签发 access + refresh,落 Redis 黑名单状态
            return Result.success(authService.issueTokens(user));
        } catch (BadCredentialsException e) {
            // 账号不存在 / 密码错 → 统一 1003(配合下面 catch 形成"无差别"响应)
            return Result.fail(ResultCode.INVALID_CREDENTIALS);
        } catch (LockedException | DisabledException e) {
            // 安全设计:被封禁(BANNED) / 已禁用(DISABLED) 的账号对前端统一返"账号或密码错误",
            // 不让攻击者通过响应差异探测"哪个账号存在 + 哪种状态"——这是账号枚举攻击的标准防御
            return Result.fail(ResultCode.INVALID_CREDENTIALS);
        }
    }

    @Operation(
            summary = "刷新令牌",
            description = "用 refresh token 换新 access + refresh;refresh 一次性轮换,旧 refresh 立即失效"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "刷新成功,data 为新 TokenBundle"),
            @ApiResponse(responseCode = "2004", description = "refresh 失败(token 无效/过期/已被消费/已撤销)", content = @Content)
    })
    @SecurityRequirements   // 公开端点(用 refresh token 而非 access)
    @PostMapping("/refresh")
    public Result<TokenBundle> refresh(@Valid @RequestBody RefreshRequest req) {
        try {
            // AuthService.refresh 内部 4 步:① 验签 ② 查黑名单 ③ 轮换两 token ④ 存新 refresh JTI
            return Result.success(authService.refresh(req.refreshToken()));
        } catch (AuthException e) {
            // e.getMessage() 透传具体失败原因(token 过期 / 已被消费 / 已撤销),便于客户端排错
            return Result.fail(ResultCode.TOKEN_REFRESH_FAILED, e.getMessage());
        }
    }

    @Operation(
            summary = "登出",
            description = "撤销当前 access(按剩余 TTL 加黑名单);可同时撤销 refresh(从 body 传入);两 token 都可选"
    )
    @ApiResponses(@ApiResponse(responseCode = "200", description = "登出成功"))
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request,
                               @RequestBody(required = false) LogoutRequest body) {
        // 从 Authorization header 提取 access:加 Bearer 前缀判断 + substring(7) + trim,
        // 是因为 header 可能为 null / 错 scheme / 多空格,任一情况都安全降级为 null
        String header = request.getHeader("Authorization");
        String accessToken = (header != null && header.startsWith("Bearer "))
                ? header.substring(7).trim() : null;
        // refresh 从 body 拿(可选):仅撤销 access 也算登出,但前端通常两个都发
        String refreshToken = body != null ? body.refreshToken() : null;
        // AuthService.logout 内部按 token TTL 精准加黑名单(不是固定窗口),节省 Redis 内存
        authService.logout(accessToken, refreshToken);
        return Result.success();
    }

    // ====================== 密码重置(邮箱验证码) ======================

    /**
     * 申请重置密码 —— 提交注册邮箱,触发邮件验证码发送。
     *
     * <p>不论邮箱是否存在 / 用户是否被封禁,响应一律 200(防邮箱枚举);
     * 限流触发时返回 429 + {@code RESET_CODE_SEND_TOO_FREQUENT}。</p>
     */
    @Operation(
            summary = "申请重置密码",
            description = "提交邮箱,触发邮件验证码;不论邮箱是否存在都返回 200"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已处理(具体结果在 server log)"),
            @ApiResponse(responseCode = "2009", description = "限流触发", content = @Content)
    })
    @SecurityRequirements
    @PostMapping("/password/reset/request")
    public Result<Void> requestPasswordReset(@Valid @RequestBody RequestResetDto req,
                                             HttpServletRequest httpReq) {
        passwordResetService.requestReset(req.email(), ClientIpResolver.resolve(httpReq));
        return Result.success();
    }

    /**
     * 确认重置密码 —— 校验邮箱 + 6 位验证码 + 新密码,改密成功后踢所有 refresh token。
     *
     * <p><b>access 不主动撤销</b>:公开端点拿不到原 access(只能拿到当前请求的),依赖
     * access ≤15min 自然到期;refresh 撤销由 {@code PasswordResetService} 完成,
     * 绑定 userId 清掉 {@code auth:refresh:{userId}} 上所有活跃 JTI。
     */
    @Operation(
            summary = "确认重置密码",
            description = "校验邮箱+验证码+新密码;成功后踢所有 refresh,access ≤15min 自然到期"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "改密成功"),
            @ApiResponse(responseCode = "2013", description = "验证码错误或已过期", content = @Content),
            @ApiResponse(responseCode = "2014", description = "失败次数过多,验证码已失效", content = @Content),
            @ApiResponse(responseCode = "2012", description = "新密码与旧密码相同", content = @Content)
    })
    @SecurityRequirements
    @PostMapping("/password/reset/confirm")
    public Result<Void> confirmPasswordReset(@Valid @RequestBody ConfirmResetDto req) {
        passwordResetService.confirmReset(req);
        return Result.success();
    }
}

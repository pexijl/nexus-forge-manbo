package com.nexusforge.user.controller;

import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.user.dto.ConfirmDeletionDto;
import com.nexusforge.user.dto.RequestDeletionDto;
import com.nexusforge.user.dto.RestoreAccountDto;
import com.nexusforge.user.service.AccountLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号注销 / 恢复端点 —— 公开或登录态,见各方法。
 *
 * <p>路径设计:</p>
 * <ul>
 *   <li>{@code POST /api/users/me/delete/request}  —— 登录态,提交密码,触发邮件</li>
 *   <li>{@code POST /api/users/me/delete/confirm}  —— 公开,提交邮箱+验证码,执行真删</li>
 *   <li>{@code POST /api/users/me/restore}          —— 公开,提交一次性 token(commit 3 实装)</li>
 * </ul>
 */
@Tag(name = "账号注销", description = "用户自助注销 / 撤销注销")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class AccountDeletionController {

    private final AccountLifecycleService accountLifecycleService;

    @Operation(summary = "申请注销", description = "提交当前密码二次确认,触发邮件验证码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已发送验证码"),
            @ApiResponse(responseCode = "2011", description = "密码错"),
            @ApiResponse(responseCode = "2015", description = "60s 内重复申请")
    })
    @PostMapping("/me/delete/request")
    public Result<Void> requestDeletion(
            @Valid @RequestBody RequestDeletionDto dto) {
        Long userId = currentUserId();
        if (userId == null) {
            // 实际由 Spring Security 拦截未认证请求返回 401;这里是 defense-in-depth
            return Result.fail(com.nexusforge.enums.ResultCode.UNAUTHORIZED);
        }
        accountLifecycleService.requestDeletion(userId, dto.password());
        return Result.success();
    }

    @Operation(summary = "确认注销", description = "提交邮箱 + 6 位验证码,执行真删(PII 擦除 + 软删 + 踢 refresh + 通知 ai 模块)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注销成功"),
            @ApiResponse(responseCode = "2013", description = "验证码错或过期"),
            @ApiResponse(responseCode = "2014", description = "失败次数过多")
    })
    @SecurityRequirements
    @PostMapping("/me/delete/confirm")
    public Result<Void> confirmDeletion(@Valid @RequestBody ConfirmDeletionDto dto) {
        accountLifecycleService.confirmDeletion(dto);
        return Result.success();
    }

    @Operation(summary = "撤销注销(commit 3 实装)", description = "公开端点,通过邮件中的 token 撤销 14 天内的注销")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "恢复成功"),
            @ApiResponse(responseCode = "4017", description = "token 无效或已过期")
    })
    @SecurityRequirements
    @PostMapping("/me/restore")
    public Result<Void> restoreFromToken(@Valid @RequestBody RestoreAccountDto dto) {
        accountLifecycleService.restoreFromToken(dto.token());
        return Result.success();
    }

    /**
     * 从 SecurityContext 解析当前 userId。
     * 不用 {@code @AuthenticationPrincipal} 是因为该注解依赖
     * {@code AuthenticationPrincipalArgumentResolver} 走 MVC dispatcher 链,
     * 在某些场景(unit test 直接调、IT @Nested 上下文切换)不解析 → NPE。
     * 改手动从 SecurityContextHolder 拿,行为一致。
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p.userId();
        }
        return null;
    }
}

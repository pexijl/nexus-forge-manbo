package com.nexusforge.user.controller;

import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.user.dto.BanUserDto;
import com.nexusforge.user.dto.UnbanUserDto;
import com.nexusforge.user.entity.AccountLifecycleLog;
import com.nexusforge.user.enums.AccountLifecycleAction;
import com.nexusforge.user.repository.AccountLifecycleLogRepository;
import com.nexusforge.user.service.AccountLifecycleService;
import com.nexusforge.user.vo.AccountLifecycleLogVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员账号生命周期管理 —— 封禁 / 解封 / 查询审计。
 *
 * <p>所有端点要求 {@code ADMIN} 角色。{@code @PreAuthorize} 注解生效的前提是
 * 启用了 {@code @EnableMethodSecurity},Spring Security 6 默认开启(本项目
 * SecurityConfig 未显式开启,但 spring-boot-starter-security 默认是开启的)。</p>
 *
 * <p>参考 {@code com.nexusforge.ai.controller.AiAdminController} 的同模式实现。</p>
 */
@Tag(name = "管理员·账号生命周期", description = "封禁 / 解封 / 查询审计(仅 ADMIN)")
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserLifecycleController {

    private final AccountLifecycleService accountLifecycleService;
    private final AccountLifecycleLogRepository logRepository;

    @Operation(summary = "封禁用户", description = "管理员强制封禁;status=BANNED + 踢 refresh + 写审计")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "封禁成功"),
            @ApiResponse(responseCode = "2001", description = "用户不存在"),
            @ApiResponse(responseCode = "403", description = "无管理员权限")
    })
    @PostMapping("/{id}/ban")
    public Result<Void> ban(
            @Parameter(description = "目标用户 id") @PathVariable Long id,
            @Valid @RequestBody(required = false) BanUserDto dto) {
        String reason = dto == null ? null : dto.reason();
        accountLifecycleService.ban(id, currentAdminId(), reason);
        return Result.success();
    }

    @Operation(summary = "解封用户", description = "管理员撤销封禁;status=ACTIVE + 写审计(不踢 refresh)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "解封成功"),
            @ApiResponse(responseCode = "2001", description = "用户不存在"),
            @ApiResponse(responseCode = "403", description = "无管理员权限")
    })
    @PostMapping("/{id}/unban")
    public Result<Void> unban(
            @Parameter(description = "目标用户 id") @PathVariable Long id,
            @Valid @RequestBody(required = false) UnbanUserDto dto) {
        String reason = dto == null ? null : dto.reason();
        accountLifecycleService.unban(id, currentAdminId(), reason);
        return Result.success();
    }

    /**
     * 从 SecurityContext 解析当前 admin id。
     *
     * <p>没用 {@code @AuthenticationPrincipal} 是因为 unit test 直接调
     * controller 方法时,该注解不解析(走 {@code AuthenticationPrincipalArgumentResolver}
     * 走 MVC dispatcher 链);手动从 SecurityContextHolder 拿,unit test 设置
     * SecurityContext 即可生效。{@code @PreAuthorize} 鉴权仍由 Spring AOP 走,
     * 在 commit 5 集成测试中验证。</p>
     */
    private Long currentAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p.userId();
        }
        return null;
    }

    @Operation(summary = "查询某用户的生命周期审计", description = "按时间倒序返回全部 BAN / UNBAN / DELETE_REQUEST / DELETE_CONFIRM / RESTORE 事件")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "403", description = "无管理员权限")
    })
    @GetMapping("/{id}/lifecycle")
    public Result<List<AccountLifecycleLogVo>> listUserLifecycle(
            @Parameter(description = "目标用户 id") @PathVariable Long id) {
        List<AccountLifecycleLog> rows = logRepository.findByUserIdOrderByCreatedAtDesc(id);
        List<AccountLifecycleLogVo> vos = rows.stream().map(AccountLifecycleLogVo::from).toList();
        return Result.success(vos);
    }

    @Operation(summary = "按动作分页查询全局审计(管理员后台面板)",
            description = "例如 ?action=BAN&page=1&size=20 看最近封禁列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "403", description = "无管理员权限")
    })
    @GetMapping("/lifecycle")
    public Result<PageResult<AccountLifecycleLogVo>> listLifecycleByAction(
            @Parameter(description = "动作类型") @RequestParam AccountLifecycleAction action,
            @Parameter(description = "页码(从 1 开始)") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page - 1), Math.max(1, size));
        Page<AccountLifecycleLog> rows = logRepository.findByActionOrderByCreatedAtDesc(action, pr);
        Page<AccountLifecycleLogVo> vos = rows.map(AccountLifecycleLogVo::from);
        return Result.success(PageResult.of(vos));
    }
}

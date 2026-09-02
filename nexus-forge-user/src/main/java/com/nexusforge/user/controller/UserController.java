package com.nexusforge.user.controller;

import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.user.dto.ChangePasswordDto;
import com.nexusforge.user.dto.UpdateUserDto;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "用户管理", description = "当前用户的查询、修改、头像、密码相关接口")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(
            summary = "获取当前登录用户信息",
            description = "从 JWT 中解析 userId，返回完整用户视图（含最新头像 URL）"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "未登录或 token 过期", content = @Content)
    })
    @GetMapping("/me")
    public Result<UserVo> me(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        UserVo userVo = userService.findUserVoById(principal.userId());
        return Result.success(userVo);
    }

    @Operation(
            summary = "修改当前用户资料",
            description = "支持部分字段更新；email/phone/nickname/avatarUrl 任选"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数校验失败（邮箱/手机号格式错误）", content = @Content),
            @ApiResponse(responseCode = "2003", description = "邮箱已被他人占用", content = @Content)
    })
    @PatchMapping("/me")
    @com.nexusforge.audit.Audited(
            value = "user.update",
            resource = "user",
            resourceId = "#principal.userId()")
    public Result<UserVo> updateMe(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody UpdateUserDto dto) {
        UserVo userVo = userService.updateUser(principal.userId(), dto);
        return Result.success(userVo);
    }

    @Operation(
            summary = "修改当前用户密码",
            description = "需校验旧密码正确；新旧密码不能相同"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "修改成功"),
            @ApiResponse(responseCode = "2011", description = "旧密码不正确", content = @Content),
            @ApiResponse(responseCode = "2012", description = "新旧密码相同", content = @Content)
    })
    @PostMapping("/me/password")
    @com.nexusforge.audit.Audited(
            value = "user.password.change",
            resource = "user",
            resourceId = "#principal.userId()")
    public Result<Void> changePassword(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordDto dto) {
        userService.changePassword(principal.userId(), dto);
        return Result.success();
    }

    @Operation(
            summary = "上传当前用户头像",
            description = "multipart/form-data；前端先校验 ≤ 2MB；后端会清理旧文件"
    )
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UserVo> uploadAvatar(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "头像文件 (≤ 2MB)", required = true)
            @RequestPart("file") MultipartFile file) {
        return Result.success(userService.updateAvatar(principal.userId(), file));
    }

    @Operation(summary = "删除当前用户头像")
    @DeleteMapping("/me/avatar")
    @com.nexusforge.audit.Audited(
            value = "user.avatar.remove",
            resource = "user",
            resourceId = "#principal.userId()")
    public Result<UserVo> removeAvatar(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(userService.removeAvatar(principal.userId()));
    }
}

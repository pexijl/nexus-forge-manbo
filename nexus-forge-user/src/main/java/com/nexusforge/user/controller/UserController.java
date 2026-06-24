package com.nexusforge.user.controller;

import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.user.dto.ChangePasswordDto;
import com.nexusforge.user.dto.UpdateUserDto;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public Result<UserVo> me(@AuthenticationPrincipal UserPrincipal principal) {
        UserVo userVo = userService.findUserVoById(principal.userId());
        return Result.success(userVo);
    }

    @PatchMapping("/me")
    public Result<UserVo> updateMe(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody UpdateUserDto dto
    ) {
        // TODO: 修改用户个人信息
        return Result.success();
    }

    @PostMapping("/me/password")
    public Result<Void> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ChangePasswordDto dto
    ) {
        // TODO: 修改用户密码
        return Result.success();
    }
}

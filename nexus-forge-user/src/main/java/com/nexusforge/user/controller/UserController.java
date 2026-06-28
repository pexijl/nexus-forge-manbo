package com.nexusforge.user.controller;

import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.user.dto.ChangePasswordDto;
import com.nexusforge.user.dto.UpdateUserDto;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
        UserVo userVo = userService.updateUser(principal.userId(), dto);
        return Result.success(userVo);
    }

    @PostMapping("/me/password")
    public Result<Void> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ChangePasswordDto dto
    ) {
        // TODO: 修改用户密码
        return Result.success();
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<UserVo> uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestPart("file") MultipartFile file
    ) {
        return Result.success(userService.updateAvatar(principal.userId(), file));
    }
}

package com.nexusforge.user.controller;

import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}

package com.nexusforge.user.controller;

import com.nexusforge.base.Result;
import com.nexusforge.user.dto.UserRegisterDto;
import com.nexusforge.user.service.UserService;
import com.nexusforge.user.vo.UserVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<UserVo> register(@Valid @RequestBody UserRegisterDto dto) {
        UserVo userVo = userService.register(dto);
        return Result.success(userVo);
    }
}

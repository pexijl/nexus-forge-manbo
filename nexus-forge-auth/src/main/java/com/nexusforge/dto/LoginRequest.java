package com.nexusforge.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 DTO 类，用于封装用户登录请求的数据，包括账号和密码
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名或邮箱不能为空")
    private String account;

    @NotBlank(message = "密码不能为空")
    private String password;

    @AssertTrue(message = "邮箱格式不正确")
    public boolean isAccountValid() {
        return !account.contains("@") || account.matches("^[\\w.-]+@[\\w.-]+\\.\\w+$");
    }
}

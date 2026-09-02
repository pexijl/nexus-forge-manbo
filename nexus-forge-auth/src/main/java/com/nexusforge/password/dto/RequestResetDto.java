package com.nexusforge.password.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请重置密码请求 —— 提交注册邮箱,触发邮件验证码发送。
 *
 * <p>响应一律 200 OK(不论邮箱是否存在 / 用户是否被封禁),以防止攻击者
 * 通过状态码 / 错误信息差异枚举有效邮箱;真实状态在 server 端 log 中
 * 体现。</p>
 */
@Schema(description = "申请重置密码请求体(提交邮箱)")
public record RequestResetDto(

        @Schema(description = "注册时使用的邮箱", example = "alice@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "邮箱长度不能超过 255")
        String email
) {
}

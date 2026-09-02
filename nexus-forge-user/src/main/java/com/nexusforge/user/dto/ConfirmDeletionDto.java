package com.nexusforge.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 确认注销账号请求 —— 邮箱 + 6 位验证码,触发真删。
 */
@Schema(description = "确认注销账号请求(邮箱 + 6 位验证码)")
public record ConfirmDeletionDto(

        @Schema(description = "注册时使用的邮箱", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @Schema(description = "邮件中的 6 位数字验证码", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "\\d{6}", message = "验证码必须为 6 位数字")
        String code
) {
}

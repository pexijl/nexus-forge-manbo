package com.nexusforge.password.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 确认重置密码请求 —— 校验邮箱 + 6 位验证码 + 新密码。
 *
 * <p>错误码语义:</p>
 * <ul>
 *   <li>{@code 2013 RESET_CODE_INVALID} —— 验证码错误 / 已过期</li>
 *   <li>{@code 2014 RESET_CODE_TOO_MANY_ATTEMPTS} —— 失败次数过多,验证码已自动失效</li>
 *   <li>{@code 2012 NEW_PASSWORD_SAME_AS_OLD} —— 新密码与旧密码相同(复用现有码)</li>
 * </ul>
 */
@Schema(description = "确认重置密码请求体(邮箱 + 验证码 + 新密码)")
public record ConfirmResetDto(

        @Schema(description = "注册时使用的邮箱", example = "alice@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        String email,

        @Schema(description = "邮件中的 6 位数字验证码", example = "482910", minLength = 6, maxLength = 6, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "验证码不能为空")
        @Pattern(regexp = "\\d{6}", message = "验证码必须为 6 位数字")
        String code,

        @Schema(description = "新密码(6-32 位)", example = "newSecret789", minLength = 6, maxLength = 32, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "新密码长度必须在 6-32 位之间")
        String newPassword
) {
}

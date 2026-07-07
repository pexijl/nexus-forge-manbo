package com.nexusforge.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "修改密码请求体")
public record ChangePasswordDto(

        @Schema(description = "当前密码", example = "oldPass123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "旧密码不能为空")
        String oldPassword,

        @Schema(description = "新密码（6-32 位）", example = "newSecret456", minLength = 6, maxLength = 32, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "新密码长度必须在6-32位之间")
        String newPassword
) {
}

package com.nexusforge.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 申请注销账号请求 —— 提交当前密码(二次确认),触发邮件验证码发送。
 */
@Schema(description = "申请注销账号请求(提交当前密码)")
public record RequestDeletionDto(

        @Schema(description = "当前密码(二次确认)", example = "oldPass123", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "密码不能为空")
        String password
) {
}

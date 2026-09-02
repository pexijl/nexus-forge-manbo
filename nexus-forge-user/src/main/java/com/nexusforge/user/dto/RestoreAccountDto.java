package com.nexusforge.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销注销请求体 —— 提交邮件中的恢复 token。
 *
 * <p>公开端点(用户已注销后无 token,必须靠邮件链接恢复)。</p>
 */
@Schema(description = "撤销注销请求体(邮件 token)")
public record RestoreAccountDto(

        @Schema(description = "邮件中的恢复 token", example = "a1b2c3...", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "token 不能为空")
        @Size(min = 32, max = 128, message = "token 长度必须在 32-128 之间")
        String token
) {
}

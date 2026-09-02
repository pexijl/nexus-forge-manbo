package com.nexusforge.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 管理员解封用户请求体 —— 提交解封理由(可空)。
 */
@Schema(description = "管理员解封用户请求体")
public record UnbanUserDto(

        @Schema(description = "解封理由(写入审计)", example = "申诉通过")
        @Size(max = 500, message = "解封理由长度不能超过 500")
        String reason
) {
}

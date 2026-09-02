package com.nexusforge.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 管理员封禁用户请求体 —— 提交封禁理由(可空)。
 */
@Schema(description = "管理员封禁用户请求体")
public record BanUserDto(

        @Schema(description = "封禁理由(写入审计)", example = "违规发布 spam 内容")
        @Size(max = 500, message = "封禁理由长度不能超过 500")
        String reason
) {
}

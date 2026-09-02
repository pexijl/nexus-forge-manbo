package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.entity.UserAiModelAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户 model alias 响应 VO。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "用户 model alias 响应")
public class UserAiModelAliasVo {

    @Schema(description = "alias ID")
    private Long id;

    @Schema(description = "所属 user ID")
    private Long userId;

    @Schema(description = "别名")
    private String alias;

    @Schema(description = "解析目标 vendor")
    private String targetVendor;

    @Schema(description = "解析目标 model")
    private String targetModel;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;

    public static UserAiModelAliasVo from(UserAiModelAlias a) {
        if (a == null) return null;
        return UserAiModelAliasVo.builder()
                .id(a.getId())
                .userId(a.getUserId())
                .alias(a.getAlias())
                .targetVendor(a.getTargetVendor())
                .targetModel(a.getTargetModel())
                .enabled(a.getEnabled())
                .description(a.getDescription())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}

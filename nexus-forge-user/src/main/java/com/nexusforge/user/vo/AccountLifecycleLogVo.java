package com.nexusforge.user.vo;

import com.nexusforge.user.entity.AccountLifecycleLog;
import com.nexusforge.user.enums.AccountActorRole;
import com.nexusforge.user.enums.AccountLifecycleAction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 账号生命周期审计日志视图对象 —— 供 admin 端点返回。
 */
@Schema(description = "账号生命周期审计日志")
public record AccountLifecycleLogVo(

        @Schema(description = "审计行 id")
        Long id,

        @Schema(description = "被操作的用户 id")
        Long userId,

        @Schema(description = "动作")
        AccountLifecycleAction action,

        @Schema(description = "操作人 id(null 表示 SYSTEM)")
        Long actorId,

        @Schema(description = "操作人角色")
        AccountActorRole actorRole,

        @Schema(description = "原因")
        String reason,

        @Schema(description = "上下文 metadata")
        Map<String, Object> metadata,

        @Schema(description = "发生时间")
        OffsetDateTime createdAt
) {
    public static AccountLifecycleLogVo from(AccountLifecycleLog row) {
        return new AccountLifecycleLogVo(
                row.getId(),
                row.getUserId(),
                row.getAction(),
                row.getActorId(),
                row.getActorRole(),
                row.getReason(),
                row.getMetadata(),
                row.getCreatedAt());
    }
}

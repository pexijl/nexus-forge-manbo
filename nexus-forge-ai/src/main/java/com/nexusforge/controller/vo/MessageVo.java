package com.nexusforge.controller.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 单条消息视图对象
 */
@Data
@Schema(description = "对话消息")
public class MessageVo {

    @Schema(description = "消息 ID")
    private Long id;

    @Schema(description = "角色", example = "USER")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "消息序号")
    private Integer seq;

    @Schema(description = "token 用量(AI 回复才有)")
    private UsageVo usage;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;
}
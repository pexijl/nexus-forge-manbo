package com.nexusforge.controller.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话详情视图对象(含消息列表)
 */
@Data
@Schema(description = "会话详情(含消息)")
public class ConversationDetailVo {

    @Schema(description = "会话 ID")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "模型标识")
    private String model;

    @Schema(description = "是否置顶")
    private Boolean pinned;

    @Schema(description = "消息列表(按时间升序)")
    private List<MessageVo> messages;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "最后更新时间")
    private OffsetDateTime updatedAt;
}
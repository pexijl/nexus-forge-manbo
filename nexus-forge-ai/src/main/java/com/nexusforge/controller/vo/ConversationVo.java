package com.nexusforge.controller.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话列表项视图对象
 */
@Data
@Schema(description = "会话列表项")
public class ConversationVo {

    @Schema(description = "会话 ID", example = "1")
    private Long id;

    @Schema(description = "标题", example = "关于 Java 并发的讨论")
    private String title;

    @Schema(description = "模型标识", example = "openai:gpt-4o-mini")
    private String model;

    @Schema(description = "是否置顶")
    private Boolean pinned;

    @Schema(description = "消息数量")
    private Long messageCount;

    @Schema(description = "最后一条消息预览(前 100 字)", example = "好的,我来解释一下...")
    private String lastMessage;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "最后更新时间")
    private OffsetDateTime updatedAt;
}
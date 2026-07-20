package com.nexusforge.controller.vo;

import com.nexusforge.ai.ToolCall;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

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

    /**
     * P4 Step 11:工具调用列表,仅在 assistant 回复触发了工具调用时非空。
     * 当前只暴露不执行 —— 真正的工具执行 + 重入 LLM 在后续 Step 12+ 完成。
     */
    @Schema(description = "工具调用列表(assistant 触发了 tool_calls 时非空)")
    private List<ToolCall> toolCalls;
}
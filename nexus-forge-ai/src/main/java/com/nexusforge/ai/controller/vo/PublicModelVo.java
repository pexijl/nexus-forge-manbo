package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.entity.AiModelCatalog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 公共可用模型 VO — 给 {@code /api/ai/models/available} 用。
 *
 * <p>精简字段:不暴露 cost(内部财务数据)/ description(内部备注) /
 * createdAt / updatedAt(运维元信息),只给客户端用得到的:
 * vendor / model / displayName / capabilities / tier。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "公共可用模型(给前端 UI 选 model 用)")
public class PublicModelVo {

    @Schema(description = "vendor", example = "openai")
    private String vendor;

    @Schema(description = "model 名,直接喂给 LLM client", example = "gpt-4o-mini")
    private String modelName;

    @Schema(description = "UI 友好名", example = "GPT-4o mini")
    private String displayName;

    @Schema(description = "上下文窗口大小")
    private Integer contextWindow;

    @Schema(description = "是否支持视觉")
    private Boolean supportsVision;

    @Schema(description = "是否支持 tools")
    private Boolean supportsTools;

    @Schema(description = "是否支持流式")
    private Boolean supportsStreaming;

    @Schema(description = "限流分层")
    private String tier;

    public static PublicModelVo from(AiModelCatalog m) {
        if (m == null) return null;
        return PublicModelVo.builder()
                .vendor(m.getVendor())
                .modelName(m.getModelName())
                .displayName(m.getDisplayName() != null ? m.getDisplayName() : m.getModelName())
                .contextWindow(m.getContextWindow())
                .supportsVision(m.getSupportsVision())
                .supportsTools(m.getSupportsTools())
                .supportsStreaming(m.getSupportsStreaming())
                .tier(m.getTier())
                .build();
    }
}

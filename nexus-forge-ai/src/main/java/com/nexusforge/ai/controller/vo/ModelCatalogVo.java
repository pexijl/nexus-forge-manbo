package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.entity.AiModelCatalog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 模型目录响应 VO。admin 详情 / 列表用全字段版;公共 {@code /api/ai/models/available}
 * 精简版在 {@link PublicModelVo}(不暴露 cost 等内部字段)。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "模型目录条目")
public class ModelCatalogVo {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "vendor")
    private String vendor;

    @Schema(description = "model 名")
    private String modelName;

    @Schema(description = "UI 友好名")
    private String displayName;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "上下文窗口大小")
    private Integer contextWindow;

    @Schema(description = "单次输出最大 tokens")
    private Integer maxOutputTokens;

    @Schema(description = "是否支持视觉")
    private Boolean supportsVision;

    @Schema(description = "是否支持 tools")
    private Boolean supportsTools;

    @Schema(description = "是否支持流式")
    private Boolean supportsStreaming;

    @Schema(description = "输入 token 单价 USD/1K")
    private BigDecimal costInputPer1k;

    @Schema(description = "输出 token 单价 USD/1K")
    private BigDecimal costOutputPer1k;

    @Schema(description = "限流分层")
    private String tier;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;

    public static ModelCatalogVo from(AiModelCatalog m) {
        if (m == null) return null;
        return ModelCatalogVo.builder()
                .id(m.getId())
                .vendor(m.getVendor())
                .modelName(m.getModelName())
                .displayName(m.getDisplayName())
                .enabled(m.getEnabled())
                .contextWindow(m.getContextWindow())
                .maxOutputTokens(m.getMaxOutputTokens())
                .supportsVision(m.getSupportsVision())
                .supportsTools(m.getSupportsTools())
                .supportsStreaming(m.getSupportsStreaming())
                .costInputPer1k(m.getCostInputPer1k())
                .costOutputPer1k(m.getCostOutputPer1k())
                .tier(m.getTier())
                .description(m.getDescription())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}

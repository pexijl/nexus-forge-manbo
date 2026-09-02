package com.nexusforge.ai.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型目录创建 / 修改请求体。
 *
 * <p>设计原则 — <b>partial update 友好</b>:所有可选字段都是包装类型(null = 不传),
 * {@code ModelCatalogService.applyDto} 只在非 null 时覆盖 entity。{@code vendor} /
 * {@code modelName} 在 create 时必填,update 时不允许改(改了就跟 cache key 对不上,
 * 走 delete + create 更安全)。
 *
 * <p>{@code @JsonInclude(NON_NULL)} 让 null 字段不进 JSON,简化请求体;
 * 比如"只切 enabled"就只传 {@code {"enabled": false}}。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "模型目录创建/修改请求体")
public class ModelCatalogDto {

    /** create 必填,update 禁止改(改了会让 cache key 失配) */
    @Schema(description = "供应商标识,create 必填,update 不允许改", example = "openai")
    @Size(max = 64)
    private String vendor;

    /** create 必填,update 禁止改 */
    @Schema(description = "模型名(create 必填,update 不允许改)", example = "gpt-4o-mini")
    @Size(max = 128)
    private String modelName;

    @Schema(description = "UI 友好名(Phase 4 之前作为显示名)")
    @Size(max = 128)
    private String displayName;

    @Schema(description = "是否启用(false 时网关立即拒绝该 model 的所有调用)")
    private Boolean enabled;

    @Schema(description = "上下文窗口大小(tokens)", example = "128000")
    @Positive
    private Integer contextWindow;

    @Schema(description = "单次输出最大 tokens", example = "16384")
    @Positive
    private Integer maxOutputTokens;

    @Schema(description = "是否支持视觉输入")
    private Boolean supportsVision;

    @Schema(description = "是否支持 tool/function calling")
    private Boolean supportsTools;

    @Schema(description = "是否支持流式输出")
    private Boolean supportsStreaming;

    @Schema(description = "输入 token 单价 USD / 1K", example = "0.000150")
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal costInputPer1k;

    @Schema(description = "输出 token 单价 USD / 1K", example = "0.000600")
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal costOutputPer1k;

    @Schema(description = "限流分层: FREE / STANDARD / PREMIUM", example = "STANDARD",
            allowableValues = {"FREE", "STANDARD", "PREMIUM"})
    @Size(max = 32)
    private String tier;

    @Schema(description = "model 描述(给 admin UI 看)")
    @Size(max = 2000)
    private String description;
}

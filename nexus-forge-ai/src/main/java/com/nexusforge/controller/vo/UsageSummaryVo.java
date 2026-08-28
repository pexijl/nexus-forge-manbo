package com.nexusforge.controller.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * P5 Step 5 — 用户 24h 用量汇总 VO。
 *
 * <p>由 {@link com.nexusforge.service.UsageService#getSummary(Long)} 组装,
 * 用于 {@code /api/ai/usage} 接口返回。
 */
@Data
@Schema(description = "用户 24h 用量汇总")
public class UsageSummaryVo {

    @Schema(description = "24h 内累计输入 token 数")
    private long promptTokens;

    @Schema(description = "24h 内累计输出 token 数")
    private long completionTokens;

    @Schema(description = "24h 内累计总 token 数")
    private long totalTokens;

    @Schema(description = "24h 内成功 LLM 调用次数")
    private long requestCount;

    @Schema(description = "按模型拆分的用量明细")
    private List<ModelUsage> byModel;

    /**
     * 单个模型的用量明细。
     */
    @Data
    @Schema(description = "单模型用量明细")
    public static class ModelUsage {

        @Schema(description = "模型名称", example = "gpt-4o-mini")
        private String model;

        @Schema(description = "该模型累计输入 token 数")
        private long promptTokens;

        @Schema(description = "该模型累计输出 token 数")
        private long completionTokens;

        @Schema(description = "该模型累计总 token 数")
        private long totalTokens;

        @Schema(description = "该模型累计调用次数")
        private long requestCount;
    }
}

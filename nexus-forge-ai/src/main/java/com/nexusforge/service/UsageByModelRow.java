package com.nexusforge.service;

/**
 * P5 按模型拆分的用量聚合单行结果(record DTO)。
 *
 * <p>由 {@code AiMessageUsageRepository.sumByUserModelWindow} 在 GROUP BY u.model
 * 后返回,调用方按 {@link #totalTokens} 降序消费即可(仓库已 ORDER BY)。
 *
 * <p>用于账单拆分、按 model 限额、后台时序图等场景。
 */
public record UsageByModelRow(
        String model,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long requestCount
) {
}

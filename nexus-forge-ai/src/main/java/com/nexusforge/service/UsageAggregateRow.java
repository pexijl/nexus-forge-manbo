package com.nexusforge.service;

/**
 * P5 用量聚合单行结果(record DTO)。
 *
 * <p>由 {@code AiMessageUsageRepository.sumByUserAndWindow} / {@code sumByConversation}
 * 投影到 JPQL {@code new com.nexusforge.service.UsageAggregateRow(...)} 返回。
 *
 * <p>所有字段是 {@code COALESCE(SUM(...), 0)} 的结果,空窗口返回全 0,
 * 调用方无需再做 null 处理。
 *
 * <p>{@code requestCount} 是窗口内命中的消息数(即 ai_message_usage 行数),
 * 1 行 = 1 次成功的 LLM 调用。
 */
public record UsageAggregateRow(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long requestCount
) {
    /** 零行 record,用于空窗口 / 用户无用量场景。 */
    public static UsageAggregateRow empty() {
        return new UsageAggregateRow(0L, 0L, 0L, 0L);
    }
}

package com.nexusforge.ai;

import lombok.Builder;
import lombok.Data;

/**
 * AI对话消耗Token用量实体
 * 用于记录本次对话输入、输出及总消耗token数量
 */
@Data
@Builder
public class ChatUsage {
    /**
     * 提示词消耗token数（用户输入内容token）
     */
    private Integer promptTokens;

    /**
     * 模型回复消耗token数（AI输出内容token）
     */
    private Integer completionTokens;

    /**
     * 本次对话总消耗token数 = promptTokens + completionTokens
     */
    private Integer totalTokens;
}
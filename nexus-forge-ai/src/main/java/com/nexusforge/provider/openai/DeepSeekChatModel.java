package com.nexusforge.provider.openai;

import com.nexusforge.config.AiProperties;
import com.nexusforge.stream.OpenAiStreamParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * DeepSeek ChatModel 实现。
 *
 * <p>DeepSeek API 与 OpenAI Chat Completions 完全兼容(OpenAI-compatible 协议),
 * 所以直接继承 {@link OpenAiCompatibleChatModel} 基类,只换 base-url 与默认模型。
 *
 * <p>默认 base-url: {@code https://api.deepseek.com/v1}<br>
 * 默认模型: {@code deepseek-chat}(对应 V3 系列;R1 推理模型用 {@code deepseek-reasoner},
 * 通过 {@code spring.ai.providers.deepseek.default-model} 切换)
 *
 * <p>启用方式:application.yaml 写
 * <pre>
 * spring:
 *   ai:
 *     providers:
 *       deepseek:
 *         enabled: true
 *         api-key: ${DEEPSEEK_API_KEY}
 *         # base-url / default-model 可省略,用下方默认值
 * </pre>
 *
 * <p>DeepSeek 主流模型(DeepSeek-V3 / DeepSeek-R1)都支持 tool_calls 和 stream,
 * capabilities 与基类默认一致。
 */
@Component
@ConditionalOnProperty(name = "spring.ai.providers.deepseek.enabled", havingValue = "true")
public class DeepSeekChatModel extends OpenAiCompatibleChatModel {

    public DeepSeekChatModel(AiProperties props, ObjectMapper json, OpenAiJsonMapper mapper,
                             OpenAiStreamParser streamParser) {
        super("deepseek",
                "https://api.deepseek.com/v1",
                "deepseek-chat",
                props, json, mapper, streamParser);
    }
}

package com.nexusforge.provider.openai;

import com.nexusforge.config.AiProperties;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.stream.OpenAiStreamParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI ChatModel 实现(P1 / P2 原版)。
 *
 * <p>P4 起继承 {@link OpenAiCompatibleChatModel} 基类,把 base-url / 默认模型
 * 抽象为子类可注入;OpenAI 仍是默认 vendor,base-url = {@code https://api.openai.com/v1},
 * default-model = {@code gpt-4o-mini}。
 *
 * <p>{@code @ConditionalOnProperty(matchIfMissing=true)} 保持 P1 行为:
 * 用户没显式禁用 OpenAI 时它自动启用;若要全局关闭,在 application.yaml 写
 * {@code spring.ai.providers.openai.enabled=false}。
 *
 * <p>{@link #capabilities()} 覆写基类,把 vision 打开(OpenAI GPT-4o 系列原生支持 vision)。
 */
@Component
@ConditionalOnProperty(name = "spring.ai.providers.openai.enabled", havingValue = "true", matchIfMissing = true)
public class OpenAiChatModel extends OpenAiCompatibleChatModel {

    public OpenAiChatModel(AiProperties props, ObjectMapper json, OpenAiJsonMapper mapper,
                           OpenAiStreamParser streamParser) {
        super("openai",
                "https://api.openai.com/v1",
                "gpt-4o-mini",
                props, json, mapper, streamParser);
    }

    @Override
    public ChatCapabilities capabilities() {
        return ChatCapabilities.builder()
                .stream(true)
                .tools(true)
                .vision(true)       // GPT-4o / GPT-4-vision 原生支持图像输入
                .jsonMode(true)
                .build();
    }

    /**
     * 给单元测试使用:暴露 mapper 以便 IT 测试中拼接 tool_calls 期望响应。
     * 包级私有,只在测试包可见。
     */
    OpenAiJsonMapper testMapper() {
        return mapper;
    }

    /** 同上:暴露流式 parser 给测试。 */
    OpenAiStreamParser testStreamParser() {
        return streamParser;
    }
}

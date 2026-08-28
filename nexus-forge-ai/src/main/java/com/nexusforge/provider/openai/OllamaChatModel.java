package com.nexusforge.provider.openai;

import com.nexusforge.config.AiProperties;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.stream.OpenAiStreamParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Ollama 本地推理 ChatModel 实现。
 *
 * <p>Ollama 从 0.1.14 起原生支持 OpenAI-compatible {@code /v1/chat/completions},
 * 所以直接继承 {@link OpenAiCompatibleChatModel} 基类。
 *
 * <p>默认 base-url: {@code http://localhost:11434/v1}<br>
 * 默认模型: {@code llama3.1}(社区常用;其他常用模型:
 * {@code qwen2.5:7b} / {@code codellama:13b} / {@code mistral:7b})
 *
 * <p>注意:本地 Ollama 不需要 api-key,但 OpenAI 协议要求 Authorization 头非空,
 * 基类构造时若 {@code api-key=null} 会导致发出 {@code "Authorization: Bearer null"} 头。
 * Ollama 服务端通常忽略该头(只校验非空),若服务端严格校验会出现 401,
 * 解决办法是在 application.yaml 给 {@code spring.ai.providers.ollama.api-key} 填任意非空字符串
 * (例如 {@code ollama})。
 *
 * <p>启用方式:application.yaml 写
 * <pre>
 * spring:
 *   ai:
 *     providers:
 *       ollama:
 *         enabled: true
 *         api-key: ollama           # Ollama 不校验,任意非空字符串
 *         # base-url / default-model 可省略,默认 localhost:11434 + llama3.1
 * </pre>
 *
 * <p>capabilities 覆写: vision 关闭(Ollama vision 模型相对少见),
 * stream / tools / jsonMode 沿用基类默认。
 */
@Component
@ConditionalOnProperty(name = "spring.ai.providers.ollama.enabled", havingValue = "true")
public class OllamaChatModel extends OpenAiCompatibleChatModel {

    public OllamaChatModel(AiProperties props, ObjectMapper json, OpenAiJsonMapper mapper,
                           OpenAiStreamParser streamParser) {
        super("ollama",
                "http://localhost:11434/v1",
                "llama3.1",
                props, json, mapper, streamParser);
    }

    @Override
    public ChatCapabilities capabilities() {
        return ChatCapabilities.builder()
                .stream(true)
                .tools(true)
                .vision(false)      // Ollama 主流模型本地无 vision
                .jsonMode(true)
                .build();
    }
}

/**
 * OpenAI / OpenAI-compatible 适配层。
 *
 * <p>P1 阶段仅承载 {@code OpenAiChatModel} 一个 ChatModel 实现,使用 JDK 自带
 * {@code java.net.http.HttpClient} 直接调用 {@code https://api.openai.com/v1/chat/completions}。
 *
 * <p>P4 起将在此包内新增 OllamaChatModel / DeepSeekChatModel / QwenChatModel 等
 * OpenAI-compatible 适配,共享本包的 {@link com.nexusforge.ai.model.ChatModel} SPI
 * 与 {@link com.nexusforge.ai.config.AiProperties.Provider} 配置段,
 * 通过 {@code spring.ai.providers.<vendor>.enabled} 单独开关。
 *
 * <p>包内所有引用类型默认非空;只有显式标注 {@code @Nullable} 的参数 / 返回值才能传 null。
 *
 * @author nexus-forge AI 团队
 * @since 1.0.0-P1
 */
@org.springframework.lang.NonNullApi
package com.nexusforge.provider.openai;

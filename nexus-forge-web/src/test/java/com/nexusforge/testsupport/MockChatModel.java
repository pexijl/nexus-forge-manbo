package com.nexusforge.testsupport;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

/**
 * 集成测试用 ChatModel 替身。在 Nexus Forge 的 {@code @Tag("integration")} 测试中,
 * 真实 {@link com.nexusforge.provider.openai.OpenAiChatModel} 因外网依赖 / 构造期
 * 配置强校验而无法启动。本类注入一个 name() == "openai" 的内存 ChatModel,
 * 在测试上下文里接管全部 vendor=openai 的路由。
 *
 * <p>使用方式(IT 类):
 *
 * <pre>{@code
 * @Import(MockChatModel.class)
 * @TestPropertySource(properties = {
 *     "spring.ai.providers.openai.enabled=false",     // 关掉真 OpenAiChatModel
 *     "spring.ai.providers.openai.api-key=mock-key"  // 让 AiProperties.providers
 *                                                   // .openai 非空且 enabled=true
 * })
 * class AiChatIT extends IntegrationTestBase { ... }
 * }</pre>
 *
 * <p>{@code spring.ai.providers.openai.enabled=false} 关掉真 {@code OpenAiChatModel};
 * {@code spring.ai.providers.openai.api-key=mock-key} 让 {@link com.nexusforge.config.AiProperties.Provider}
 * 在 {@code providers.openai} map 里以"启用"姿态出现,满足路由器的 vendor 启用前置校验。
 *
 * <p>流式接口(P2):逐字符回显,最后一片携带 finishReason + usage,模拟 OpenAI 风格 SSE 流;
 * 长度 0 输入只发一个 finish 帧,不触发 chunk。
 */
@TestConfiguration
public class MockChatModel {

    /**
     * 提供一个 name== "openai" 的 ChatModel bean,Spring 通过
     * {@code List<ChatModel>} 把它收进路由器 vendor 索引。
     */
    @Bean
    public ChatModel mockChatModel() {
        return new MockChatModelImpl();
    }

    /**
     * 回声最后一条 USER 消息,前缀 {@code "echo:"}。
     * 数据流之外的字段(id / model / finishReason)固定,方便 IT 做确定性断言。
     */
    public static final class MockChatModelImpl implements ChatModel {

        @Override public String name() { return "openai"; }

        @Override public ChatCapabilities capabilities() {
            return ChatCapabilities.builder()
                    .stream(false)
                    .tools(false)
                    .vision(false)
                    .jsonMode(false)
                    .build();
        }

        @Override public ChatResponse call(ChatRequest request) {
            String echoed = echoOf(request.getMessages());
            return ChatResponse.builder()
                    .id("mock-" + System.nanoTime())
                    .model("mock-openai-model")
                    .content(echoed)
                    .finishReason("stop")
                    .latencyMillis(1L)
                    .build();
        }

        @Override public Flux<ChatChunk> stream(ChatRequest request) {
            String echoed = echoOf(request.getMessages());
            java.util.List<ChatChunk> chunks = new java.util.ArrayList<>(echoed.length() + 1);
            for (int i = 0; i < echoed.length(); i++) {
                chunks.add(ChatChunk.builder()
                        .id("mock-stream")
                        .model("mock-openai-model")
                        .deltaContent(String.valueOf(echoed.charAt(i)))
                        .build());
            }
            chunks.add(ChatChunk.builder()
                    .id("mock-stream-finish")
                    .model("mock-openai-model")
                    .finishReason("stop")
                    .usage(com.nexusforge.ai.ChatUsage.builder()
                            .promptTokens(3)
                            .completionTokens(echoed.length())
                            .totalTokens(3 + echoed.length())
                            .build())
                    .build());
            return Flux.interval(java.time.Duration.ofMillis(10))
                    .take(chunks.size())
                    .map(i -> chunks.get(i.intValue()));
        }
        private static String echoOf(java.util.List<ChatMessage> messages) {
            if (messages == null || messages.isEmpty()) return "echo:";
            ChatMessage lastUser = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if (m.getRole() == com.nexusforge.ai.Role.USER
                        && m.getContent() != null && !m.getContent().isBlank()) {
                    lastUser = m;
                    break;
                }
            }
            return "echo:" + (lastUser == null ? "" : lastUser.getContent());
        }
    }
}

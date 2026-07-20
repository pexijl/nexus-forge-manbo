package com.nexusforge.testsupport;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.ChatUsage;
import com.nexusforge.ai.DeltaToolCall;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolCall;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

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
 * <p>P4 扩展:
 * <ul>
 *   <li>tools 能力 + tool_calls 响应(通过 {@link MockChatModelImpl#setToolsEnabled}
 *       / {@link MockChatModelImpl#setEmitToolCalls} 控制)。</li>
 *   <li>{@link #mockFallbackChatModel()} 第二个 vendor(ollama),用
 *       {@code spring.ai.test.fallback-vendor=true} 启用,专供 FallbackIT 测降级链。</li>
 *   <li>{@link MockChatModelImpl#setBehavior(io.nexusforge.testsupport.MockChatModel.Behavior)}
 *       切换回声 vs 抛错;FallbackIT 用 ollama 行为为 SUCCESS,openai 行为为 THROW_3004,
 *       验证 LlmClient.call 自动跳到次选。</li>
 * </ul>
 */
@TestConfiguration
public class MockChatModel {

    /**
     * mock 行为枚举:控制 {@link MockChatModelImpl#call} 的返回/异常。
     */
    public enum Behavior {
        /** 默认:回声 lastUser message,前缀 "echo:"。 */
        ECHO,
        /** 抛 LlmException(LLM_PROVIDER_ERROR, 3004) — 模拟 5xx 上游错误。 */
        THROW_3004,
        /** 抛 LlmException(LLM_UPSTREAM_TIMEOUT, 3005) — 模拟网络超时。 */
        THROW_3005
    }

    /**
     * 提供一个 name== "openai" 的 ChatModel bean,Spring 通过
     * {@code List<ChatModel>} 把它收进路由器 vendor 索引。
     */
    @Bean
    public ChatModel mockChatModel() {
        return new MockChatModelImpl("openai");
    }

    /**
     * P4:降级链测试用第二个 vendor(ollama)。
     *
     * <p>{@code @ConditionalOnProperty} 控制是否注册 —— FallbackIT 启用
     * ({@code spring.ai.test.fallback-vendor=true}),其它 IT 关闭避免
     * 干扰 OpenAI-only 路径。
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.test.fallback-vendor", havingValue = "true")
    public ChatModel mockFallbackChatModel() {
        return new MockChatModelImpl("ollama");
    }

    /**
     * 可变 mock 实现:支持 tools/behavior 切换。name 通过构造参数指定。
     */
    public static final class MockChatModelImpl implements ChatModel {

        private final String vendorName;
        private boolean toolsEnabled = false;
        private boolean emitToolCalls = false;
        private Behavior behavior = Behavior.ECHO;

        public MockChatModelImpl() { this("openai"); }
        public MockChatModelImpl(String vendorName) { this.vendorName = vendorName; }

        public MockChatModelImpl setToolsEnabled(boolean v) { this.toolsEnabled = v; return this; }
        public MockChatModelImpl setEmitToolCalls(boolean v) { this.emitToolCalls = v; return this; }
        public MockChatModelImpl setBehavior(Behavior b) { this.behavior = b; return this; }

        @Override public String name() { return vendorName; }

        @Override public ChatCapabilities capabilities() {
            return ChatCapabilities.builder()
                    .stream(true)
                    .tools(toolsEnabled)
                    .vision(false)
                    .jsonMode(true)
                    .build();
        }

        @Override public ChatResponse call(ChatRequest request) {
            if (behavior == Behavior.THROW_3004) {
                throw new com.nexusforge.exception.LlmException(
                        com.nexusforge.enums.ResultCode.LLM_PROVIDER_ERROR,
                        "[mock-" + vendorName + "] 模拟 5xx 上游错误");
            }
            if (behavior == Behavior.THROW_3005) {
                throw new com.nexusforge.exception.LlmException(
                        com.nexusforge.enums.ResultCode.LLM_UPSTREAM_TIMEOUT,
                        "[mock-" + vendorName + "] 模拟上游超时");
            }
            if (shouldEmitToolCall(request)) {
                return ChatResponse.builder()
                        .id("mock-toolcall-" + vendorName)
                        .model("mock-" + vendorName + "-model")
                        .content("")
                        .finishReason("tool_calls")
                        .toolCalls(List.of(buildWeatherToolCall(request)))
                        .usage(ChatUsage.builder()
                                .promptTokens(5).completionTokens(0).totalTokens(5).build())
                        .latencyMillis(1L)
                        .build();
            }
            String echoed = echoOf(request.getMessages());
            return ChatResponse.builder()
                    .id("mock-" + vendorName + "-" + System.nanoTime())
                    .model("mock-" + vendorName + "-model")
                    .content(echoed)
                    .finishReason("stop")
                    .latencyMillis(1L)
                    .build();
        }

        @Override public Flux<ChatChunk> stream(ChatRequest request) {
            // 降级链 stream 仅在订阅前解析 vendor,测试关注 call 路径;
            // stream 简化为 echo 实现,与 P1/P2 一致。
            String echoed = echoOf(request.getMessages());
            List<ChatChunk> chunks = new ArrayList<>(echoed.length() + 1);
            for (int i = 0; i < echoed.length(); i++) {
                chunks.add(ChatChunk.builder()
                        .id("mock-stream-" + vendorName)
                        .model("mock-" + vendorName + "-model")
                        .deltaContent(String.valueOf(echoed.charAt(i)))
                        .build());
            }
            chunks.add(ChatChunk.builder()
                    .id("mock-stream-finish-" + vendorName)
                    .model("mock-" + vendorName + "-model")
                    .finishReason("stop")
                    .usage(ChatUsage.builder()
                            .promptTokens(3)
                            .completionTokens(echoed.length())
                            .totalTokens(3 + echoed.length())
                            .build())
                    .build());
            return Flux.interval(java.time.Duration.ofMillis(10))
                    .take(chunks.size())
                    .map(i -> chunks.get(i.intValue()));
        }

        /**
         * P4:决定是否走 tool_calls 终止帧。
         * <ul>
         *   <li>{@code emitToolCalls} 必须为 true(测试场景显式开启)</li>
         *   <li>request 必须非空带 tools</li>
         *   <li>最后一条 USER 消息包含 "weather" 关键词(简化触发条件)</li>
         * </ul>
         */
        private boolean shouldEmitToolCall(ChatRequest request) {
            if (!emitToolCalls) return false;
            if (request.getTools() == null || request.getTools().isEmpty()) return false;
            String last = lastUserContent(request);
            return last != null && last.toLowerCase().contains("weather");
        }

        private ToolCall buildWeatherToolCall(ChatRequest request) {
            String city = "Beijing";
            String last = lastUserContent(request);
            if (last != null && last.toLowerCase().contains("shanghai")) city = "Shanghai";
            String argsJson = "{\"city\":\"" + city + "\"}";
            try {
                return ToolCall.builder()
                        .id("call_mock_001_" + vendorName)
                        .name("weather")
                        .arguments(new tools.jackson.databind.ObjectMapper()
                                .readTree(argsJson))
                        .build();
            } catch (Exception e) {
                throw new RuntimeException("MockChatModel 构造 tool_call 失败", e);
            }
        }

        private static String echoOf(List<ChatMessage> messages) {
            if (messages == null || messages.isEmpty()) return "echo:";
            ChatMessage lastUser = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage m = messages.get(i);
                if (m.getRole() == Role.USER
                        && m.getContent() != null && !m.getContent().isBlank()) {
                    lastUser = m;
                    break;
                }
            }
            return "echo:" + (lastUser == null ? "" : lastUser.getContent());
        }

        private static String lastUserContent(ChatRequest request) {
            if (request.getMessages() == null) return null;
            for (int i = request.getMessages().size() - 1; i >= 0; i--) {
                ChatMessage m = request.getMessages().get(i);
                if (m.getRole() == Role.USER && m.getContent() != null) return m.getContent();
            }
            return null;
        }
    }
}
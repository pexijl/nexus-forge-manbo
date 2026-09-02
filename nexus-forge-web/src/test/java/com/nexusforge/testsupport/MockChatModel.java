package com.nexusforge.testsupport;

import com.nexusforge.exception.LlmException;
import com.nexusforge.enums.ResultCode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 集成测试用 Spring AI ChatModel 替身。
 *
 * <p>Phase 6 重写:实现 Spring AI 的 {@link ChatModel} 接口(替代原
 * {@code com.nexusforge.model.ChatModel}),返回 Spring AI 的 {@link ChatResponse}。
 *
 * <p>Spring 容器按 bean 名收 ChatModel,本类生成 2 个 bean:
 * <ul>
 *   <li>{@code openAiChatModel} — 默认 vendor,主路径</li>
 *   <li>{@code ollamaChatModel} — 降级链测试用,需要
 *       {@code spring.ai.test.fallback-vendor=true} 启用</li>
 * </ul>
 *
 * <p>bean 名遵循 Spring AI starter 命名约定 {@code <vendor>ChatModel},
 * 被 {@code ChatModelRouter.normalizeBeanName} 归一化为小写 vendor 名
 * (openAiChatModel → openai)。
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
     * 主 vendor — openai。Bean 名 {@code openAiChatModel} 匹配 Spring AI
     * 命名约定,被 router 归一化为 vendor "openai"。
     */
    @Bean("openAiChatModel")
    public ChatModel mockChatModel() {
        return new MockChatModelImpl("openai");
    }

    /**
     * 降级链测试用第二个 vendor(ollama)。
     * {@code @ConditionalOnProperty} 控制是否注册 — FallbackIT 启用
     * ({@code spring.ai.test.fallback-vendor=true}),其它 IT 关闭避免
     * 干扰 OpenAI-only 路径。
     */
    @Bean("ollamaChatModel")
    @ConditionalOnProperty(name = "spring.ai.test.fallback-vendor", havingValue = "true")
    public ChatModel mockFallbackChatModel() {
        return new MockChatModelImpl("ollama");
    }

    /**
     * 可变 mock 实现:支持 tools/behavior 切换。vendor 通过构造参数指定。
     * 类名刻意以 "ChatModel" 结尾(后缀会被 ChatModelRouter.normalizeBeanName
     * 剥掉),simple name 形如 {@code MockChatModelImpl} 不用 — vendor 来自 bean 名
     * 归一化,不靠反射。
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

        @Override
        public ChatResponse call(Prompt prompt) {
            if (behavior == Behavior.THROW_3004) {
                throw new LlmException(ResultCode.LLM_PROVIDER_ERROR,
                        "[mock-" + vendorName + "] 模拟 5xx 上游错误");
            }
            if (behavior == Behavior.THROW_3005) {
                throw new LlmException(ResultCode.LLM_UPSTREAM_TIMEOUT,
                        "[mock-" + vendorName + "] 模拟上游超时");
            }
            if (shouldEmitToolCall(prompt)) {
                return buildToolCallResponse(prompt);
            }
            return buildEchoResponse(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            // 流式响应:每帧一个 ChatResponse(沿用 Phase 2b 的 SSE wire 协议)。
            String echoed = echoOf(prompt);
            List<ChatResponse> frames = new ArrayList<>(echoed.length() + 1);
            for (int i = 0; i < echoed.length(); i++) {
                AssistantMessage msg = new AssistantMessage(String.valueOf(echoed.charAt(i)));
                frames.add(new ChatResponse(List.of(new Generation(msg))));
            }
            AssistantMessage finishMsg = new AssistantMessage(null);
            frames.add(new ChatResponse(
                    List.of(new Generation(finishMsg)),
                    ChatResponseMetadata.builder()
                            .model("mock-" + vendorName + "-model")
                            .usage(stubUsage(3, echoed.length()))
                            .build()));
            return Flux.interval(java.time.Duration.ofMillis(10))
                    .take(frames.size())
                    .map(i -> frames.get(i.intValue()));
        }

        // ─────────── internal builders ───────────

        private ChatResponse buildEchoResponse(Prompt prompt) {
            String echoed = echoOf(prompt);
            int promptLen = prompt.getInstructions().stream()
                    .mapToInt(m -> {
                        String t = m.getText();
                        return t == null ? 0 : t.length();
                    }).sum();
            AssistantMessage msg = new AssistantMessage(echoed);
            Usage usage = stubUsage(promptLen / 4 + 1, echoed.length() / 4 + 1);
            return new ChatResponse(
                    List.of(new Generation(msg)),
                    ChatResponseMetadata.builder()
                            .model("mock-" + vendorName + "-model")
                            .usage(usage)
                            .build());
        }

        private ChatResponse buildToolCallResponse(Prompt prompt) {
            String last = lastUserContent(prompt);
            String city = (last != null && last.toLowerCase().contains("shanghai")) ? "Shanghai" : "Beijing";
            String argsJson = "{\"city\":\"" + city + "\"}";
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call_mock_001_" + vendorName, "weather", argsJson, null);
            // Spring AI 2.0 AssistantMessage 没有 (content, List<ToolCall>, Map) 公共构造器,
            // 用 builder 构造(content="" + toolCalls + 空 properties)
            AssistantMessage msg = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build();
            Usage usage = stubUsage(5, 0);
            return new ChatResponse(
                    List.of(new Generation(msg)),
                    ChatResponseMetadata.builder()
                            .model("mock-" + vendorName + "-model")
                            .usage(usage)
                            .build());
        }

        private static Usage stubUsage(int prompt, int completion) {
            final int p = prompt, c = completion, t = p + c;
            return new Usage() {
                @Override public Integer getPromptTokens() { return p; }
                @Override public Integer getCompletionTokens() { return c; }
                @Override public Integer getTotalTokens() { return t; }
                @Override public Object getNativeUsage() { return null; }
            };
        }

        /**
         * 决定是否走 tool_calls 终止帧。
         * <ul>
         *   <li>{@code emitToolCalls} 必须为 true(测试场景显式开启)</li>
         *   <li>prompt.getOptions() 里的 {@code ToolCallingChatOptions#getToolCallbacks()}
         *       必须非空(对应原 ChatRequest.tools)</li>
         *   <li>最后一条 USER 消息包含 "weather" 关键词(简化触发条件)</li>
         * </ul>
         */
        private boolean shouldEmitToolCall(Prompt prompt) {
            if (!emitToolCalls) return false;
            ChatOptions opts = prompt.getOptions();
            if (opts == null) return false;
            // Spring AI 2.0 用 ToolCallingChatOptions#getToolCallbacks() 暴露工具列表
            if (opts instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tco
                    && tco.getToolCallbacks() != null
                    && !tco.getToolCallbacks().isEmpty()) {
                String last = lastUserContent(prompt);
                return last != null && last.toLowerCase().contains("weather");
            }
            return false;
        }

        private static String echoOf(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            if (messages == null || messages.isEmpty()) return "echo:";
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (m instanceof UserMessage um) {
                    String t = um.getText();
                    if (t != null && !t.isBlank()) return "echo:" + t;
                }
            }
            return "echo:";
        }

        private static String lastUserContent(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            if (messages == null) return null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (m instanceof UserMessage um) {
                    return um.getText();
                }
            }
            return null;
        }
    }
}

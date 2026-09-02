package com.nexusforge.client;

import com.nexusforge.ai.entity.AiModelCatalog;
import com.nexusforge.ai.service.FallbackChainService;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainSource;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainView;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.router.ChatModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * /chat/stream ClassCastException 修复 — Spring AI 2.0 的 3 个 vendor ChatOptions
 * 互不继承(OpenAiChatOptions / AnthropicChatOptions / OllamaChatOptions 都
 * 各自硬转),LlmClient 之前一律用 OpenAiChatOptions 包装在 anthropic 路径下
 * 必抛。
 *
 * <p>本测试覆盖:
 * <ol>
 *   <li>{@link LlmClient#withModelInOptions} 按 vendor 分发到正确的 ChatOptions
 *       构造器(单元测试,无 Spring context)。deepseek 之前走 DeepSeekChatOptions,
 *       现在 DeepSeek 已统一走 OpenAI starter(详见 build.gradle + 上一会话 bridge
 *       配置),所以 deepseek vendor 同样用 OpenAiChatOptions。</li>
 *   <li>LlmClient.stream(prompt, "deepseek", ...) 把 OpenAiChatOptions 喂给
 *       openAiChatModel(经 ChatModelRouter aliasing 路由)不再 ClassCast
 *       (集成测试,模拟真实 ChatModel 行为)</li>
 *   <li>LlmClient.call(prompt, "openai", ...) 降级到 anthropic 时 rebuild
 *       options 为 AnthropicChatOptions(降级链跨 vendor 测试)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmClientOptionsDispatchTest {

    /**
     * Phase 1 — LlmClient 多了 ModelCatalogService 依赖(每次 call 前查 catalog
     * 校验 admin gate)。本测试不关心 catalog 校验,统一 stub 一个"啥都允许"
     * 的 catalog,让原有 vendor dispatch / fallback 测试逻辑不变。
     * 专门的 catalog gate 测试在 {@link LlmClientCatalogCheckTest}。
     */
    @Mock ModelCatalogService catalogService;
    @Mock com.nexusforge.ai.provider.SystemKeyChatModelFactory systemKeyFactory;
    @Mock FallbackChainService fallbackChainService;

    @BeforeEach
    void stubCatalog() {
        AiModelCatalog any = new AiModelCatalog();
        any.setVendor("any");
        any.setModelName("any");
        any.setEnabled(true);
        when(catalogService.findByVendorModel(any(), any())).thenReturn(any);
    }

    // ─────────────────────── 单元:withModelInOptions ───────────────────────

    @Nested
    @DisplayName("withModelInOptions 单元:按 vendor 分发 ChatOptions 类型")
    class WithModelInOptionsUnit {

        @Test
        @DisplayName("deepseek → OpenAiChatOptions(DeepSeek 已统一走 OpenAI starter),model 字段为纯模型名")
        void deepseek() {
            Prompt result = LlmClient.withModelInOptions(
                    new Prompt(List.of(new UserMessage("hi"))),
                    "deepseek", "deepseek-v4-flash");
            assertThat(result.getOptions()).isInstanceOf(OpenAiChatOptions.class);
            // 关键:model 字段是纯模型名,Spring AI 透传给 DeepSeek/OpenAI 不会 400
            assertThat(result.getOptions().getModel()).isEqualTo("deepseek-v4-flash");
        }

        @Test
        @DisplayName("anthropic → AnthropicChatOptions,model 字段为纯模型名")
        void anthropic() {
            Prompt result = LlmClient.withModelInOptions(
                    new Prompt(List.of(new UserMessage("hi"))),
                    "anthropic", "claude-3-5-haiku-latest");
            assertThat(result.getOptions()).isInstanceOf(AnthropicChatOptions.class);
            assertThat(result.getOptions().getModel()).isEqualTo("claude-3-5-haiku-latest");
        }

        @Test
        @DisplayName("openai / ollama → OpenAiChatOptions(OpenAI 协议家族共用)")
        void openAiProtocolFamily() {
            for (String vendor : List.of("openai", "ollama")) {
                Prompt result = LlmClient.withModelInOptions(
                        new Prompt(List.of(new UserMessage("hi"))),
                        vendor, "any-model");
                assertThat(result.getOptions())
                        .as("vendor=%s 期望 OpenAiChatOptions", vendor)
                        .isInstanceOf(OpenAiChatOptions.class);
            }
        }

        @Test
        @DisplayName("vendor 大小写不敏感:DEEPSEEK / OpenAI / Anthropic 都识别")
        void caseInsensitive() {
            assertThat(LlmClient.withModelInOptions(
                    new Prompt(List.of(new UserMessage("hi"))), "DEEPSEEK", "x")
                    .getOptions()).isInstanceOf(OpenAiChatOptions.class);
            assertThat(LlmClient.withModelInOptions(
                    new Prompt(List.of(new UserMessage("hi"))), "Anthropic", "x")
                    .getOptions()).isInstanceOf(AnthropicChatOptions.class);
        }

        @Test
        @DisplayName("未知 vendor / null vendor 兜底 OpenAiChatOptions(防御未知接入)")
        void unknownVendorFallsBackToOpenAi() {
            assertThat(LlmClient.withModelInOptions(
                    new Prompt(List.of(new UserMessage("hi"))), "some-new-vendor", "x")
                    .getOptions()).isInstanceOf(OpenAiChatOptions.class);
            assertThat(LlmClient.withModelInOptions(
                    new Prompt(List.of(new UserMessage("hi"))), null, "x")
                    .getOptions()).isInstanceOf(OpenAiChatOptions.class);
        }

        @Test
        @DisplayName("model=null/空 → 不动 prompt,options 保持原样(走 ChatModel.defaultOptions)")
        void nullOrBlankModelReturnsSrcUnchanged() {
            Prompt src = new Prompt(List.of(new UserMessage("hi")));
            assertThat(LlmClient.withModelInOptions(src, "openai", null)).isSameAs(src);
            assertThat(LlmClient.withModelInOptions(src, "openai", "")).isSameAs(src);
            assertThat(LlmClient.withModelInOptions(src, "openai", "   ")).isSameAs(src);
        }
    }

    // ─────────────────────── 集成:/chat/stream ───────────────────────

    @Nested
    @DisplayName("集成测试:stream / call 端到端不 ClassCast")
    class EndToEndIntegration {

        @Test
        @DisplayName("stream(deepseek) → 经 ChatModelRouter aliasing 路由到 openAiChatModel,收到 OpenAiChatOptions 包装(对齐真实 OpenAiChatModel 硬转)")
        void streamDeepseekVendorRoutedViaAliasing() {
            // deepseek 不再有独立 ChatModel bean;ChatModelRouter 通过 aliasing 把
            // "deepseek" vendor 路由到 openAiChatModel(map 里只放 openAiChatModel)。
            // LlmClient.withModelInOptions("deepseek") 产出 OpenAiChatOptions —
            // 跟实际 ChatModel 期望的类型匹配,不会 ClassCast。
            //
            // Phase 5 — LlmClient 改用 systemKeyFactory.resolveOrCreate(vendor) 拿 ChatModel,
            // 不再走 router 的 bean。这里 stub factory 让 deepseek 请求也走 OpenAI-like model
            // (跟 router aliasing 行为对齐)。
            OpenAiLikeChatModel openaiModel = new OpenAiLikeChatModel();
            LlmClient client = newClient(
                    Map.of("openAiChatModel", openaiModel),
                    Map.of("deepseek", provider(true, "deepseek-v4-flash", "https://api.deepseek.com")),
                    "deepseek", List.of());
            // 显式 stub:deepseek vendor 走 openai model(对齐 router aliasing)
            when(systemKeyFactory.resolveOrCreate("deepseek")).thenReturn(openaiModel);

            StepVerifier.create(
                    client.stream(new Prompt(List.of(new UserMessage("hi"))),
                            "deepseek", "deepseek-v4-flash"))
                    .expectNextCount(1)
                    .verifyComplete();

            // 关键断言:mock 内部 (OpenAiChatOptions) prompt.getOptions() 硬转没抛
            assertThat(openaiModel.captured).isNotNull();
            assertThat(openaiModel.captured.getOptions()).isInstanceOf(OpenAiChatOptions.class);
            // 关键:model 字段是纯模型名,Spring AI 透传给 DeepSeek API 不会 400
            assertThat(openaiModel.captured.getOptions().getModel()).isEqualTo("deepseek-v4-flash");
        }

        @Test
        @DisplayName("stream(anthropic) → mock AnthropicChatModel 收到的 prompt 用 AnthropicChatOptions 包装")
        void streamAnthropicVendorDoesNotClassCast() {
            AnthropicLikeChatModel anthropicModel = new AnthropicLikeChatModel();
            LlmClient client = newClient(
                    Map.of("anthropicChatModel", anthropicModel),
                    Map.of("anthropic", provider(true, "claude-3-5-haiku-latest", null)),
                    "anthropic", List.of());

            StepVerifier.create(
                    client.stream(new Prompt(List.of(new UserMessage("hi"))),
                            "anthropic", "claude-3-5-haiku-latest"))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(anthropicModel.captured).isNotNull();
            assertThat(anthropicModel.captured.getOptions()).isInstanceOf(AnthropicChatOptions.class);
        }

        @Test
        @DisplayName("stream(openai) 抛 LLM_PROVIDER_ERROR → wrapStream onErrorMap 路径,openai 收到 prompt 抛错(cross-vendor 由 call() 测试)")
        void streamPrimaryThrowsErrorPropagates() {
            // stream 实际是 pickFirstUsableHop 单 hop 走(不 iterate 整链),
            // 所以首选 openai 抛错时 wrapStream 的 onErrorMap 直接透传,不会真的
            // 跳到 anthropic — 跨 vendor rebuild 由下面的 call() 测试覆盖。
            // 这里断言的是:openai 抛 LLM_PROVIDER_ERROR 被 wrapStream 透传,
            // anthropic 不该被调用。
            OpenAiLikeChatModel openaiModel = new OpenAiLikeChatModel();
            openaiModel.throwOnCall = true;
            AnthropicLikeChatModel anthropicModel = new AnthropicLikeChatModel();

            AiProperties props = new AiProperties();
            props.setDefaultVendor("openai");
            props.getProviders().put("openai",    provider(true, "gpt-4o-mini", "https://api.openai.com"));
            props.getProviders().put("anthropic", provider(true, "claude-3-5-haiku-latest", null));
            // Phase 7 — 降级链改灌进 service.findEffective() mock
            lenient().when(fallbackChainService.findEffective()).thenReturn(
                    new FallbackChainView(List.of("anthropic"), FallbackChainSource.DB, null));
            ChatModelRouter router = new ChatModelRouter(Map.of(
                    "openAiChatModel",    openaiModel,
                    "anthropicChatModel", anthropicModel), props, fallbackChainService);
            // Phase 5 — systemKeyFactory 按 vendor 名 dispatch 到对应的 chatModel bean
            when(systemKeyFactory.resolveOrCreate("openai")).thenReturn(openaiModel);
            when(systemKeyFactory.resolveOrCreate("anthropic")).thenReturn(anthropicModel);
            LlmClient client = new LlmClient(router, props, List.of(), catalogService, systemKeyFactory);

            StepVerifier.create(
                    client.stream(new Prompt(List.of(new UserMessage("hi"))),
                            "openai", "gpt-4o-mini"))
                    .expectErrorMatches(t -> t instanceof LlmException
                            && ((LlmException) t).getCode() == ResultCode.LLM_PROVIDER_ERROR.getCode())
                    .verify();

            // 首选 openai 拿到 prompt 抛错
            assertThat(openaiModel.captured).isNotNull();
            assertThat(openaiModel.captured.getOptions()).isInstanceOf(OpenAiChatOptions.class);
            // stream 路径下不迭代降级,anthropic 不该被调用
            assertThat(anthropicModel.captured)
                    .as("stream 是单 hop,首选 openai 抛错后不跳到 anthropic")
                    .isNull();
        }

        @Test
        @DisplayName("call(openai) 抛 LLM_PROVIDER_ERROR → 降级到 anthropic,anthropic 收到 AnthropicChatOptions(cross-vendor rebuild)")
        void callFallbackToAnthropicRebuildsOptions() {
            OpenAiLikeChatModel openaiModel = new OpenAiLikeChatModel();
            openaiModel.throwOnCall = true;
            AnthropicLikeChatModel anthropicModel = new AnthropicLikeChatModel();

            AiProperties props = new AiProperties();
            props.setDefaultVendor("openai");
            props.getProviders().put("openai",    provider(true, "gpt-4o-mini", "https://api.openai.com"));
            props.getProviders().put("anthropic", provider(true, "claude-3-5-haiku-latest", null));
            // Phase 7 — 降级链改灌进 service.findEffective() mock
            lenient().when(fallbackChainService.findEffective()).thenReturn(
                    new FallbackChainView(List.of("anthropic"), FallbackChainSource.DB, null));
            ChatModelRouter router = new ChatModelRouter(Map.of(
                    "openAiChatModel",    openaiModel,
                    "anthropicChatModel", anthropicModel), props, fallbackChainService);
            when(systemKeyFactory.resolveOrCreate("openai")).thenReturn(openaiModel);
            when(systemKeyFactory.resolveOrCreate("anthropic")).thenReturn(anthropicModel);
            LlmClient client = new LlmClient(router, props, List.of(), catalogService, systemKeyFactory);

            ChatResponse resp = client.call(
                    new Prompt(List.of(new UserMessage("hi"))),
                    "openai", "gpt-4o-mini");
            assertThat(resp).isNotNull();
            assertThat(anthropicModel.captured).isNotNull();
            assertThat(anthropicModel.captured.getOptions()).isInstanceOf(AnthropicChatOptions.class);
        }
    }

    // ─────────────────────── helpers ───────────────────────

    private static AiProperties.Provider provider(boolean enabled, String defaultModel, String baseUrl) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(enabled);
        p.setDefaultModel(defaultModel);
        p.setBaseUrl(baseUrl);
        return p;
    }

    private LlmClient newClient(Map<String, ChatModel> beanToModel,
                                Map<String, AiProperties.Provider> providers,
                                String defaultVendor,
                                List<String> fallbackChain) {
        AiProperties props = new AiProperties();
        props.setDefaultVendor(defaultVendor);
        providers.forEach((k, v) -> props.getProviders().put(k, v));
        // Phase 7 — router 改读 service,期望 chain 灌进 service.findEffective() mock
        List<String> effective = fallbackChain == null ? List.of() : fallbackChain;
        lenient().when(fallbackChainService.findEffective()).thenReturn(
                new FallbackChainView(effective, FallbackChainSource.DB, null));
        // Phase 5 — systemKeyFactory 按 vendor 名 dispatch 到 beanToModel 里的 ChatModel
        beanToModel.forEach((beanName, model) -> {
            String vendor = beanName.endsWith("ChatModel")
                    ? beanName.substring(0, beanName.length() - "ChatModel".length())
                    : beanName;
            when(systemKeyFactory.resolveOrCreate(vendor.toLowerCase())).thenReturn(model);
        });
        return new LlmClient(new ChatModelRouter(beanToModel, props, fallbackChainService),
                props, List.of(), catalogService, systemKeyFactory);
    }

    /**
     * 模拟 {@code OpenAiChatModel.call/stream} 行为:把
     * {@code prompt.getOptions()} 硬转 {@link OpenAiChatOptions},类型不匹配
     * 就抛 {@link ClassCastException}(对齐 Spring AI 真实行为)。
     *
     * <p>注意 {@code stream(Prompt)} 走 {@link Flux#defer},把 cast 和
     * {@code call} 逻辑推迟到订阅时(对齐真实 ChatModel 行为),避免 mock 同步
     * 抛错污染测试断言位置。
     */
    static class OpenAiLikeChatModel implements ChatModel {
        volatile Prompt captured;
        volatile boolean throwOnCall = false;

        @Override
        public ChatResponse call(Prompt prompt) {
            OpenAiChatOptions opts = (OpenAiChatOptions) prompt.getOptions(); // 真实 OpenAiChatModel 行为
            captured = prompt;
            if (throwOnCall) {
                throw new LlmException(ResultCode.LLM_PROVIDER_ERROR,
                        "openai upstream 模拟失败");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                try {
                    return Flux.just(call(prompt));
                } catch (RuntimeException e) {
                    return Flux.error(e);
                }
            });
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().model("gpt-4o-mini").build();
        }
    }

    /**
     * 模拟 {@code AnthropicChatModel.call/stream} 行为:把
     * {@code prompt.getOptions()} 硬转 {@link AnthropicChatOptions}。
     */
    static class AnthropicLikeChatModel implements ChatModel {
        volatile Prompt captured;

        @Override
        public ChatResponse call(Prompt prompt) {
            AnthropicChatOptions opts = (AnthropicChatOptions) prompt.getOptions();
            captured = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return AnthropicChatOptions.builder().model("claude-3-5-haiku-latest").build();
        }
    }
}

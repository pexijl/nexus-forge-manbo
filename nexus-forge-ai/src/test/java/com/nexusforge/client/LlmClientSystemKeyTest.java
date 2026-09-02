package com.nexusforge.client;

import com.nexusforge.ai.entity.AiModelCatalog;
import com.nexusforge.ai.provider.SystemKeyChatModelFactory;
import com.nexusforge.ai.service.FallbackChainService;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.config.AiProperties;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 5 — LlmClient 集成测试,验证 call/stream 系统 Key 路径走 SystemKeyChatModelFactory
 * 而非 router 的固定 ChatModel bean。
 *
 * <p>核心断言:即使 router 的 ChatModel bean 抛错,只要 factory.resolveOrCreate
 * 返回的 ChatModel 不抛错,LlmClient.call 仍能正常返回 — 即 call 用的是 factory
 * 产物,不是 router bean。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LlmClient — Phase 5 系统 Key 路径走 SystemKeyChatModelFactory")
class LlmClientSystemKeyTest {

    @Mock ModelCatalogService catalogService;
    @Mock SystemKeyChatModelFactory systemKeyFactory;
    @Mock FallbackChainService fallbackChainService;
    @Mock ChatModel routerBeanModel;     // router 拿到的(被绕过,不应被调用)
    @Mock ChatModel factoryProducedModel; // factory 生成的(应该被调用)

    private LlmClient client;
    private AiProperties props;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        props.setDefaultVendor("openai");
        AiProperties.Provider openaiProvider = new AiProperties.Provider();
        openaiProvider.setEnabled(true);
        openaiProvider.setDefaultModel("gpt-4o-mini");
        props.getProviders().put("openai", openaiProvider);
        // 加 deepseek provider 让 router.resolveWithFallback("deepseek", ...) 不报 "vendor 未配置"
        AiProperties.Provider deepseekProvider = new AiProperties.Provider();
        deepseekProvider.setEnabled(true);
        deepseekProvider.setDefaultModel("deepseek-v3");
        props.getProviders().put("deepseek", deepseekProvider);

        // router 里放 routerBeanModel(stub,会被 aliasing 用但 call 不调它)
        ChatModelRouter router = new ChatModelRouter(
                Map.of("openAiChatModel", routerBeanModel), props, fallbackChainService);
        // Phase 7 — 本测试不关心降级链,给个空 chain stub(router 走 service 不再走 props)
        lenient().when(fallbackChainService.findEffective()).thenReturn(
                new FallbackChainService.FallbackChainView(
                        List.of(), FallbackChainService.FallbackChainSource.EMPTY, null));

        // catalog 放行
        AiModelCatalog allowed = new AiModelCatalog();
        allowed.setVendor("any");
        allowed.setModelName("any");
        allowed.setEnabled(true);
        when(catalogService.findByVendorModel(any(), any())).thenReturn(allowed);

        client = new LlmClient(router, props, List.of(), catalogService, systemKeyFactory);
    }

    // ─────────────────── call(Prompt, vendor, model) ───────────────────

    @Nested
    @DisplayName("call(Prompt, vendor, model) — 走 factory 产物")
    class CallSystemKey {

        @Test
        @DisplayName("factory 产物被调;router 的 bean 不被调")
        void call_uses_factory_product() {
            // factory 返回 factoryProducedModel
            when(systemKeyFactory.resolveOrCreate("openai")).thenReturn(factoryProducedModel);
            // routerBeanModel 抛错 — 如果被调到就 fail
            when(routerBeanModel.call(any(Prompt.class)))
                    .thenThrow(new RuntimeException("router bean should NOT be called"));

            ChatResponse resp = chatResponse("gpt-4o-mini", "ok", 1, 1);
            when(factoryProducedModel.call(any(Prompt.class))).thenReturn(resp);

            ChatResponse result = client.call(
                    new Prompt(List.of(new UserMessage("hi"))),
                    "openai", "gpt-4o-mini");

            assertThat(result).isSameAs(resp);
            // 关键:factory 产物被调
            verify(factoryProducedModel).call(any(Prompt.class));
            // 关键:router 的 bean 没被调
            verify(routerBeanModel, never()).call(any(Prompt.class));
        }

        @Test
        @DisplayName("factory 按 vendor 名 dispatch — 'deepseek' 调用对应 factory 解析")
        void call_dispatches_vendor_to_factory() {
            when(systemKeyFactory.resolveOrCreate("deepseek")).thenReturn(factoryProducedModel);
            // catalog 允许 deepseek
            AiModelCatalog deepseekAllowed = new AiModelCatalog();
            deepseekAllowed.setVendor("deepseek");
            deepseekAllowed.setModelName("deepseek-v3");
            deepseekAllowed.setEnabled(true);
            when(catalogService.findByVendorModel(eq("deepseek"), eq("deepseek-v3")))
                    .thenReturn(deepseekAllowed);

            ChatResponse resp = chatResponse("deepseek-v3", "hi", 1, 1);
            when(factoryProducedModel.call(any(Prompt.class))).thenReturn(resp);

            client.call(
                    new Prompt(List.of(new UserMessage("hi"))),
                    "deepseek", "deepseek-v3");

            // 关键:factory 用 "deepseek"(原 vendor 名,非 router aliasing 的 "openai")调用
            verify(systemKeyFactory).resolveOrCreate("deepseek");
            verify(factoryProducedModel).call(any(Prompt.class));
        }
    }

    // ─────────────────── call(Prompt) — 无显式 vendor/model ───────────────────

    @Nested
    @DisplayName("call(Prompt) — primary vendor 走 factory")
    class CallPrimary {

        @Test
        @DisplayName("无显式 vendor/model → router 解析 primary → factory.resolveOrCreate(primary)")
        void call_primary_uses_factory() {
            when(systemKeyFactory.resolveOrCreate("openai")).thenReturn(factoryProducedModel);
            ChatResponse resp = chatResponse("gpt-4o-mini", "ok", 1, 1);
            when(factoryProducedModel.call(any(Prompt.class))).thenReturn(resp);

            ChatResponse result = client.call(new Prompt(List.of(new UserMessage("hi"))));

            assertThat(result).isSameAs(resp);
            verify(systemKeyFactory).resolveOrCreate("openai");
            verify(routerBeanModel, never()).call(any(Prompt.class));
        }
    }

    // ─────────────────── stream ───────────────────

    @Nested
    @DisplayName("stream — 走 factory 产物")
    class StreamSystemKey {

        @Test
        @DisplayName("stream(vendor, model) → factory 产物被 subscribe;router bean 不被 subscribe")
        void stream_uses_factory_product() {
            when(systemKeyFactory.resolveOrCreate("openai")).thenReturn(factoryProducedModel);
            ChatResponse resp = chatResponse("gpt-4o-mini", "ok", 1, 1);
            when(factoryProducedModel.stream(any(Prompt.class)))
                    .thenReturn(reactor.core.publisher.Flux.just(resp));

            var flux = client.stream(
                    new Prompt(List.of(new UserMessage("hi"))),
                    "openai", "gpt-4o-mini");
            var list = flux.collectList().block();

            assertThat(list).hasSize(1);
            assertThat(list.get(0)).isSameAs(resp);
            verify(factoryProducedModel).stream(any(Prompt.class));
            verify(routerBeanModel, never()).stream(any(Prompt.class));
        }
    }

    // ─────────────────── helpers ───────────────────

    private static ChatResponse chatResponse(String model, String content, int prompt, int completion) {
        AssistantMessage msg = new AssistantMessage(content);
        Generation g = new Generation(msg);
        return new ChatResponse(List.of(g));
    }
}

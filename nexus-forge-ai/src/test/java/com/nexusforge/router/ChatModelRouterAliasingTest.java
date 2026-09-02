package com.nexusforge.router;

import com.nexusforge.ai.service.FallbackChainService;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainSource;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainView;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * DeepSeek 移除 starter 后(commit 1+2+3)的支撑逻辑 —
 * {@link ChatModelRouter} OpenAI 兼容 vendor aliasing:
 *
 * <p>Spring 容器只装 3 个 ChatModel bean(openai / anthropic / ollama),
 * 但 yaml 可以配任意多个 OpenAI 兼容 vendor(deepseek / dashscope / glm /
 * minimax / kimi / doubao / hunyuan / siliconflow / oneapi / openrouter
 * / ...)。ChatModelRouter 在 ctor 里把这些 vendor key 别名到对应 protocol
 * 的 ChatModel bean,业务面无感。
 *
 * <p>本测试覆盖:
 * <ul>
 *   <li>yaml 配的 OpenAI 兼容 vendor(deepseek / dashscope / glm)能被
 *       路由到 openai ChatModel bean</li>
 *   <li>yaml 配的 anthropic vendor(有自己 bean)— 不被 aliasing 覆盖,
 *       仍走 anthropicChatModel bean</li>
 *   <li>禁用 vendor 不 alias</li>
 *   <li>显式 protocol 覆盖 key 推断(例如 yaml 配的 vendor 显式设
 *       {@code protocol: anthropic} — 会 alias 到 anthropicChatModel)</li>
 *   <li>vendor 在 yaml 配了但 map 里已有同名的 — 不 alias(已有自己的 bean)</li>
 *   <li>未知协议 vendor(没有对应 ChatModel bean)— aliasing 后 vendor
 *       仍不可用,resolve 抛 LLM_MODEL_NOT_FOUND</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ChatModelRouter OpenAI 兼容 vendor aliasing")
class ChatModelRouterAliasingTest {

    /**
     * 极简 ChatModel 测试桩(同 ChatModelRouterFallbackTest,本地副本避免跨测试类依赖)。
     */
    private static ChatModel stub(String name) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage msg = new AssistantMessage("stub-" + name);
                return new ChatResponse(List.of(new Generation(msg)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new UnsupportedOperationException("stub.stream"));
            }
        };
    }

    private ChatModel openaiBean;
    private ChatModel anthropicBean;
    private ChatModel ollamaBean;
    private AiProperties props;
    @Mock private FallbackChainService fallbackChainService;

    @BeforeEach
    void setup() {
        openaiBean    = stub("openai");
        anthropicBean = stub("anthropic");
        ollamaBean    = stub("ollama");

        // 模拟 Spring AI 2.0 装配出的 3 个 ChatModel bean(按 starter 命名规范)
        // spring-ai-starter-model-openai  → openAiChatModel
        // spring-ai-starter-model-anthropic → anthropicChatModel
        // spring-ai-starter-model-ollama → ollamaChatModel
        // (DeepSeek starter 移除,不再有 deepSeekChatModel bean)
    }

    private ChatModelRouter router(Map<String, ChatModel> beanMap, Map<String, AiProperties.Provider> yamlProviders) {
        props = new AiProperties();
        props.setDefaultVendor("openai");
        if (yamlProviders != null) {
            yamlProviders.forEach((k, v) -> props.getProviders().put(k, v));
        }
        // Phase 7 — router 改读 service,默认给空链(本测试多数 case 不测降级链)
        lenient().when(fallbackChainService.findEffective()).thenReturn(
                new FallbackChainView(List.of(), FallbackChainSource.EMPTY, null));
        return new ChatModelRouter(beanMap, props, fallbackChainService);
    }

    private void stubChain(List<String> vendors) {
        lenient().when(fallbackChainService.findEffective()).thenReturn(
                new FallbackChainView(vendors, FallbackChainSource.DB, null));
    }

    private Map<String, ChatModel> mapOfAllThree() {
        Map<String, ChatModel> m = new HashMap<>();
        m.put("openAiChatModel",    openaiBean);
        m.put("anthropicChatModel", anthropicBean);
        m.put("ollamaChatModel",    ollamaBean);
        return m;
    }

    private static AiProperties.Provider enabled(String defaultModel) {
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(true);
        p.setDefaultModel(defaultModel);
        return p;
    }

    // ─────────────────── OpenAI 兼容 vendor aliasing ───────────────────

    @Nested
    @DisplayName("OpenAI 兼容 vendor → openaiChatModel bean")
    class OpenAiCompatibleAliasing {

        @Test
        @DisplayName("yaml 配的 deepseek → resolve 路由到 openai ChatModel(DeepSeek 已统一走 OpenAI starter)")
        void deepseek_aliases_to_openai() {
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of("deepseek", enabled("deepseek-v4-flash")));

            ChatModelRouter.Resolved resolved = r.resolve("deepseek", "deepseek-v4-flash");
            assertThat(resolved.vendor()).isEqualTo("deepseek");
            assertThat(resolved.model()).isSameAs(openaiBean);
            assertThat(resolved.modelName()).isEqualTo("deepseek-v4-flash");
        }

        @Test
        @DisplayName("yaml 配的 dashscope / glm / minimax → resolve 路由到 openai ChatModel")
        void domestic_vendors_alias_to_openai() {
            for (String vendor : new String[]{"dashscope", "glm", "minimax", "kimi", "doubao", "hunyuan"}) {
                ChatModelRouter r = router(
                        mapOfAllThree(),
                        Map.of(vendor, enabled("any-model")));

                ChatModelRouter.Resolved resolved = r.resolve(vendor, "any-model");
                assertThat(resolved.vendor())
                        .as("vendor=%s 应 alias 到 openai ChatModel", vendor)
                        .isEqualTo(vendor);
                assertThat(resolved.model())
                        .as("vendor=%s 应使用 openaiChatModel bean", vendor)
                        .isSameAs(openaiBean);
            }
        }

        @Test
        @DisplayName("yaml 配的硅基流动 / oneapi / openrouter 等中转站 → openai ChatModel")
        void relay_vendors_alias_to_openai() {
            for (String vendor : new String[]{"siliconflow", "oneapi", "openrouter"}) {
                ChatModelRouter r = router(
                        mapOfAllThree(),
                        Map.of(vendor, enabled("any-model")));

                ChatModelRouter.Resolved resolved = r.resolve(vendor, "any-model");
                assertThat(resolved.model()).isSameAs(openaiBean);
            }
        }

        @Test
        @DisplayName("vendor 名大小写不敏感:DEEPSEEK / DeepSeek 都 alias 到 openai")
        void aliasing_case_insensitive() {
            // aliasing 内部 vendorKey.toLowerCase 归一化,所以 yaml key 大小写都能 alias
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of("deepseek", enabled("deepseek-v4-flash")));

            // resolve 也内部 .toLowerCase,DEEPSEEK / DeepSeek / deepseek 都识别
            assertThat(r.resolve("DEEPSEEK", "x").model()).isSameAs(openaiBean);
            assertThat(r.resolve("DeepSeek", "x").model()).isSameAs(openaiBean);
            assertThat(r.resolve("deepseek", "x").model()).isSameAs(openaiBean);
        }
    }

    // ─────────────────── aliasing 不该覆盖的场景 ───────────────────

    @Nested
    @DisplayName("aliasing 不该覆盖的场景")
    class NoAliasing {

        @Test
        @DisplayName("已有自己 ChatModel bean 的 vendor(anthropic / ollama)不被 aliasing 覆盖")
        void own_bean_not_overridden_by_aliasing() {
            // map 里 anthropicChatModel bean,props.providers.anthropic 启用
            // → resolve("anthropic", ...) 走 anthropic ChatModel,不走 aliasing
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of("anthropic", enabled("claude-3-5-haiku-latest")));

            ChatModelRouter.Resolved resolved = r.resolve("anthropic", "claude-3-5-haiku-latest");
            assertThat(resolved.vendor()).isEqualTo("anthropic");
            assertThat(resolved.model()).isSameAs(anthropicBean);
        }

        @Test
        @DisplayName("禁用 vendor 不参与 aliasing")
        void disabled_vendor_skipped() {
            AiProperties.Provider p = enabled("deepseek-v4-flash");
            p.setEnabled(false);   // 显式 disable
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of("deepseek", p));

            // deepseek 不在 models map(已 disabled,aliasing 跳过),
            // 也没有自己的 ChatModel bean → resolve 抛 LLM_MODEL_NOT_FOUND
            assertThatThrownBy(() -> r.resolve("deepseek", "x"))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("未在 props.providers 配置的 vendor,即便 openai 协议也不 alias")
        void unconfigured_vendor_not_aliased() {
            // map 里只有 openai ChatModel;不配 deepseek 在 props → resolve 抛错
            ChatModelRouter r = router(mapOfAllThree(), null);

            assertThatThrownBy(() -> r.resolve("deepseek", "x"))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
    }

    // ─────────────────── 显式 protocol 覆盖 ───────────────────

    @Nested
    @DisplayName("显式 protocol 覆盖 key 推断(只在 vendor 无自己 ChatModel bean 时生效)")
    class ExplicitProtocolOverride {

        @Test
        @DisplayName("yaml 配的 deepseek 显式 protocol=anthropic → alias 到 anthropic ChatModel")
        void explicit_protocol_overrides_inferred() {
            AiProperties.Provider p = enabled("claude-via-deepseek-key");
            p.setProtocol(AiProperties.Protocol.ANTHROPIC);
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of("deepseek", p));

            // deepseek 没自己 ChatModel bean,aliasing 走 protocol 推断
            // 显式 anthropic → alias 到 anthropicChatModel bean
            ChatModelRouter.Resolved resolved = r.resolve("deepseek", "claude-via-deepseek-key");
            assertThat(resolved.vendor()).isEqualTo("deepseek");
            assertThat(resolved.model()).isSameAs(anthropicBean);
        }

        @Test
        @DisplayName("已有 ChatModel bean 的 vendor(显式 protocol 不同于自身)被忽略,走 own bean")
        void explicit_protocol_ignored_when_own_bean_exists() {
            // openai 已有 openAiChatModel bean,即使显式 protocol=ollama,
            // aliasing 跳过(openai 在 map 里)→ resolve 仍用 openai ChatModel
            // (用户想完全切协议的话,应直接禁用 openai vendor 启 ollama vendor)
            AiProperties.Provider p = enabled("gpt-4o-mini");
            p.setProtocol(AiProperties.Protocol.OLLAMA);
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of("openai", p));

            ChatModelRouter.Resolved resolved = r.resolve("openai", "gpt-4o-mini");
            assertThat(resolved.model())
                    .as("openai 已有 own bean,显式 protocol=ollama 被 aliasing 忽略")
                    .isSameAs(openaiBean);
        }
    }

    // ─────────────────── 降级链跟 aliasing 协作 ───────────────────

    @Nested
    @DisplayName("降级链跟 aliasing 协作")
    class FallbackChainWithAliasing {

        @Test
        @DisplayName("fallbackChain 含 deepseek → 跟 openai 一样可用 alias 后的 ChatModel")
        void fallback_chain_includes_aliased_vendor() {
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of(
                            "openai",   enabled("gpt-4o-mini"),
                            "deepseek", enabled("deepseek-v4-flash")));

            stubChain(List.of("deepseek"));
            ChatModelRouter.FallbackChain chain = r.resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(2);
            // 第一跳 openai(用户传入 model)
            ChatModelRouter.Resolved first = chain.iterator().next();
            assertThat(first.vendor()).isEqualTo("openai");
            assertThat(first.modelName()).isEqualTo("gpt-4o");
            // 第二跳 deepseek(用 yaml 的 default-model)→ alias 到的还是 openai ChatModel
            var iter = chain.iterator();
            iter.next();
            ChatModelRouter.Resolved second = iter.next();
            assertThat(second.vendor()).isEqualTo("deepseek");
            assertThat(second.modelName()).isEqualTo("deepseek-v4-flash");
            // 关键:alias 后的 ChatModel 跟 openai 同一个 bean
            assertThat(second.model()).isSameAs(first.model());
        }
    }

    // ─────────────────── vendorNames 跟 aliasing 协作 ───────────────────

    @Nested
    @DisplayName("vendorNames() 反映 aliasing 后的 keys")
    class VendorNamesAfterAliasing {

        @Test
        @DisplayName("router 启动后 vendorNames() 包含 aliasing 进来的 deepseek / dashscope / glm")
        void vendor_names_includes_aliased() {
            ChatModelRouter r = router(
                    mapOfAllThree(),
                    Map.of(
                            "openai",    enabled("gpt-4o-mini"),
                            "deepseek",  enabled("deepseek-v4-flash"),
                            "dashscope", enabled("qwen-max"),
                            "glm",       enabled("glm-4-plus")));

            assertThat(r.vendorNames())
                    .contains("openai", "anthropic", "ollama",
                            "deepseek", "dashscope", "glm");
        }
    }
}

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
import static org.mockito.Mockito.when;

/**
 * {@link ChatModelRouter#resolveWithFallback(String, String)} + 触发条件
 * {@link ChatModelRouter#isFallbackTriggering(LlmException, String)} 的单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@code fallbackChain} 为空 → 链只有首选</li>
 *   <li>{@code fallbackChain} 配置 N 个 vendor → 链含首选 + N 个去重/跳过无效后剩下的项</li>
 *   <li>首选 vendor 不存在/禁用 → {@link ResultCode#LLM_MODEL_NOT_FOUND} 立即抛</li>
 *   <li>{@code fallbackChain} 里出现"无效 vendor"(未注册/未启用) → 跳过,不进链</li>
 *   <li>{@code fallbackChain} 出现首选 vendor 自身 → 去重,只出现一次</li>
 *   <li>{@link ChatModelRouter#isFallbackTriggering} 白名单</li>
 *   <li>{@link ChatModelRouter#isPrimaryVendorOpen} 在 Phase 4 退化为恒 false</li>
 * </ul>
 *
 * <p>spring-ai-full-migration Phase 6 重写:用 Spring AI 的
 * {@link ChatModel} / {@link Prompt} 替代原 com.nexusforge.model.ChatModel /
 * ChatRequest / ChatResponse / ChatChunk / ChatCapabilities。
 *
 * <p>DeepSeek 移除 starter 后(commit 1+2+3):本测试 setup 不再注入
 * {@code deepseek} ChatModel,改用 ollama 作为"3rd enabled vendor"
 * (跟实际 Spring 容器只装 3 个 ChatModel bean 一致:openai / anthropic / ollama)。
 * OpenAI 兼容 vendor aliasing(commit 4)测试见
 * {@link ChatModelRouterAliasingTest}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatModelRouterFallbackTest {

    /**
     * 极简 Spring AI ChatModel 测试桩 — 只实现测试用的 2 个抽象方法。
     * Spring AI 2.0 的 ChatModel 接口是低层 call(prompt)/stream(prompt),
     * 其余 5 个 default 方法(call(String)/call(Message...)/getOptions() 等)
     * 接口有默认实现,不用 override。
     */
    private static ChatModel stub(String name) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String m = prompt.getOptions() == null ? "?" : prompt.getOptions().getModel();
                AssistantMessage msg = new AssistantMessage("stub-" + name + "(" + m + ")");
                return new ChatResponse(List.of(new Generation(msg)));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new UnsupportedOperationException("stub.stream"));
            }
        };
    }

    private ChatModel openai;
    private ChatModel anthropic;
    private ChatModel ollama;
    private AiProperties props;
    @Mock private FallbackChainService fallbackChainService;

    @BeforeEach
    void setup() {
        openai     = stub("openai");
        anthropic  = stub("anthropic");
        ollama     = stub("ollama");

        props = new AiProperties();
        props.setDefaultVendor("openai");
        props.setDefaultModel("gpt-4o-mini");

        // 注册三个有效 vendor + 一个显式禁用的 "disabled-vendor"
        // (用 enabled=false 的 4th vendor 测试"禁用 vendor 跳过"逻辑,
        //  而不是 ollama 自身 — 跟 Spring 容器只装 3 个 ChatModel bean 保持一致)
        AiProperties.Provider pOpenAi = new AiProperties.Provider();
        pOpenAi.setEnabled(true);
        pOpenAi.setDefaultModel("gpt-4o-mini");
        props.getProviders().put("openai", pOpenAi);

        AiProperties.Provider pAnthropic = new AiProperties.Provider();
        pAnthropic.setEnabled(true);
        pAnthropic.setDefaultModel("claude-3-5-haiku");
        props.getProviders().put("anthropic", pAnthropic);

        AiProperties.Provider pOllama = new AiProperties.Provider();
        pOllama.setEnabled(true);
        pOllama.setDefaultModel("llama3");
        props.getProviders().put("ollama", pOllama);

        AiProperties.Provider pDisabled = new AiProperties.Provider();
        pDisabled.setEnabled(false);          // 禁用 — 用来测试 "skips disabled vendor"
        pDisabled.setDefaultModel("disabled-model");
        props.getProviders().put("disabled-vendor", pDisabled);
    }

    private ChatModelRouter router(Map<String, ChatModel> map) {
        return new ChatModelRouter(map, props, fallbackChainService);
    }

    /**
     * Phase 7 — 把期望的降级链灌进 {@code FallbackChainService.findEffective()}
     * mock,这样 router 走的是 service 而不是 yaml。原先 {@code stubChain(X)}
     * 的调用语义("router 读这个 chain")被 {@code stubChain(X)} 替代,router 的
     * 链构建逻辑(去重/跳过无效 vendor/取 defaultModel)测试意图完全不变。
     */
    private void stubChain(List<String> vendors) {
        lenient().when(fallbackChainService.findEffective()).thenReturn(
                new FallbackChainView(vendors, FallbackChainSource.DB, null));
    }

    private Map<String, ChatModel> mapAll() {
        Map<String, ChatModel> m = new HashMap<>();
        m.put("openai", openai);
        m.put("anthropic", anthropic);
        m.put("ollama", ollama);
        return m;
    }

    /**
     * router 现在接 (vendor, model) 显式两个参数,不再要求 caller 把 vendor 拼到
     * model 字符串里。原 {@code req("openai:gpt-4o")} 现在直接 {@code ("openai", "gpt-4o")},
     * 测试代码也跟着用新 API。
     */

    @Nested
    @DisplayName("resolveWithFallback 链构建")
    class ChainBuilding {

        @Test
        @DisplayName("fallbackChain 为空 → 链只有首选")
        void empty_chain_yields_single_hop() {
            stubChain(List.of());
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(1);
            assertThat(chain.isSingleHop()).isTrue();
            assertThat(chain.primaryVendor()).isEqualTo("openai");
            ChatModelRouter.Resolved head = chain.iterator().next();
            assertThat(head.vendor()).isEqualTo("openai");
            assertThat(head.modelName()).isEqualTo("gpt-4o");
        }

        @Test
        @DisplayName("fallbackChain 非空 → 链含首选 + 后续 vendor,每个用各自 defaultModel")
        void chain_appends_fallback_hops_with_default_models() {
            stubChain(List.of("anthropic", "ollama"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(3);
            assertThat(chain.primaryVendor()).isEqualTo("openai");

            var iter = chain.iterator();
            ChatModelRouter.Resolved first = iter.next();
            assertThat(first.vendor()).isEqualTo("openai");
            assertThat(first.modelName()).isEqualTo("gpt-4o");
            ChatModelRouter.Resolved second = iter.next();
            assertThat(second.vendor()).isEqualTo("anthropic");
            assertThat(second.modelName()).isEqualTo("claude-3-5-haiku");   // 用 Provider.defaultModel
            ChatModelRouter.Resolved third = iter.next();
            assertThat(third.vendor()).isEqualTo("ollama");
            assertThat(third.modelName()).isEqualTo("llama3");
        }

        @Test
        @DisplayName("fallbackChain 出现首选 vendor → 去重,只出现一次")
        void chain_dedups_primary_vendor() {
            stubChain(List.of("openai", "anthropic"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(2);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic");
        }

        @Test
        @DisplayName("fallbackChain 出现重复 vendor → 去重")
        void chain_dedups_within_fallback() {
            stubChain(List.of("anthropic", "anthropic", "ollama", "ollama"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(3);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic", "ollama");
        }

        @Test
        @DisplayName("fallbackChain 含未注册 vendor → 跳过")
        void chain_skips_unknown_vendor() {
            stubChain(List.of("anthropic", "ghost-vendor", "ollama"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(3);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic", "ollama");
        }

        @Test
        @DisplayName("fallbackChain 含禁用 vendor → 跳过")
        void chain_skips_disabled_vendor() {
            stubChain(List.of("anthropic", "disabled-vendor", "ollama"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(3);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic", "ollama");
        }

        @Test
        @DisplayName("fallbackChain 含 null/空白项 → 跳过")
        void chain_skips_blank_entries() {
            stubChain(java.util.Arrays.asList("anthropic", null, "  ", "ollama"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("fallbackChain 全是无效 vendor → 链只有首选")
        void chain_all_invalid_yields_single_hop() {
            stubChain(List.of("ghost-1", "ghost-2"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.size()).isEqualTo(1);
            assertThat(chain.isSingleHop()).isTrue();
        }

        @Test
        @DisplayName("首选 vendor 走默认:无冒号 model 串 → 默认 vendor + 传入 model")
        void primary_resolved_uses_default_vendor_when_no_colon() {
            stubChain(List.of());
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(null, "claude-3-5-haiku");
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai");
            assertThat(chain.hops().get(0).modelName()).isEqualTo("claude-3-5-haiku");
        }
    }

    @Nested
    @DisplayName("resolveWithFallback 错误分支")
    class ErrorBranches {

        @Test
        @DisplayName("首选 vendor 未注册 → 立即抛 LLM_MODEL_NOT_FOUND,不展开链")
        void primary_unknown_throws_immediately() {
            // map 中不放 anthropic 但 props.providers 中存在
            Map<String, ChatModel> onlyOpenAi = new HashMap<>();
            onlyOpenAi.put("openai", openai);
            stubChain(List.of("anthropic", "ollama"));
            assertThatThrownBy(() -> router(onlyOpenAi).resolveWithFallback("anthropic", "claude"))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("首选 vendor 显式禁用 → 立即抛 LLM_MODEL_NOT_FOUND,不展开链")
        void primary_disabled_throws_immediately() {
            // 用户显式请求被禁用的 disabled-vendor
            stubChain(List.of("openai", "ollama"));
            assertThatThrownBy(() -> router(mapAll()).resolveWithFallback("disabled-vendor", "x"))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
        @Test
        @DisplayName("首选 vendor 未在 props.providers 配置 → 立即抛 LLM_MODEL_NOT_FOUND")
        void primary_unconfigured_throws_immediately() {
            // map 中保留 ollama,但从 props.providers 中移除 → 走 resolveInternal
            // 第二道校验(p==null 分支)
            props.getProviders().remove("ollama");
            stubChain(List.of("openai"));
            assertThatThrownBy(() -> router(mapAll()).resolveWithFallback("ollama", "llama3"))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
    }

    @Nested
    @DisplayName("isFallbackTriggering 触发条件")
    class TriggerCondition {

        @Test
        @DisplayName("LLM_PROVIDER_ERROR → true")
        void provider_error_triggers() {
            LlmException ex = new LlmException(ResultCode.LLM_PROVIDER_ERROR, "boom");
            assertThat(ChatModelRouter.isFallbackTriggering(ex, "openai")).isTrue();
        }

        @Test
        @DisplayName("LLM_UPSTREAM_TIMEOUT → true")
        void timeout_triggers() {
            LlmException ex = new LlmException(ResultCode.LLM_UPSTREAM_TIMEOUT, "slow");
            assertThat(ChatModelRouter.isFallbackTriggering(ex, "openai")).isTrue();
        }

        @Test
        @DisplayName("LLM_RATE_LIMITED → false(改 vendor 救不了)")
        void rate_limited_does_not_trigger() {
            LlmException ex = new LlmException(ResultCode.LLM_RATE_LIMITED, "429");
            assertThat(ChatModelRouter.isFallbackTriggering(ex, "openai")).isFalse();
        }

        @Test
        @DisplayName("LLM_QUOTA_EXCEEDED → false")
        void quota_exceeded_does_not_trigger() {
            LlmException ex = new LlmException(ResultCode.LLM_QUOTA_EXCEEDED, "out");
            assertThat(ChatModelRouter.isFallbackTriggering(ex, "openai")).isFalse();
        }

        @Test
        @DisplayName("LLM_INVALID_REQUEST → false")
        void invalid_request_does_not_trigger() {
            LlmException ex = new LlmException(ResultCode.LLM_INVALID_REQUEST, "bad");
            assertThat(ChatModelRouter.isFallbackTriggering(ex, "openai")).isFalse();
        }

        @Test
        @DisplayName("LLM_CIRCUIT_OPEN → false(熔断由降级链机制本身处理,不重复降级)")
        void circuit_open_does_not_trigger() {
            LlmException ex = new LlmException(ResultCode.LLM_CIRCUIT_OPEN, "open");
            assertThat(ChatModelRouter.isFallbackTriggering(ex, "openai")).isFalse();
        }

        @Test
        @DisplayName("LLM_CONFIG_MISSING → false")
        void config_missing_does_not_trigger() {
            LlmException ex = new LlmException(ResultCode.LLM_CONFIG_MISSING, "no key");
            assertThat(ChatModelRouter.isFallbackTriggering(ex, "openai")).isFalse();
        }

        @Test
        @DisplayName("null ex → false")
        void null_ex_does_not_trigger() {
            assertThat(ChatModelRouter.isFallbackTriggering(null, "openai")).isFalse();
        }
    }

    @Nested
    @DisplayName("isPrimaryVendorOpen 行为")
    class PrimaryVendorOpen {

        @Test
        @DisplayName("Phase 4 简化:不再有 ChatModelHttpSupport,恒返回 false")
        void always_false() {
            ChatModelRouter.Resolved r = new ChatModelRouter.Resolved(openai, "openai", "gpt-4o");
            assertThat(router(mapAll()).isPrimaryVendorOpen(r)).isFalse();
        }

        @Test
        @DisplayName("null Resolved → false")
        void null_resolved_returns_false() {
            assertThat(router(mapAll()).isPrimaryVendorOpen(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("FallbackChain record 行为")
    class FallbackChainRecord {

        @Test
        @DisplayName("hops 列表不可变 → 修改抛 UnsupportedOperationException")
        void hops_list_immutable() {
            stubChain(List.of("anthropic"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThatThrownBy(() -> chain.hops().add(
                    new ChatModelRouter.Resolved(openai, "ghost", "x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("isSingleHop:链只有 1 项 → true")
        void single_hop_flag() {
            stubChain(List.of());
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback("openai", "gpt-4o");
            assertThat(chain.isSingleHop()).isTrue();
        }
    }
}

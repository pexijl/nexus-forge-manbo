package com.nexusforge.router;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import com.nexusforge.provider.support.ChatModelHttpSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChatModelRouter#resolveWithFallback(ChatRequest)} + 触发条件
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
 *   <li>{@link ChatModelRouter#isPrimaryVendorOpen} 在 {@code http=null} 时恒返回 false</li>
 * </ul>
 *
 * <p>{@link ChatModelHttpSupport} 的 {@code isVendorOpen} 通过 stub {@link ChatModelHttpSupport}
 * 没法直接 new(其构造由 Spring 注入 {@code props}),故 {@code isPrimaryVendorOpen} 测试只覆盖
 * {@code http=null} 分支;{@code http!=null} 的熔断集成测试在 {@code ConversationIT} / 未来
 * {@code FallbackIT} 中覆盖。
 */
class ChatModelRouterFallbackTest {

    private static ChatModel stub(String name) {
        return new ChatModel() {
            @Override public String name() { return name; }
            @Override public ChatCapabilities capabilities() {
                return ChatCapabilities.builder().stream(true).tools(false).build();
            }
            @Override public ChatResponse call(ChatRequest request) {
                return ChatResponse.builder().model(request.getModel()).content("stub-" + name).build();
            }
            @Override public Flux<ChatChunk> stream(ChatRequest request) {
                return Flux.error(new UnsupportedOperationException("stub"));
            }
        };
    }

    private ChatModel openai;
    private ChatModel anthropic;
    private ChatModel qwen;
    private ChatModel deepseek;
    private ChatModel ollama;
    private AiProperties props;

    @BeforeEach
    void setup() {
        openai     = stub("openai");
        anthropic  = stub("anthropic");
        qwen       = stub("qwen");
        deepseek   = stub("deepseek");
        ollama     = stub("ollama");

        props = new AiProperties();
        props.setDefaultVendor("openai");
        props.setDefaultModel("gpt-4o-mini");

        // 注册三个有效 vendor + 一个显式禁用的 ollama
        AiProperties.Provider pOpenAi = new AiProperties.Provider();
        pOpenAi.setEnabled(true);
        pOpenAi.setDefaultModel("gpt-4o-mini");
        props.getProviders().put("openai", pOpenAi);

        AiProperties.Provider pAnthropic = new AiProperties.Provider();
        pAnthropic.setEnabled(true);
        pAnthropic.setDefaultModel("claude-3-5-haiku");
        props.getProviders().put("anthropic", pAnthropic);

        AiProperties.Provider pQwen = new AiProperties.Provider();
        pQwen.setEnabled(true);
        pQwen.setDefaultModel("qwen-turbo");
        props.getProviders().put("qwen", pQwen);

        AiProperties.Provider pDeepSeek = new AiProperties.Provider();
        pDeepSeek.setEnabled(true);
        pDeepSeek.setDefaultModel("deepseek-chat");
        props.getProviders().put("deepseek", pDeepSeek);

        AiProperties.Provider pOllama = new AiProperties.Provider();
        pOllama.setEnabled(false);            // 禁用
        pOllama.setDefaultModel("llama3");
        props.getProviders().put("ollama", pOllama);
    }

    private ChatModelRouter router(Map<String, ChatModel> map) {
        return new ChatModelRouter(map, props, null);   // http=null
    }

    private Map<String, ChatModel> mapAll() {
        Map<String, ChatModel> m = new HashMap<>();
        m.put("openai", openai);
        m.put("anthropic", anthropic);
        m.put("qwen", qwen);
        m.put("deepseek", deepseek);
        m.put("ollama", ollama);
        return m;
    }

    private ChatRequest req(String model) {
        return ChatRequest.builder().model(model).messages(List.of()).build();
    }

    @Nested
    @DisplayName("resolveWithFallback 链构建")
    class ChainBuilding {

        @Test
        @DisplayName("fallbackChain 为空 → 链只有首选")
        void empty_chain_yields_single_hop() {
            props.setFallbackChain(List.of());
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
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
            props.setFallbackChain(List.of("anthropic", "qwen"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
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
            assertThat(third.vendor()).isEqualTo("qwen");
            assertThat(third.modelName()).isEqualTo("qwen-turbo");
        }

        @Test
        @DisplayName("fallbackChain 出现首选 vendor → 去重,只出现一次")
        void chain_dedups_primary_vendor() {
            props.setFallbackChain(List.of("openai", "anthropic"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThat(chain.size()).isEqualTo(2);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic");
        }

        @Test
        @DisplayName("fallbackChain 出现重复 vendor → 去重")
        void chain_dedups_within_fallback() {
            props.setFallbackChain(List.of("anthropic", "anthropic", "qwen", "qwen"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThat(chain.size()).isEqualTo(3);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic", "qwen");
        }

        @Test
        @DisplayName("fallbackChain 含未注册 vendor → 跳过")
        void chain_skips_unknown_vendor() {
            props.setFallbackChain(List.of("anthropic", "ghost-vendor", "qwen"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThat(chain.size()).isEqualTo(3);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic", "qwen");
        }

        @Test
        @DisplayName("fallbackChain 含禁用 vendor → 跳过")
        void chain_skips_disabled_vendor() {
            props.setFallbackChain(List.of("anthropic", "ollama", "qwen"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThat(chain.size()).isEqualTo(3);
            assertThat(chain.hops())
                    .extracting(ChatModelRouter.Resolved::vendor)
                    .containsExactly("openai", "anthropic", "qwen");
        }

        @Test
        @DisplayName("fallbackChain 含 null/空白项 → 跳过")
        void chain_skips_blank_entries() {
            props.setFallbackChain(java.util.Arrays.asList("anthropic", null, "  ", "qwen"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThat(chain.size()).isEqualTo(3);
        }

        @Test
        @DisplayName("fallbackChain 全是无效 vendor → 链只有首选")
        void chain_all_invalid_yields_single_hop() {
            props.setFallbackChain(List.of("ghost-1", "ghost-2"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThat(chain.size()).isEqualTo(1);
            assertThat(chain.isSingleHop()).isTrue();
        }

        @Test
        @DisplayName("首选 vendor 走默认:无冒号 model 串 → 默认 vendor + 传入 model")
        void primary_resolved_uses_default_vendor_when_no_colon() {
            props.setFallbackChain(List.of());
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("claude-3-5-haiku"));
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
            props.setFallbackChain(List.of("anthropic", "qwen"));
            assertThatThrownBy(() -> router(onlyOpenAi).resolveWithFallback(req("anthropic:claude")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("首选 vendor 显式禁用 → 立即抛 LLM_MODEL_NOT_FOUND,不展开链")
        void primary_disabled_throws_immediately() {
            // 用户显式请求被禁用的 ollama
            props.setFallbackChain(List.of("openai", "qwen"));
            assertThatThrownBy(() -> router(mapAll()).resolveWithFallback(req("ollama:llama3")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
        @Test
        @DisplayName("首选 vendor 未在 props.providers 配置 → 立即抛 LLM_MODEL_NOT_FOUND")
        void primary_unconfigured_throws_immediately() {
            // map 中保留 deepseek,但从 props.providers 中移除 → 走 resolveInternal
            // 第二道校验(p==null 分支)
            props.getProviders().remove("deepseek");
            props.setFallbackChain(List.of("openai"));
            assertThatThrownBy(() -> router(mapAll()).resolveWithFallback(req("deepseek:deepseek-chat")))
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
        @DisplayName("http=null → 恒返回 false")
        void http_null_returns_false() {
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
            props.setFallbackChain(List.of("anthropic"));
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThatThrownBy(() -> chain.hops().add(
                    new ChatModelRouter.Resolved(openai, "ghost", "x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("isSingleHop:链只有 1 项 → true")
        void single_hop_flag() {
            props.setFallbackChain(List.of());
            ChatModelRouter.FallbackChain chain = router(mapAll()).resolveWithFallback(req("openai:gpt-4o"));
            assertThat(chain.isSingleHop()).isTrue();
        }
    }
}
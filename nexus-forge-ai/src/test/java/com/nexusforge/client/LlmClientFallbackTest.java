package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import com.nexusforge.provider.support.ChatModelHttpSupport;
import com.nexusforge.router.ChatModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LlmClient} 与 {@link ChatModelRouter#resolveWithFallback(ChatRequest)} 端到端集成测试。
 *
 * <p>锁住契约:
 * <ul>
 *   <li>首选 vendor 成功 → 只调用首选,后续 hop 不触碰</li>
 *   <li>首选抛 {@link ResultCode#LLM_PROVIDER_ERROR} → 自动跳下一跳,返回 fallback 响应</li>
 *   <li>首选抛 {@link ResultCode#LLM_UPSTREAM_TIMEOUT} → 自动跳下一跳</li>
 *   <li>首选抛 {@link ResultCode#LLM_RATE_LIMITED} / {@code LLM_QUOTA_EXCEEDED} /
 *       {@code LLM_INVALID_REQUEST} / {@code LLM_CIRCUIT_OPEN} → 不降级,立即抛</li>
 *   <li>链全部失败(都是 triggering code)→ 抛 {@link ResultCode#LLM_ALL_VENDORS_FAILED},
 *       cause = 最后一跳的 {@link LlmException}</li>
 *   <li>首选熔断(via {@link ChatModelHttpSupport#isVendorOpen(String)} 报 true) →
 *       跳过首选,不浪费 HTTP 调用,直接进入下一跳</li>
 *   <li>链全部熔断 → {@code call} 抛 {@link ResultCode#LLM_ALL_VENDORS_FAILED};
 *       {@code stream} 返回 {@code Flux.error(LLM_ALL_VENDORS_FAILED)}</li>
 *   <li>{@code fallbackChain} 为空 → 首选失败抛 {@code LLM_ALL_VENDORS_FAILED}(单跳耗尽)</li>
 *   <li>router 解析失败(用户请求不存在的 vendor)→ 同步抛 {@code LLM_MODEL_NOT_FOUND},
 *       不调用任何 ChatModel</li>
 * </ul>
 */
class LlmClientFallbackTest {

    /**
     * 可编程 ChatModel stub:每次调用消费 {@code script} 队列里的下一个"指令"。
     * 指令可以是 {@link ChatResponse}(call) / {@link ChatChunk}(stream) / {@link RuntimeException}。
     */
    static final class ScriptedChatModel implements ChatModel {
        final String vendorName;
        final Deque<Object> script = new ArrayDeque<>();
        ChatRequest lastSeenRequest;
        int callCount = 0;
        int streamCount = 0;

        ScriptedChatModel(String vendorName) { this.vendorName = vendorName; }

        ScriptedChatModel enqueue(Object step) { script.add(step); return this; }

        @Override public String name() { return vendorName; }
        @Override public ChatCapabilities capabilities() {
            return ChatCapabilities.builder().stream(true).tools(false).build();
        }
        @Override public ChatResponse call(ChatRequest request) {
            this.lastSeenRequest = request;
            this.callCount++;
            Object step = script.poll();
            if (step == null) throw new IllegalStateException("script empty for call #" + callCount);
            if (step instanceof ChatResponse r) return r;
            if (step instanceof RuntimeException e) throw e;
            throw new IllegalStateException("unsupported call script step: " + step);
        }
        @Override public Flux<ChatChunk> stream(ChatRequest request) {
            this.lastSeenRequest = request;
            this.streamCount++;
            Object step = script.poll();
            if (step == null) throw new IllegalStateException("script empty for stream #" + streamCount);
            if (step instanceof ChatChunk c) return Flux.just(c);
            if (step instanceof RuntimeException e) return Flux.error(e);
            throw new IllegalStateException("unsupported stream script step: " + step);
        }
    }

    private ScriptedChatModel openai;
    private ScriptedChatModel ollama;
    private ScriptedChatModel anthropic;
    private AiProperties props;
    private ChatModelHttpSupport http;
    private ChatModelRouter router;
    private LlmClient client;

    @BeforeEach
    void setup() {
        openai    = new ScriptedChatModel("openai");
        ollama    = new ScriptedChatModel("ollama");
        anthropic = new ScriptedChatModel("anthropic");

        props = new AiProperties();
        props.setDefaultVendor("openai");
        props.setDefaultModel("gpt-4o-mini");

        AiProperties.Provider pOpenAi = new AiProperties.Provider();
        pOpenAi.setEnabled(true);
        pOpenAi.setDefaultModel("gpt-4o-mini");
        props.getProviders().put("openai", pOpenAi);

        AiProperties.Provider pOllama = new AiProperties.Provider();
        pOllama.setEnabled(true);
        pOllama.setDefaultModel("llama3");
        props.getProviders().put("ollama", pOllama);

        AiProperties.Provider pAnthropic = new AiProperties.Provider();
        pAnthropic.setEnabled(true);
        pAnthropic.setDefaultModel("claude-3-5-haiku");
        props.getProviders().put("anthropic", pAnthropic);

        Map<String, ChatModel> models = new HashMap<>();
        models.put("openai", openai);
        models.put("ollama", ollama);
        models.put("anthropic", anthropic);

        http = new ChatModelHttpSupport(props);
        router = new ChatModelRouter(models, props, http);
        client = new LlmClient(router, props);
    }

    private ChatRequest req(String model) {
        return ChatRequest.builder().model(model).messages(List.of()).build();
    }

    private ChatResponse cannedResp(String vendor, String content) {
        return ChatResponse.builder().id(vendor + "-r").model("m").content(content).build();
    }

    private ChatChunk cannedChunk(String vendor, String content) {
        return ChatChunk.builder().id(vendor + "-c").model("m").deltaContent(content).build();
    }

    @Nested
    @DisplayName("call() 降级行为")
    class CallFallback {

        @Test
        @DisplayName("首选成功 → 只调用首选,不触碰 fallback")
        void primary_success_skips_fallback() {
            props.setFallbackChain(List.of("ollama", "anthropic"));
            openai.enqueue(cannedResp("openai", "primary"));

            ChatResponse resp = client.call(req("openai:gpt-4o"));

            assertThat(resp.getContent()).isEqualTo("primary");
            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isEqualTo(0);
            assertThat(anthropic.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选抛 LLM_PROVIDER_ERROR → 跳下一跳,返回 fallback 响应")
        void primary_provider_error_falls_back_to_ollama() {
            props.setFallbackChain(List.of("ollama", "anthropic"));
            openai.enqueue(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "openai down"));
            ollama.enqueue(cannedResp("ollama", "fallback-ollama"));

            ChatResponse resp = client.call(req("openai:gpt-4o"));

            assertThat(resp.getContent()).isEqualTo("fallback-ollama");
            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isEqualTo(1);
            assertThat(anthropic.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选抛 LLM_UPSTREAM_TIMEOUT → 跳下一跳")
        void primary_timeout_falls_back() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new LlmException(ResultCode.LLM_UPSTREAM_TIMEOUT, "slow"));
            ollama.enqueue(cannedResp("ollama", "fb"));

            ChatResponse resp = client.call(req("openai:gpt-4o"));

            assertThat(resp.getContent()).isEqualTo("fb");
            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("首选抛 LLM_RATE_LIMITED → 不降级,直接抛(改 vendor 救不了)")
        void primary_rate_limited_does_not_fall_back() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new LlmException(ResultCode.LLM_RATE_LIMITED, "429"));

            assertThatThrownBy(() -> client.call(req("openai:gpt-4o")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_RATE_LIMITED.getCode());

            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选抛 LLM_QUOTA_EXCEEDED → 不降级")
        void primary_quota_does_not_fall_back() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new LlmException(ResultCode.LLM_QUOTA_EXCEEDED, "out"));

            assertThatThrownBy(() -> client.call(req("openai:gpt-4o")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_QUOTA_EXCEEDED.getCode());

            assertThat(ollama.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选抛 LLM_INVALID_REQUEST → 不降级")
        void primary_invalid_request_does_not_fall_back() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new LlmException(ResultCode.LLM_INVALID_REQUEST, "bad"));

            assertThatThrownBy(() -> client.call(req("openai:gpt-4o")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());

            assertThat(ollama.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选抛 LLM_CIRCUIT_OPEN → 不降级(熔断由降级链自身处理)")
        void primary_circuit_open_does_not_fall_back() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new LlmException(ResultCode.LLM_CIRCUIT_OPEN, "open"));

            assertThatThrownBy(() -> client.call(req("openai:gpt-4o")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_CIRCUIT_OPEN.getCode());

            assertThat(ollama.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选非 LlmException 异常 → 不降级,透传(非业务错误)")
        void primary_illegal_state_propagates_without_fallback() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new IllegalStateException("oops"));

            assertThatThrownBy(() -> client.call(req("openai:gpt-4o")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("oops");

            assertThat(ollama.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选 + 第二跳都失败 → 跳到第三跳,返回第三跳响应")
        void fallback_to_third_hop() {
            props.setFallbackChain(List.of("ollama", "anthropic"));
            openai.enqueue(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "openai down"));
            ollama.enqueue(new LlmException(ResultCode.LLM_UPSTREAM_TIMEOUT, "ollama slow"));
            anthropic.enqueue(cannedResp("anthropic", "third-hop"));

            ChatResponse resp = client.call(req("openai:gpt-4o"));

            assertThat(resp.getContent()).isEqualTo("third-hop");
            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isEqualTo(1);
            assertThat(anthropic.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("整链失败 → 抛 LLM_ALL_VENDORS_FAILED,cause = 最后一跳异常")
        void all_hops_failed_throws_with_cause() {
            props.setFallbackChain(List.of("ollama", "anthropic"));
            openai.enqueue(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "openai-err"));
            ollama.enqueue(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "ollama-err"));
            anthropic.enqueue(new LlmException(ResultCode.LLM_UPSTREAM_TIMEOUT, "anthropic-err"));

            LlmException thrown = null;
            try {
                client.call(req("openai:gpt-4o"));
            } catch (LlmException ex) {
                thrown = ex;
            }
            assertThat(thrown).isNotNull();
            assertThat(thrown.getCode()).isEqualTo(ResultCode.LLM_ALL_VENDORS_FAILED.getCode());
            assertThat(thrown.getCause()).isInstanceOf(LlmException.class);
            assertThat(((LlmException) thrown.getCause()).getCode())
                    .isEqualTo(ResultCode.LLM_UPSTREAM_TIMEOUT.getCode());

            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isEqualTo(1);
            assertThat(anthropic.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("fallbackChain 为空 + 首选失败 → 抛 LLM_ALL_VENDORS_FAILED(单跳耗尽)")
        void empty_chain_throws_all_vendors_failed() {
            // 单跳 + triggering code → 链耗尽抛 LLM_ALL_VENDORS_FAILED(非触发型错误透传见其它用例)
            props.setFallbackChain(List.of());
            openai.enqueue(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "openai down"));

            assertThatThrownBy(() -> client.call(req("openai:gpt-4o")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_ALL_VENDORS_FAILED.getCode());

            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isEqualTo(0);
            assertThat(anthropic.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("fallbackChain 为空 + 首选抛 LLM_RATE_LIMITED(非触发型)→ 直接抛原异常,不包 ALL_VENDORS_FAILED")
        void empty_chain_passes_through_non_triggering_error() {
            props.setFallbackChain(List.of());
            openai.enqueue(new LlmException(ResultCode.LLM_RATE_LIMITED, "429"));

            assertThatThrownBy(() -> client.call(req("openai:gpt-4o")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_RATE_LIMITED.getCode());
        }

        @Test
        @DisplayName("首选 vendor 解析失败 → 同步抛 LLM_MODEL_NOT_FOUND,不调用任何 ChatModel")
        void primary_resolve_failure_throws_without_invoking_any_model() {
            props.setFallbackChain(List.of("ollama"));
            assertThatThrownBy(() -> client.call(req("ghost:foo")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());

            assertThat(openai.callCount).isEqualTo(0);
            assertThat(ollama.callCount).isEqualTo(0);
        }

        @Test
        @DisplayName("fallback 节点下发的 ChatRequest.model 是该 vendor 的 defaultModel")
        void fallback_hop_uses_vendor_default_model() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "openai down"));
            ollama.enqueue(cannedResp("ollama", "fb"));

            client.call(req("openai:gpt-4o"));

            assertThat(ollama.lastSeenRequest).isNotNull();
            assertThat(ollama.lastSeenRequest.getModel()).isEqualTo("llama3");
        }

        @Test
        @DisplayName("首选成功路径下发的 ChatRequest.model 保留用户传入的 model 名")
        void primary_path_passes_user_model() {
            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(cannedResp("openai", "ok"));

            client.call(req("openai:gpt-4o-custom"));

            assertThat(openai.lastSeenRequest).isNotNull();
            assertThat(openai.lastSeenRequest.getModel()).isEqualTo("gpt-4o-custom");
        }
    }

    @Nested
    @DisplayName("stream() 降级行为")
    class StreamFallback {

        @Test
        @DisplayName("fallbackChain 为空 → 首选 stream 成功,推 chunk 出去")
        void empty_chain_streams_from_primary() {
            props.setFallbackChain(List.of());
            openai.enqueue(cannedChunk("openai", "hi"));

            StepVerifier.create(client.stream(req("openai:gpt-4o")))
                    .expectNextMatches(c -> "hi".equals(c.getDeltaContent()))
                    .expectComplete()
                    .verify();

            assertThat(openai.streamCount).isEqualTo(1);
        }

        @Test
        @DisplayName("首选 stream 抛 LLM_UPSTREAM_TIMEOUT → Flux.error 透传(stream 阶段不切换 vendor)")
        void primary_stream_error_propagates() {
            props.setFallbackChain(List.of("ollama"));
            ollama.enqueue(cannedChunk("ollama", "unused"));
            openai.enqueue(new LlmException(ResultCode.LLM_UPSTREAM_TIMEOUT, "slow"));

            StepVerifier.create(client.stream(req("openai:gpt-4o")))
                    .expectErrorMatches(t ->
                            t instanceof LlmException
                                    && ((LlmException) t).getCode().equals(ResultCode.LLM_UPSTREAM_TIMEOUT.getCode()))
                    .verify();

            // stream 阶段一旦订阅,不再切换 vendor —— 即使 fallbackChain 有 ollama
            assertThat(ollama.streamCount).isEqualTo(0);
        }

        @Test
        @DisplayName("首选 vendor 解析失败 → 同步抛 LLM_MODEL_NOT_FOUND,不进 Flux")
        void primary_resolve_failure_throws_synchronously_in_stream() {
            // router.resolveWithFallback 在 stream() 第一行同步执行,失败立即抛
            // (与 Reactor 流语义相反:错误不进 Flux.error,而是同步传播)
            assertThatThrownBy(() -> client.stream(req("ghost:foo")).blockLast())
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode());
        }
    }

    @Nested
    @DisplayName("http=null 兼容路径")
    class HttpNullCompat {

        @Test
        @DisplayName("http=null → call 仍按降级链走,只是不查熔断")
        void call_works_with_null_http() {
            ChatModelRouter r = new ChatModelRouter(
                    Map.of("openai", openai, "ollama", ollama, "anthropic", anthropic),
                    props, null);
            LlmClient c = new LlmClient(r, props);

            props.setFallbackChain(List.of("ollama"));
            openai.enqueue(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "down"));
            ollama.enqueue(cannedResp("ollama", "fb"));

            ChatResponse resp = c.call(req("openai:gpt-4o"));

            assertThat(resp.getContent()).isEqualTo("fb");
            assertThat(ollama.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("http=null → stream 走首选(无熔断查询)")
        void stream_works_with_null_http() {
            ChatModelRouter r = new ChatModelRouter(
                    Map.of("openai", openai, "ollama", ollama),
                    props, null);
            LlmClient c = new LlmClient(r, props);

            openai.enqueue(cannedChunk("openai", "ok"));
            StepVerifier.create(c.stream(req("openai:gpt-4o")))
                    .expectNextMatches(c2 -> "ok".equals(c2.getDeltaContent()))
                    .expectComplete()
                    .verify();
        }
    }
}
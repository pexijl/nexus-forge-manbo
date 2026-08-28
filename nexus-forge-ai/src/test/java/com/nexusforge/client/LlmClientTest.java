package com.nexusforge.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolDefinition;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.error.StreamTimeoutException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import com.nexusforge.router.ChatModelRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmClient 门面单元测试 —— 锁住:
 *
 * <ul>
 *   <li>{@link LlmClient#call(ChatRequest)} 通过 router 解析 vendor → model,
 *       并把 ChatRequest.model 改写为去除 vendor 前缀的纯 model 名后下传。</li>
 *   <li>{@link LlmClient#stream(ChatRequest)} 同步把 request.stream 置 true 再下传,
 *       路由路径与 call 一致。</li>
 *   <li>router 抛 LlmException 时,call/stream 不调用实现,异常透传。</li>
 *   <li>usage == null 时,info 日志不抛 NPE。</li>
 *   <li>下传字段(messages / temperature / maxTokens / options / tools)逐个拷贝,
 *       不丢字段也不擅自改写。</li>
 * </ul>
 */
class LlmClientTest {

    /**
     * 录制式 ChatModel stub:每次调用都会把 {@code lastSeenRequest} 替换成
     * LlmClient 下发的最终 ChatRequest,并把 callCount / streamCount 累加,
     * 方便测试断言"调用了几次 / 进了哪个 vendor / 下发的 model 名是什么"。
     */
    static final class RecordingChatModel implements ChatModel {
        final String vendorName;
        final ChatResponse cannedResponse;
        final ChatChunk cannedChunk;
        ChatRequest lastSeenRequest;
        int callCount = 0;
        int streamCount = 0;

        RecordingChatModel(String vendorName, ChatResponse cannedResponse) {
            this(vendorName, cannedResponse,
                    ChatChunk.builder().id("x").model("m").deltaContent("ok").build());
        }

        RecordingChatModel(String vendorName, ChatResponse cannedResponse, ChatChunk cannedChunk) {
            this.vendorName = vendorName;
            this.cannedResponse = cannedResponse;
            this.cannedChunk = cannedChunk;
        }

        @Override public String name() { return vendorName; }

        @Override public ChatCapabilities capabilities() {
            return ChatCapabilities.builder().stream(true).tools(false).build();
        }

        @Override public ChatResponse call(ChatRequest request) {
            this.lastSeenRequest = request;
            this.callCount++;
            return cannedResponse;
        }

        @Override public Flux<ChatChunk> stream(ChatRequest request) {
            this.lastSeenRequest = request;
            this.streamCount++;
            return Flux.just(cannedChunk);
        }
    }

    /** 永不发射的 ChatModel:用于验证 Flux.timeout 后 LlmClient 把 TimeoutException 转成 StreamTimeoutException */
    static final class NeverStreamChatModel implements ChatModel {
        final String vendorName;
        NeverStreamChatModel(String vendorName) { this.vendorName = vendorName; }
        @Override public String name() { return vendorName; }
        @Override public ChatCapabilities capabilities() {
            return ChatCapabilities.builder().stream(true).tools(false).build();
        }
        @Override public ChatResponse call(ChatRequest request) {
            throw new UnsupportedOperationException("NeverStreamChatModel.call");
        }
        @Override public Flux<ChatChunk> stream(ChatRequest request) {
            return Flux.never();
        }
    }

    private RecordingChatModel openai;
    private RecordingChatModel ollama;
    private AiProperties props;
    private ChatModelRouter router;
    private LlmClient client;

    @BeforeEach
    void setup() {
        openai = new RecordingChatModel("openai",
                ChatResponse.builder().id("r-1").model("gpt-4o-mini").content("hi").build());
        ollama = new RecordingChatModel("ollama",
                ChatResponse.builder().id("r-2").model("llama3").content("yo").build());

        props = new AiProperties();
        props.setDefaultVendor("openai");
        props.setDefaultModel("gpt-4o-mini");
        AiProperties.Provider po = new AiProperties.Provider();
        po.setEnabled(true);
        po.setDefaultModel("gpt-4o-mini");
        props.getProviders().put("openai", po);
        AiProperties.Provider pol = new AiProperties.Provider();
        pol.setEnabled(true);
        pol.setDefaultModel("llama3");
        props.getProviders().put("ollama", pol);

        Map<String, ChatModel> models = new HashMap<>();
        models.put("openai", openai);
        models.put("ollama", ollama);
        router = new ChatModelRouter(models, props);
        client = new LlmClient(router, props);
    }

    // ─── call() 主路径 ─────────────────────────────────────────────
    @Nested
    @DisplayName("call() 主路径")
    class CallPath {

        @Test
        @DisplayName("call: vendor 前缀 'openai:gpt-4o-mini' → router 解析 → 实现只看到 'gpt-4o-mini'")
        void call_resolves_and_strips_vendor_prefix() {
            ChatResponse out = client.call(req("openai:gpt-4o-mini"));

            // 返回的就是 RecordingChatModel 的 cannedResponse
            assertThat(out.getId()).isEqualTo("r-1");
            assertThat(out.getContent()).isEqualTo("hi");
            // 只调用了 openai 一次,ollama 没碰
            assertThat(openai.callCount).isEqualTo(1);
            assertThat(ollama.callCount).isZero();
            // 下发的 ChatRequest.model 已经被 LlmClient 改写成去掉 vendor 前缀的纯 model 名
            assertThat(openai.lastSeenRequest).isNotNull();
            assertThat(openai.lastSeenRequest.getModel()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("call: 无 vendor 前缀 'gpt-4o-mini' → 用全局默认 vendor + 原 model 名")
        void call_uses_default_vendor_when_no_prefix() {
            ChatResponse out = client.call(req("gpt-4o-mini"));
            assertThat(out.getModel()).isEqualTo("gpt-4o-mini");
            assertThat(openai.callCount).isEqualTo(1);
            assertThat(openai.lastSeenRequest.getModel()).isEqualTo("gpt-4o-mini");
        }

        @Test
        @DisplayName("call: 'ollama:llama3' → 路由到 ollama 实现")
        void call_routes_to_ollama_when_prefixed() {
            ChatResponse out = client.call(req("ollama:llama3"));
            assertThat(out.getId()).isEqualTo("r-2");
            assertThat(ollama.callCount).isEqualTo(1);
            assertThat(openai.callCount).isZero();
            assertThat(ollama.lastSeenRequest.getModel()).isEqualTo("llama3");
        }

        @Test
        @DisplayName("call: messages / temperature / maxTokens / options / tools 全部下传,不丢字段")
        void call_copies_request_fields_verbatim() {
            Map<String, Object> opts = Map.of("topP", 0.9, "stop", List.of("END"));
            JsonNode params = JsonNodeFactory.instance.objectNode().put("type", "object");
            ToolDefinition tool = ToolDefinition.builder()
                    .name("lookup").description("look up by id").parameters(params).build();

            ChatRequest original = ChatRequest.builder()
                    .model("openai:gpt-4o-mini")
                    .messages(List.of(ChatMessage.builder().role(Role.USER).content("x").build()))
                    .temperature(0.42)
                    .maxTokens(123)
                    .stream(false)
                    .options(opts)
                    .tools(List.of(tool))
                    .build();

            client.call(original);

            ChatRequest downstream = openai.lastSeenRequest;
            assertThat(downstream).isNotNull();
            assertThat(downstream.getModel()).isEqualTo("gpt-4o-mini");        // vendor 前缀已剥
            assertThat(downstream.getMessages()).hasSize(1);
            assertThat(downstream.getMessages().get(0).getContent()).isEqualTo("x");
            assertThat(downstream.getTemperature()).isEqualTo(0.42);
            assertThat(downstream.getMaxTokens()).isEqualTo(123);
            assertThat(downstream.getStream()).isFalse();
            assertThat(downstream.getOptions()).containsKeys("topP", "stop");
            assertThat(downstream.getTools()).hasSize(1);
            assertThat(downstream.getTools().get(0).getName()).isEqualTo("lookup");
            assertThat(downstream.getTools().get(0).getParameters().get("type").asText()).isEqualTo("object");
        }
    }

    // ─── stream() 行为 ─────────────────────────────────────────────
    @Nested
    @DisplayName("stream() 路径")
    class StreamPath {

        @Test
        @DisplayName("stream: 调用 router 解析后,下发的 ChatRequest.stream 自动被置为 true")
        void stream_sets_request_stream_flag_true() {
            ChatRequest original = ChatRequest.builder()
                    .model("openai:gpt-4o-mini")
                    .stream(false)            // 客户端上传 false,LlmClient 应改写为 true
                    .messages(List.of(ChatMessage.builder().role(Role.USER).content("hi").build()))
                    .build();

            Flux<ChatChunk> flux = client.stream(original);
            // 触发实际订阅,确保 RecordingChatModel.stream 被调用
            List<ChatChunk> received = flux.collectList().block();

            assertThat(openai.streamCount).isEqualTo(1);
            assertThat(openai.callCount).isZero();                               // call() 没被调用
            assertThat(openai.lastSeenRequest).isNotNull();
            assertThat(openai.lastSeenRequest.getStream()).isTrue();
            assertThat(openai.lastSeenRequest.getModel()).isEqualTo("gpt-4o-mini");
            assertThat(received).hasSize(1);
            assertThat(received.get(0).getDeltaContent()).isEqualTo("ok");
        }

        @Test
        @DisplayName("stream: 'ollama:llama3' 路由到 ollama 实现")
        void stream_routes_to_ollama_when_prefixed() {
            ChatRequest original = ChatRequest.builder()
                    .model("ollama:llama3")
                    .messages(List.of(ChatMessage.builder().role(Role.USER).content("hi").build()))
                    .build();
            client.stream(original).collectList().block();

            assertThat(ollama.streamCount).isEqualTo(1);
            assertThat(openai.streamCount).isZero();
            assertThat(ollama.lastSeenRequest.getModel()).isEqualTo("llama3");
            assertThat(ollama.lastSeenRequest.getStream()).isTrue();
        }

        @Test
        @DisplayName("stream: 上游从不发射 → Flux.timeout 后抛 StreamTimeoutException")
        void stream_propagates_timeout_when_upstream_never_emits() {
            // 单独构造 client:用 NeverStreamChatModel 让流永不结束
            NeverStreamChatModel slow = new NeverStreamChatModel("openai");
            java.util.Map<String, ChatModel> models = new HashMap<>();
            models.put("openai", slow);
            AiProperties fast = new AiProperties();
            fast.setDefaultVendor("openai");
            fast.setDefaultModel("gpt-4o-mini");
            fast.setRequestTimeout(Duration.ofMillis(300));    // 短超时,让测试快
            AiProperties.Provider p = new AiProperties.Provider();
            p.setEnabled(true);
            p.setDefaultModel("gpt-4o-mini");
            fast.getProviders().put("openai", p);
            ChatModelRouter r = new ChatModelRouter(models, fast);
            LlmClient c = new LlmClient(r, fast);

            ChatRequest req = ChatRequest.builder()
                    .model("openai:gpt-4o-mini")
                    .messages(List.of(ChatMessage.builder().role(Role.USER).content("hi").build()))
                    .build();

            assertThatThrownBy(() -> c.stream(req).collectList().block(Duration.ofSeconds(2)))
                    .isInstanceOf(StreamTimeoutException.class)
                    .hasMessageContaining("流式调用超过 300ms");
        }

        @Test
        @DisplayName("stream: doFinally 信号日志在 complete 时不抛(隐式验证 doFinally 钩子)")
        void stream_logs_signal_on_complete_without_throwing() {
            // 沿用 setup() 的 client(RecordingChatModel 立即发射 1 个 chunk 后 complete)
            ChatRequest req = ChatRequest.builder()
                    .model("openai:gpt-4o-mini")
                    .messages(List.of(ChatMessage.builder().role(Role.USER).content("hi").build()))
                    .build();
            // 不抛 = pass;doFinally 的 signal=complete 日志是副作用,这里只验证副作用不破坏流
            List<ChatChunk> got = client.stream(req).collectList().block();
            assertThat(got).hasSize(1);
        }
    }

    // ─── 异常 / 边界 ──────────────────────────────────────────────
    @Nested
    @DisplayName("异常与边界")
    class ErrorsAndEdges {

        @Test
        @DisplayName("router.resolve 抛 LlmException → call() 直接透传,不再调实现")
        void call_propagates_router_exception_without_invoking_model() {
            // 制造一个未知 vendor 让 router 抛 LLM_MODEL_NOT_FOUND
            assertThatThrownBy(() -> client.call(req("nonexistent:foo")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode()))
                    .hasMessageContaining("未找到 vendor=nonexistent");
            // 实现根本不应被触发
            assertThat(openai.callCount).isZero();
            assertThat(ollama.callCount).isZero();
        }

        @Test
        @DisplayName("router.resolve 抛 LlmException → stream() 同样透传")
        void stream_propagates_router_exception_without_invoking_model() {
            ChatRequest r = ChatRequest.builder()
                    .model("nonexistent:foo")
                    .messages(List.of(ChatMessage.builder().role(Role.USER).content("x").build()))
                    .build();
            assertThatThrownBy(() -> client.stream(r).blockFirst())
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_MODEL_NOT_FOUND.getCode()));
            assertThat(openai.streamCount).isZero();
            assertThat(ollama.streamCount).isZero();
        }

        @Test
        @DisplayName("call: 下游返回 ChatResponse.usage == null 时,LlmClient 不抛 NPE(info 日志可正常发)")
        void call_with_null_usage_does_not_throw() {
            // 单独构造一个没有 usage 的 ChatResponse 的 stub
            ChatResponse stub = ChatResponse.builder().id("r").model("m").content("ok").build();
            RecordingChatModel fresh = new RecordingChatModel("openai", stub);
            Map<String, ChatModel> models = new HashMap<>();
            models.put("openai", fresh);
            ChatModelRouter freshRouter = new ChatModelRouter(models, props);
            LlmClient freshClient = new LlmClient(freshRouter, props);

            // 不抛就是过
            ChatResponse r = freshClient.call(req("openai:gpt-4o-mini"));
            assertThat(r.getUsage()).isNull();
        }
    }

    // ─── 路由 + 门面集成 sanity ─────────────────────────────────
    @Nested
    @DisplayName("路由集成")
    class RouterIntegration {

        @Test
        @DisplayName("连序两次 call('openai:gpt-4o-mini') 都进 openai,ollama 完全不动")
        void one_resolve_per_call_two_calls() {
            ChatRequest original = req("openai:gpt-4o-mini");

            client.call(original);
            client.call(original);

            assertThat(openai.callCount).isEqualTo(2);
            assertThat(ollama.callCount).isZero();
            // 第二次下发仍是同一个 vendor
            assertThat(openai.lastSeenRequest.getModel()).isEqualTo("gpt-4o-mini");
        }
    }

    /** 通用最小 ChatRequest 构造。 */
    private ChatRequest req(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(ChatMessage.builder().role(Role.USER).content("user-msg").build()))
                .build();
    }
}

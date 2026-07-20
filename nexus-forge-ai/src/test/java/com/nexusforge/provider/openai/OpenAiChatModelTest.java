package com.nexusforge.provider.openai;

import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.Role;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.stream.OpenAiStreamParser;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiChatModel 单元测试 —— 用 mockwebserver3 启动本地 HTTP 服务器,
 * 拦截 JDK HttpClient 真实发出的请求,验证契约:
 *
 * <ul>
 *   <li>请求 URL / Method / Headers(Authorization Bearer / Content-Type)符合 OpenAI v1 协议</li>
 *   <li>请求体(messages / temperature / max_tokens / model)序列化格式正确,stream=false</li>
 *   <li>成功响应解析为 {@link ChatResponse},latencyMillis 字段非空</li>
 *   <li>失败响应通过 {@link com.nexusforge.error.LlmErrorMapper} 映射到正确的 ResultCode</li>
 * </ul>
 *
 * <p>本测试与被测类统一使用 Jackson 3.x({@code tools.jackson.databind.ObjectMapper})。
 */
class OpenAiChatModelTest {

    private MockWebServer server;
    private AiProperties props;
    private ObjectMapper json = new ObjectMapper();
    private OpenAiJsonMapper mapper;
    private OpenAiStreamParser streamParser;
    private OpenAiChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        props = new AiProperties();
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(true);
        p.setApiKey("k-test");
        p.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        p.setDefaultModel("gpt-4o-mini");
        props.getProviders().put("openai", p);
        // 缩短超时,使 timeout 测试可控
        props.setRequestTimeout(Duration.ofSeconds(2));

        mapper = new OpenAiJsonMapper(json);
        streamParser = new OpenAiStreamParser();
        model = new OpenAiChatModel(props, json, mapper, streamParser);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.close();
    }

    /** 最简单的 ChatRequest 构造器。 */
    private ChatRequest sampleRequest(String content) {
        return ChatRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(ChatMessage.builder()
                        .role(Role.USER)
                        .content(content)
                        .build()))
                .temperature(0.7)
                .maxTokens(128)
                .build();
    }

    /**
     * mockwebserver3 5.x 的 MockResponse 用 Kotlin Builder;
     * status 必须写成完整的 HTTP status line,形如 {@code "HTTP/1.1 200 OK"};
     * 写 {@code "200 OK"} 之类简写会被 OkHttp 拒绝("Invalid status line")。
     */
    private MockResponse json(int code, String body) {
        return new MockResponse.Builder()
                .status("HTTP/1.1 " + code + " OK")
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    /** mock 端延后写 body,用于测试客户端超时。 */
    private MockResponse slowJson(int code, String body, long delaySeconds) {
        return new MockResponse.Builder()
                .status("HTTP/1.1 " + code + " OK")
                .addHeader("Content-Type", "application/json")
                .bodyDelay(delaySeconds, TimeUnit.SECONDS)
                .body(body)
                .build();
    }

    // ─── 成功路径 ────────────────────────────────────────────────
    @Nested
    @DisplayName("call() 成功路径")
    class SuccessCases {

        @Test
        @DisplayName("200 → ChatResponse,content / usage / model / latencyMillis 全部解析正确")
        void parses_full_completion() throws Exception {
            server.enqueue(json(200, """
                    {"id":"cmpl-1","object":"chat.completion","created":1234567890,
                     "model":"gpt-4o-mini",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"hello world"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                    """));

            ChatResponse resp = model.call(sampleRequest("hi"));

            assertThat(resp.getId()).isEqualTo("cmpl-1");
            assertThat(resp.getModel()).isEqualTo("gpt-4o-mini");
            assertThat(resp.getContent()).isEqualTo("hello world");
            assertThat(resp.getFinishReason()).isEqualTo("stop");
            assertThat(resp.getUsage().getPromptTokens()).isEqualTo(3);
            assertThat(resp.getUsage().getCompletionTokens()).isEqualTo(2);
            assertThat(resp.getUsage().getTotalTokens()).isEqualTo(5);
            assertThat(resp.getLatencyMillis()).isNotNull().isGreaterThanOrEqualTo(0);

            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getMethod()).isEqualTo("POST");
            assertThat(recorded.getTarget()).isEqualTo("/v1/chat/completions");
        }

        @Test
        @DisplayName("请求头包含 Authorization: Bearer <apiKey> 与 Content-Type: application/json")
        void sends_authorization_and_content_type_headers() throws Exception {
            server.enqueue(json(200, """
                    {"id":"x","model":"gpt-4o-mini",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """));

            model.call(sampleRequest("ping"));

            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getMethod()).isEqualTo("POST");
            assertThat(recorded.getTarget()).isEqualTo("/v1/chat/completions");
            assertThat(recorded.getHeaders().get("Authorization")).isEqualTo("Bearer k-test");
            assertThat(recorded.getHeaders().get("Content-Type")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("请求体序列化为 OpenAI v1 schema:model / stream=false / messages[] / temperature / max_tokens")
        void serializes_request_body_in_openai_format() throws Exception {
            server.enqueue(json(200, """
                    {"id":"x","model":"gpt-4o-mini",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """));

            ChatRequest req = ChatRequest.builder()
                    .model("ignored-by-server-replaced-with-provider-default")
                    .messages(List.of(
                            ChatMessage.builder().role(Role.SYSTEM).content("you are helpful").build(),
                            ChatMessage.builder().role(Role.USER).content("hello").build()))
                    .temperature(0.5)
                    .maxTokens(64)
                    .build();
            model.call(req);

            RecordedRequest recorded = server.takeRequest();
            String body = recorded.getBody().utf8();
            assertThat(body).contains("\"model\":\"gpt-4o-mini\"");           // 用 provider 默认 model
            assertThat(body).contains("\"stream\":false");                     // P1 固定 false
            assertThat(body).contains("\"role\":\"system\"").contains("\"content\":\"you are helpful\"");
            assertThat(body).contains("\"role\":\"user\"").contains("\"content\":\"hello\"");
            assertThat(body).contains("\"temperature\":0.5");
            assertThat(body).contains("\"max_tokens\":64");
        }

        @Test
        @DisplayName("usage 段缺失时 ChatResponse 不抛异常,且 usage 为 null")
        void missing_usage_section_produces_null_usage() throws Exception {
            server.enqueue(json(200, """
                    {"id":"x","model":"gpt-4o-mini",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                    """));

            ChatResponse resp = model.call(sampleRequest("hi"));
            assertThat(resp.getContent()).isEqualTo("ok");
            assertThat(resp.getUsage()).isNull();
        }
    }

    // ─── 失败 / 错误路径 ──────────────────────────────────────────
    @Nested
    @DisplayName("call() 错误路径 → ResultCode 映射")
    class FailureCases {

        @Test
        @DisplayName("HTTP 400 → LLM_INVALID_REQUEST,message 含 '上游 4xx'")
        void http_400_maps_to_invalid_request() throws Exception {
            server.enqueue(json(400, "{\"error\":\"bad request\"}"));
            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOfSatisfying(LlmException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
                        assertThat(e.getMessage()).contains("上游 4xx").contains("bad request");
                    });
        }

        @Test
        @DisplayName("HTTP 500 → LLM_PROVIDER_ERROR,message 含 '上游 5xx'")
        void http_500_maps_to_provider_error() throws Exception {
            server.enqueue(json(500, "{\"error\":\"server\"}"));
            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOfSatisfying(LlmException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode());
                        assertThat(e.getMessage()).contains("上游 5xx");
                    });
        }

        @Test
        @DisplayName("HTTP 502 → LLM_PROVIDER_ERROR(Bad Gateway)")
        void http_502_maps_to_provider_error() throws Exception {
            server.enqueue(json(502, "{}"));
            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode()));
        }

        @Test
        @DisplayName("HTTP 401 → 当前实现归到 LLM_INVALID_REQUEST(429 / 401 仍按 4xx 兜底,等后续 PR 细化)")
        void http_401_currently_falls_into_4xx_bucket() throws Exception {
            server.enqueue(json(401, "{\"error\":\"unauthorized\"}"));
            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode()));
        }

        @Test
        @DisplayName("HTTP 200 但 body 非法 JSON → LLM_PROVIDER_ERROR(catch (Exception) 分支)")
        void http_200_invalid_json_maps_to_provider_error() throws Exception {
            server.enqueue(json(200, "not json at all"));
            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode()));
        }

        @Test
        @DisplayName("mock 端 5s 不返回 body,客户端 2s 超时 → LLM_UPSTREAM_TIMEOUT")
        void request_timeout_maps_to_upstream_timeout() throws Exception {
            server.enqueue(slowJson(200, "{\"x\":1}", 5));
            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_UPSTREAM_TIMEOUT.getCode()));
        }
    }

    // ─── 构造期校验 ──────────────────────────────────────────────
    @Nested
    @DisplayName("构造期校验")
    class Construction {

        @Test
        @DisplayName("providers.openai 段缺失 → 构造抛 LLM_CONFIG_MISSING")
        void missing_openai_provider_section_throws_on_construction() {
            AiProperties propsWithoutOpenAi = new AiProperties();
            assertThatThrownBy(() -> new OpenAiChatModel(propsWithoutOpenAi, json, mapper, streamParser))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_CONFIG_MISSING.getCode()));
        }

        @Test
        @DisplayName("providers.openai.enabled = false → 构造抛 LLM_CONFIG_MISSING")
        void disabled_openai_provider_throws_on_construction() {
            AiProperties props2 = new AiProperties();
            AiProperties.Provider p = new AiProperties.Provider();
            p.setEnabled(false);
            props2.getProviders().put("openai", p);
            assertThatThrownBy(() -> new OpenAiChatModel(props2, json, mapper, streamParser))
                    .isInstanceOfSatisfying(LlmException.class, e ->
                            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_CONFIG_MISSING.getCode()));
        }

        @Test
        @DisplayName("providers.openai.apiKey 缺失仍可构造(lazy 验证设计)")
        void missing_api_key_does_not_throw_on_construction() {
            AiProperties props2 = new AiProperties();
            AiProperties.Provider p = new AiProperties.Provider();
            p.setEnabled(true);
            p.setApiKey(null);
            p.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
            p.setDefaultModel("gpt-4o-mini");
            props2.getProviders().put("openai", p);
            new OpenAiChatModel(props2, json, mapper, streamParser);
        }

        @Test
        @DisplayName("providers.openai.baseUrl 缺失时构造不抛(默认值 'https://api.openai.com/v1')")
        void missing_base_url_defaults_to_openai_official() {
            AiProperties props2 = new AiProperties();
            AiProperties.Provider p = new AiProperties.Provider();
            p.setEnabled(true);
            p.setApiKey("k-test");
            p.setDefaultModel("gpt-4o-mini");
            props2.getProviders().put("openai", p);
            OpenAiChatModel m = new OpenAiChatModel(props2, json, mapper, streamParser);
            assertThat(m).isNotNull();
        }
    }

    // ─── API / SPI 一致性 ─────────────────────────────────────────
    @Nested
    @DisplayName("ChatModel SPI")
    class SpiContract {

        @Test
        @DisplayName("name() 返回 'openai'")
        void name_is_openai() {
            assertThat(model.name()).isEqualTo("openai");
        }

        @Test
        @DisplayName("capabilities() 报告 stream / tools / vision / jsonMode 全 true")
        void capabilities_advertises_full_feature_set() {
            ChatCapabilities cap = model.capabilities();
            assertThat(cap.isStream()).isTrue();
            assertThat(cap.isTools()).isTrue();
            assertThat(cap.isVision()).isTrue();
            assertThat(cap.isJsonMode()).isTrue();
        }
        @Test
        @DisplayName("stream(ChatRequest) 在 P2 返回 ChatChunk 流:解析 OpenAI SSE 帧 → 业务单元")
        void stream_returns_parsed_chunks_from_sse_response() throws Exception {
            server.enqueue(new MockResponse.Builder()
                    .status("HTTP/1.1 200 OK")
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: {\"id\":\"cmpl-1\",\"model\":\"gpt-4o-mini\",\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n"
                        + "data: {\"id\":\"cmpl-1\",\"model\":\"gpt-4o-mini\",\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n"
                        + "data: [DONE]\n\n")
                    .build());
            java.util.List<ChatChunk> chunks = new java.util.ArrayList<>();
            Throwable[] err = {null};
            model.stream(sampleRequest("hi"))
                    .doOnError(e -> err[0] = e)
                    .doOnNext(chunks::add)
                    .blockLast(java.time.Duration.ofSeconds(5));
            if (err[0] != null) throw new AssertionError("stream errored", err[0]);
        }
    }
}

package com.nexusforge.provider.anthropic;

import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolDefinition;
import com.nexusforge.ai.ToolCall;
import com.nexusforge.config.AiProperties;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.provider.support.ChatModelHttpSupport;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AnthropicChatModel 单元测试 —— 用 mockwebserver3 启动本地 HTTP 服务器,
 * 验证 Anthropic Messages API 协议契约。
 *
 * <p>仅覆盖 call 路径(sync);流式走 WebClient 与 MockWebServer 集成度差,
 * 不在单测范围(StreamIT 在 web 模块覆盖端到端)。
 */
class AnthropicChatModelTest {

    private MockWebServer server;
    private AiProperties props;
    private ObjectMapper json = new ObjectMapper();
    private AnthropicChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        props = new AiProperties();
        AiProperties.Provider p = new AiProperties.Provider();
        p.setEnabled(true);
        p.setApiKey("k-test-anthropic");
        p.setBaseUrl(server.url("").toString().replaceAll("/$", ""));
        p.setDefaultModel("claude-3-5-haiku-20241022");
        props.getProviders().put("anthropic", p);
        props.setRequestTimeout(Duration.ofSeconds(2));

        ChatModelHttpSupport http = new ChatModelHttpSupport(props);
        AnthropicMessagesStreamParser streamParser = new AnthropicMessagesStreamParser();
        model = new AnthropicChatModel(props, json, http, streamParser);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.close();
    }

    /** MockResponse 工厂:OpenAI 测试同款 json(...) 形态。 */
    private MockResponse json(int code, String body) {
        return new MockResponse.Builder()
                .status("HTTP/1.1 " + code + " OK")
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private ChatRequest sampleRequest(String content) {
        return ChatRequest.builder()
                .model("claude-3-5-haiku-20241022")
                .messages(List.of(ChatMessage.builder()
                        .role(Role.USER)
                        .content(content)
                        .build()))
                .temperature(0.5)
                .maxTokens(256)
                .build();
    }

    // ───────────────────────────────────────────────
    // 协议契约
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("Anthropic Messages API 协议契约")
    class ProtocolContract {

        @Test
        @DisplayName("call: POST /v1/messages + x-api-key + anthropic-version 头齐全")
        void call_sends_correct_headers() throws Exception {
            server.enqueue(json(200, """
                    {"id":"msg_mock_01","type":"message","role":"assistant",
                     "model":"claude-3-5-haiku-20241022",
                     "content":[{"type":"text","text":"hi"}],
                     "stop_reason":"end_turn",
                     "usage":{"input_tokens":4,"output_tokens":1}}
                    """));

            model.call(sampleRequest("hi"));
            RecordedRequest req = server.takeRequest();
            assertThat(req.getTarget()).isEqualTo("/v1/messages");
            assertThat(req.getMethod()).isEqualTo("POST");
            assertThat(req.getHeaders().get("x-api-key")).isEqualTo("k-test-anthropic");
            assertThat(req.getHeaders().get("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(req.getHeaders().get("Content-Type")).isEqualTo("application/json");
        }
        @Test
        @DisplayName("call: 请求体 — model / max_tokens / messages 序列化为 Anthropic 形态")
        void call_serializes_request_body() throws Exception {
            server.enqueue(json(200, """
                    {"id":"msg_x","type":"message","role":"assistant",
                     "model":"claude-3-5-haiku-20241022",
                     "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                     "usage":{"input_tokens":3,"output_tokens":1}}
                    """));

            model.call(sampleRequest("hello"));

            RecordedRequest req = server.takeRequest();
            JsonNode body = json.readTree(req.getBody().utf8());
            assertThat(body.get("model").asString()).isEqualTo("claude-3-5-haiku-20241022");
            assertThat(body.get("max_tokens").asInt()).isEqualTo(256);
            assertThat(body.get("temperature").asDouble()).isEqualTo(0.5);
            assertThat(body.get("messages").isArray()).isTrue();
            assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("user");
            assertThat(body.get("messages").get(0).get("content").get(0).get("text").asString())
                    .isEqualTo("hello");
        }

        @Test
        @DisplayName("call: SYSTEM 消息分离为顶层 system 字段,不进入 messages 列表")
        void call_separates_system_to_top_field() throws Exception {
            server.enqueue(json(200, """
                    {"id":"msg_x","type":"message","role":"assistant",
                     "model":"claude-3-5-haiku-20241022",
                     "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                     "usage":{"input_tokens":3,"output_tokens":1}}
                    """));

            ChatRequest req = ChatRequest.builder()
                    .model("claude-3-5-haiku-20241022")
                    .messages(List.of(
                            ChatMessage.builder().role(Role.SYSTEM).content("You are helpful.").build(),
                            ChatMessage.builder().role(Role.USER).content("hi").build()))
                    .build();
            model.call(req);

            RecordedRequest recorded = server.takeRequest();
            JsonNode body = json.readTree(recorded.getBody().utf8());
            assertThat(body.get("system").asString()).isEqualTo("You are helpful.");
            assertThat(body.get("messages")).hasSize(1);
            assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("user");
        }

        @Test
        @DisplayName("call: tools 翻译为 input_schema,不是 parameters")
        void call_translates_tools_to_input_schema() throws Exception {
            server.enqueue(json(200, """
                    {"id":"msg_x","type":"message","role":"assistant",
                     "model":"claude-3-5-haiku-20241022",
                     "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                     "usage":{"input_tokens":3,"output_tokens":1}}
                    """));

            JsonNode params = json.createObjectNode().put("type", "object");
            ChatRequest req = ChatRequest.builder()
                    .model("claude-3-5-haiku-20241022")
                    .messages(List.of(ChatMessage.builder().role(Role.USER).content("weather?").build()))
                    .tools(List.of(ToolDefinition.builder()
                            .name("get_weather")
                            .description("天气查询")
                            .parameters(params)
                            .build()))
                    .build();
            model.call(req);

            RecordedRequest recorded = server.takeRequest();
            JsonNode body = json.readTree(recorded.getBody().utf8());
            assertThat(body.get("tools")).hasSize(1);
            JsonNode tool = body.get("tools").get(0);
            assertThat(tool.get("name").asString()).isEqualTo("get_weather");
            assertThat(tool.get("description").asString()).isEqualTo("天气查询");
            // Anthropic 用 input_schema,不是 OpenAI 的 parameters
            assertThat(tool.get("input_schema").get("type").asString()).isEqualTo("object");
            assertThat(tool.has("parameters")).isFalse();
        }
    }

    // ───────────────────────────────────────────────
    // 响应解析
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("Anthropic 响应解析")
    class ResponseParsing {

        @Test
        @DisplayName("fromAnthropic: 多个 text 块合并为 content")
        void parse_merges_multiple_text_blocks() {
            server.enqueue(json(200, """
                    {"id":"msg_x","type":"message","role":"assistant",
                     "model":"claude-3-5-haiku-20241022",
                     "content":[
                       {"type":"text","text":"line 1"},
                       {"type":"text","text":"line 2"}
                     ],
                     "stop_reason":"end_turn",
                     "usage":{"input_tokens":4,"output_tokens":4}}
                    """));

            ChatResponse resp = model.call(sampleRequest("hi"));
            assertThat(resp.getContent()).isEqualTo("line 1\nline 2");
            assertThat(resp.getFinishReason()).isEqualTo("stop");  // end_turn → stop 归一化
        }

        @Test
        @DisplayName("fromAnthropic: tool_use 块 → ToolCall 列表")
        void parse_extracts_tool_use() {
            server.enqueue(json(200, """
                    {"id":"msg_x","type":"message","role":"assistant",
                     "model":"claude-3-5-haiku-20241022",
                     "content":[
                       {"type":"text","text":"I'll check the weather."},
                       {"type":"tool_use","id":"toolu_01","name":"get_weather",
                        "input":{"city":"Beijing"}}
                     ],
                     "stop_reason":"tool_use",
                     "usage":{"input_tokens":8,"output_tokens":12}}
                    """));

            ChatResponse resp = model.call(sampleRequest("weather?"));
            assertThat(resp.getContent()).isEqualTo("I'll check the weather.");
            assertThat(resp.getToolCalls()).hasSize(1);
            ToolCall tc = resp.getToolCalls().get(0);
            assertThat(tc.getId()).isEqualTo("toolu_01");
            assertThat(tc.getName()).isEqualTo("get_weather");
            assertThat(tc.getArguments().get("city").asString()).isEqualTo("Beijing");
            assertThat(resp.getFinishReason()).isEqualTo("tool_calls");  // tool_use → tool_calls 归一化
            assertThat(resp.getUsage().getTotalTokens()).isEqualTo(20);
        }

        @Test
        @DisplayName("fromAnthropic: stop_reason=end_turn 映射为 finishReason=stop")
        void parse_stop_reason_mapping() {
            server.enqueue(json(200, """
                    {"id":"msg_x","type":"message","role":"assistant",
                     "model":"claude-3-5-haiku-20241022",
                     "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                     "usage":{"input_tokens":3,"output_tokens":1}}
                    """));

            ChatResponse resp = model.call(sampleRequest("hi"));
            assertThat(resp.getFinishReason()).isEqualTo("stop");
        }
    }

    // ───────────────────────────────────────────────
    // 错误映射
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("错误响应 → LlmException")
    class ErrorMapping {

        @Test
        @DisplayName("401: LLM_INVALID_REQUEST (4xx → 上游客户端错误)")
        void call_401_throws() {
            server.enqueue(json(401, "{\"error\":\"invalid api key\"}"));

            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(3003);
        }

        @Test
        @DisplayName("429: LLM_INVALID_REQUEST (429 也归到 4xx)")
        void call_429_throws_invalid_request() {
            server.enqueue(json(429, "{\"error\":\"rate limit\"}"));

            assertThatThrownBy(() -> model.call(sampleRequest("hi")))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(3003);
        }
    }

    // ───────────────────────────────────────────────
    // SPI 契约
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("SPI 契约")
    class SpiContract {

        @Test
        @DisplayName("name() == \"anthropic\"")
        void name_is_anthropic() {
            assertThat(model.name()).isEqualTo("anthropic");
        }

        @Test
        @DisplayName("capabilities: stream / tools / vision 开启,jsonMode 关闭")
        void capabilities_advertise_correctly() {
            ChatCapabilities cap = model.capabilities();
            assertThat(cap.isStream()).isTrue();
            assertThat(cap.isTools()).isTrue();
            assertThat(cap.isVision()).isTrue();
            assertThat(cap.isJsonMode()).isFalse();
        }
    }

    // ───────────────────────────────────────────────
    // 配置校验
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("配置校验")
    class ConfigValidation {

        @Test
        @DisplayName("providers.anthropic 未配置 → LLM_CONFIG_MISSING")
        void missing_provider_throws() {
            AiProperties emptyProps = new AiProperties();
            emptyProps.setRequestTimeout(Duration.ofSeconds(2));
            ChatModelHttpSupport http = new ChatModelHttpSupport(emptyProps);
            AnthropicMessagesStreamParser sp = new AnthropicMessagesStreamParser();

            assertThatThrownBy(() -> new AnthropicChatModel(emptyProps, json, http, sp))
                    .isInstanceOf(LlmException.class)
                    .extracting("code").isEqualTo(3001);
        }
    }
}
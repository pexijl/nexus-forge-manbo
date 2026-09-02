package com.nexusforge.flows;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.testsupport.IntegrationTestBase;
import com.nexusforge.testsupport.MockChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static com.nexusforge.enums.ResultCode.SUCCESS;
import static com.nexusforge.enums.ResultCode.UNAUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM 网关 P1 集成测试 —— 验证 Nexus Forge web 容器启动后,/api/ai/chat 端点的
 * 端到端行为(鉴权、入参解析、回声、错误码)。
 *
 * <p>关键开关:
 * <ul>
 *   <li>{@code @Import(MockChatModel.class)} —— 注入一个 name="openai" 的真实 ChatModel bean,
 *       取代环境中的 {@code OpenAiChatModel} 在路由器中的位置(后者会被条件跳过)。</li>
 *   <li>{@code spring.ai.providers.openai.api-key=mock-key} —— 让
 *       {@code AiProperties.providers.openai} map 里有非 null Provider 段,
 *       满足 {@code ChatModelRouter} 的"vendor 启用"前置校验。</li>
 * </ul>
 *
 * <p>{@code spring.ai.providers.openai.enabled=false} 同时存在会
 * (a) 关掉真 {@code OpenAiChatModel}({@code @ConditionalOnProperty}),以及
 * (b) 同步影响 {@code AiProperties.providers.openai.enabled} 字段;
 * 因此路由器的 vendor 启用校验会失败。My 解决:不设 enabled(让它保留 Provider 字段默认值 true),
 * 用 mock 替换真实现,而不是用 property 关掉真实现。
 *
 * <p>真 OpenAiChatModel 的构造期校验要求 providers.openai 段必须 enabled,
 * 而默认 Provider.enabled=true。所以即使不写 enabled,只要给 api-key 一个 mock 值,
 * Provider 段就会被 Spring 绑定到一个实例,enabled 字段保持默认值 true。
 * OpenAiChatModel 也会被装配——但因为 LlmClient.call() 调用我们 mock 的 ChatModel 而不是它,
 * 真实 OpenAiChatModel 永远不会被触发,也就不会发外网请求。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockChatModel.class)
@TestPropertySource(properties = {
        "spring.ai.providers.openai.api-key=mock-key"   // 让 provider 段存在,默认 enabled=true
})
class AiChatIT extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
    }

    /** 每个用例独立注册一个用户,拿到 access token。 */
    private String freshAccessToken() {
        String username = "ai_" + System.nanoTime();
        var regResp = rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                        "email", username + "@example.com",
                        "password", "secret123"),
                JsonNode.class);
        assertThat(regResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(regResp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
        String[] tokens = auth.loginBoth(username, "secret123");
        return tokens[0];
    }

    private HttpHeaders bearer(String access) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ─── 用例 ──────────────────────────────────────────────────

    @Test
    @DisplayName("未登录 POST /api/ai/chat → 401 UNAUTHORIZED")
    void unauthenticated_post_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"model":"openai:gpt-4o-mini",
                 "messages":[{"role":"USER","content":"hello"}]}
                """;
        assertThatThrownBy(() -> rest().exchange("/api/ai/chat", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.Unauthorized.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(401);
                    assertThat(e.getResponseBodyAsString())
                            .contains("\"code\":" + UNAUTHORIZED.getCode());
                });
    }

    @Test
    @DisplayName("登录后 POST /api/ai/chat → 200,envelope.code=0,data.content='echo:hello'")
    void authenticated_post_echoes_user_message() {
        String access = freshAccessToken();
        HttpHeaders headers = bearer(access);

        String body = """
                {"model":"openai:gpt-4o-mini",
                 "messages":[{"role":"USER","content":"hello"}]}
                """;
        var resp = rest().exchange("/api/ai/chat", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode payload = resp.getBody();
        assertThat(payload.get("code").asInt()).isEqualTo(SUCCESS.getCode());
        assertThat(payload.get("message").asString()).isNotBlank();
        assertThat(payload.get("data").get("content").asString()).isEqualTo("echo:hello");
        assertThat(payload.get("data").get("model").asString()).isEqualTo("mock-openai-model");
        assertThat(payload.get("data").get("id").asString()).startsWith("mock-");
        assertThat(payload.get("data").get("finishReason").asString()).isEqualTo("stop");
    }

    @Test
    @DisplayName("登录后多轮对话,mock 取最后一条 USER 消息回声")
    void multi_turn_takes_last_user_message() {
        String access = freshAccessToken();
        HttpHeaders headers = bearer(access);

        String body = """
                {"model":"openai:gpt-4o-mini",
                 "messages":[
                   {"role":"SYSTEM","content":"you are helpful"},
                   {"role":"USER","content":"first"},
                   {"role":"ASSISTANT","content":"first-reply"},
                   {"role":"USER","content":"second"}
                 ]}
                """;
        var resp = rest().exchange("/api/ai/chat", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
        assertThat(resp.getBody().get("data").get("content").asString()).isEqualTo("echo:second");
    }

    @Test
    @DisplayName("messages 字段为空列表 → 400 VALIDATION_FAILED (envelope.code=1001)")
    void empty_messages_returns_validation_failed() {
        String access = freshAccessToken();
        HttpHeaders headers = bearer(access);

        Map<String, Object> bad = Map.of(
                "model", "openai:gpt-4o-mini",
                "messages", List.of()      // 违反 @NotEmpty
        );

        assertThatThrownBy(() -> rest().exchange("/api/ai/chat", HttpMethod.POST,
                new HttpEntity<>(bad, headers), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getResponseBodyAsString())
                            .contains("\"code\":" + ResultCode.VALIDATION_FAILED.getCode());
                });
    }

    @Test
    @DisplayName("未注册 vendor 'mistral:foo' → envelope.code=3002 (LLM_MODEL_NOT_FOUND)")
    void unknown_vendor_returns_LLM_MODEL_NOT_FOUND() {
        String access = freshAccessToken();
        HttpHeaders headers = bearer(access);

        // 'mistral' 不在 ChatModel map,也不在 providers 里 → router 抛 LLM_MODEL_NOT_FOUND(3002)
        // mapStatus 把 3002 → HTTP 400
        String body = """
                {"model":"mistral:foo",
                 "messages":[{"role":"USER","content":"hi"}]}
                """;
        assertThatThrownBy(() -> rest().exchange("/api/ai/chat", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    String respBody = e.getResponseBodyAsString();
                    assertThat(respBody)
                            .contains("\"code\":" + ResultCode.LLM_MODEL_NOT_FOUND.getCode());
                    // 文案迭代过(commit 4c6ecad 后从"未找到 vendor=X"改成
                    // "请求指定 vendor=X 不支持或未启用"),不断定具体文本,只断 vendor 名
                    assertThat(respBody).contains("vendor=mistral");
                });
    }

    @Test
    @DisplayName("未带 Authorization 头 → 401(Security 配置未漏配)")
    void access_without_token_returns_401_even_with_other_headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 没设 Authorization

        String body = """
                {"model":"openai:gpt-4o-mini",
                 "messages":[{"role":"USER","content":"hi"}]}
                """;
        assertThatThrownBy(() -> rest().exchange("/api/ai/chat", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }
}

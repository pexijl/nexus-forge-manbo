package com.nexusforge.flows;

import com.nexusforge.ai.ChatUsage;
import com.nexusforge.client.UsageRecorder;
import com.nexusforge.testsupport.IntegrationTestBase;
import com.nexusforge.testsupport.MockChatModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.OK;

/**
 * P5 Step 9 — 验证 actuator 端点暴露 Micrometer 指标。
 *
 * <p>指标由 {@code ConversationService.sendMessage} 内的 {@code UsageRecorder} 埋点,
 * 所以测试走「创建对话 → 发消息」流程而非直接 POST /api/ai/chat。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockChatModel.class)
@TestPropertySource(properties = {
        "spring.ai.providers.openai.api-key=mock-key"
})
class ApplicationMetricsIT extends IntegrationTestBase {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private UsageRecorder usageRecorder;

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
    }

    private String freshAccessToken() {
        String username = "metrics_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                        "email", username + "@example.com",
                        "password", "secret123"),
                JsonNode.class);
        return auth.loginBoth(username, "secret123")[0];
    }

    private HttpHeaders bearer(String access) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 创建对话 + 发一条消息。
     * 流程:POST /api/ai/conversations → POST /api/ai/conversations/{id}/messages
     */
    private void triggerOneSendMessage(String access) {
        HttpHeaders headers = bearer(access);

        // 1. 创建对话
        String createBody = """
                {"title":"metrics-test","model":"openai:gpt-4o-mini"}
                """;
        var createResp = rest().exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(createBody, headers), JsonNode.class);
        assertThat(createResp.getStatusCode().is2xxSuccessful()).isTrue();
        long convId = createResp.getBody().get("data").get("id").asLong();

        // 2. 发消息(走 ConversationService → UsageRecorder)
        String msgBody = """
                {"content":"hello metrics"}
                """;
        var msgResp = rest().exchange(
                "/api/ai/conversations/" + convId + "/messages",
                HttpMethod.POST, new HttpEntity<>(msgBody, headers), JsonNode.class);
        assertThat(msgResp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ─── 用例 ──────────────────────────────────────────────────

    @Test
    @DisplayName("UsageRecorder.meterRegistry 非 null + counter 直接验证")
    void usageRecorder_has_meter_registry() throws Exception {
        // 反射读 meterRegistry 字段,确认注入成功
        var field = UsageRecorder.class.getDeclaredField("meterRegistry");
        field.setAccessible(true);
        Object mr = field.get(usageRecorder);
        assertThat(mr).as("UsageRecorder.meterRegistry should not be null").isNotNull();

        // 直接调 recordMetrics,验证 counter 注册
        usageRecorder.recordMetrics(
                ChatUsage.builder()
                        .promptTokens(10).completionTokens(20).totalTokens(30).build(),
                "test-model");

        var counter = meterRegistry.find("ai.chat.requests").counter();
        assertThat(counter).as("counter should exist after recordMetrics").isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("触发 1 次 sendMessage → MeterRegistry 中 ai.chat.requests ≥ 1")
    void sendMessage_increments_counter() {
        String access = freshAccessToken();
        triggerOneSendMessage(access);

        var counter = meterRegistry.find("ai.chat.requests").counter();
        assertThat(counter).as("ai.chat.requests counter should exist after sendMessage").isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("GET /actuator/metrics/ai.chat.requests → name + measurements 存在")
    void metrics_endpoint_shows_chat_request_count() {
        String access = freshAccessToken();
        triggerOneSendMessage(access);

        var resp = rest().getForEntity(
                "/actuator/metrics/ai.chat.requests", JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(OK);

        JsonNode body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("name").asString()).isEqualTo("ai.chat.requests");

        JsonNode measurements = body.get("measurements");
        assertThat(measurements).isNotNull();
        assertThat(measurements.size()).isGreaterThanOrEqualTo(1);
        double count = measurements.get(0).get("value").asDouble();
        assertThat(count).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("GET /actuator/metrics → 名称列表包含 ai.chat.requests")
    void metrics_list_includes_ai_metrics() {
        // 确保至少有一个指标被注册
        String access = freshAccessToken();
        triggerOneSendMessage(access);

        var resp = rest().getForEntity("/actuator/metrics", JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(OK);

        JsonNode names = resp.getBody().get("names");
        assertThat(names).isNotNull();
        boolean found = false;
        for (JsonNode name : names) {
            if ("ai.chat.requests".equals(name.asString())) {
                found = true;
                break;
            }
        }
        assertThat(found).as("metrics list should contain ai.chat.requests").isTrue();
    }
}

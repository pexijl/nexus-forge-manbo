package com.nexusforge.flows;

import com.nexusforge.testsupport.IntegrationTestBase;
import com.nexusforge.testsupport.MockChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 Step 10 — 端到端 quota 校验。
 *
 * <p>quota 极低(requestLimit=3),burst 足够高(100)避免 rate-limit 干扰。
 * MockChatModel 返回 usage → ai_message_usage 有数据 → QuotaService 真正校验。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockChatModel.class)
@TestPropertySource(properties = {
        "spring.ai.providers.openai.api-key=mock-key",
        "spring.ai.quota.tiers.USER.daily-token-limit=1000000",
        "spring.ai.quota.tiers.USER.request-limit=3",
        "spring.ai.quota.tiers.ADMIN.daily-token-limit=1000000",
        "spring.ai.quota.tiers.ADMIN.request-limit=100",
        "spring.ai.rate-limit.user-burst=100",
        "spring.ai.rate-limit.ip-burst=100"
})
class AiQuotaIT extends IntegrationTestBase {

    private RestTemplate http;

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
        http = restNoErrorHandling();
    }

    // ─── helpers ───────────────────────────────────────────────

    private String freshUser() {
        return auth.registerAndLogin("quota");
    }

    private HttpHeaders bearer(String access) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private long createConversation(String access) {
        String body = """
                {"title":"quota-test","model":"openai:gpt-4o-mini"}
                """;
        var resp = http.exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(body, bearer(access)), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return resp.getBody().get("data").get("id").asLong();
    }

    private ResponseEntity<JsonNode> sendMessage(long convId, String content, String access) {
        String body = """
                {"content":"%s"}
                """.formatted(content);
        return http.exchange(
                "/api/ai/conversations/" + convId + "/messages",
                HttpMethod.POST, new HttpEntity<>(body, bearer(access)), JsonNode.class);
    }

    // ─── quota 测试 ────────────────────────────────────────────

    @Test
    @DisplayName("单用户连续发 requestLimit+1 次 → 第 4 次返回 HTTP 429 + code 3007")
    void quota_exceeded_returns_429() {
        String access = freshUser();
        long convId = createConversation(access);

        // 前 3 次(requestLimit=3)应成功
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<JsonNode> r = sendMessage(convId, "q-" + i, access);
            assertThat(r.getStatusCode().is2xxSuccessful())
                    .as("request %d should pass", i).isTrue();
        }

        // 第 4 次应触发配额超限
        ResponseEntity<JsonNode> fourth = sendMessage(convId, "q-4", access);
        assertThat(fourth.getStatusCode().value()).isEqualTo(429);
        JsonNode body = fourth.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code").asInt()).isEqualTo(3007);
    }

    @Test
    @DisplayName("quota 校验前 3 次通过 + 第 4 次拒绝 → 验证 requestCount 正确累加")
    void quota_request_count_accumulates() {
        String access = freshUser();
        long convId = createConversation(access);

        // 发 2 条,验证都成功(确认 quota 计数正确,不是0次就拒绝)
        for (int i = 1; i <= 2; i++) {
            ResponseEntity<JsonNode> r = sendMessage(convId, "acc-" + i, access);
            assertThat(r.getStatusCode().is2xxSuccessful())
                    .as("request %d should pass", i).isTrue();
        }

        // 第 3 条仍成功(2 < 3)
        ResponseEntity<JsonNode> third = sendMessage(convId, "acc-3", access);
        assertThat(third.getStatusCode().is2xxSuccessful()).isTrue();

        // 第 4 条拒绝(3 >= 3)
        ResponseEntity<JsonNode> fourth = sendMessage(convId, "acc-4", access);
        assertThat(fourth.getStatusCode().value()).isEqualTo(429);
        assertThat(fourth.getBody().get("code").asInt()).isEqualTo(3007);
    }
}

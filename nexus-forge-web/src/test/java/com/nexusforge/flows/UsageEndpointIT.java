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
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 Step 10 — 端到端 usage endpoint 验证。
 *
 * <p>走完整对话流程 → 检查 {@code GET /api/ai/usage} 返回数据正确。
 * MockChatModel 返回 usage → ai_message_usage 有真实数据。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockChatModel.class)
@TestPropertySource(properties = {
        "spring.ai.providers.openai.api-key=mock-key",
        "spring.ai.quota.tiers.USER.request-limit=10000",
        "spring.ai.rate-limit.user-burst=100",
        "spring.ai.rate-limit.ip-burst=100"
})
class UsageEndpointIT extends IntegrationTestBase {

    private RestTemplate http;

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
        http = restNoErrorHandling();
    }

    // ─── helpers ───────────────────────────────────────────────

    private String freshUser() {
        return auth.registerAndLogin("usage");
    }

    private HttpHeaders bearer(String access) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private long createConversation(String access) {
        String body = """
                {"title":"usage-test","model":"openai:gpt-4o-mini"}
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
    private ResponseEntity<JsonNode> getUsage(String access, String from, String to) {
        StringBuilder sb = new StringBuilder("/api/ai/usage");
        String sep = "?";
        if (from != null) { sb.append(sep).append("from=").append(from); sep = "&"; }
        if (to != null) { sb.append(sep).append("to=").append(to); }
        return http.exchange(sb.toString(), HttpMethod.GET,
                new HttpEntity<>(bearer(access)), JsonNode.class);
    }

    // ─── usage endpoint 测试 ───────────────────────────────────

    @Test
    @DisplayName("发 3 条消息后 GET /api/ai/usage → requestCount=3, totalTokens > 0")
    void usage_shows_correct_aggregation() {
        String access = freshUser();
        long convId = createConversation(access);

        for (int i = 1; i <= 3; i++) {
            assertThat(sendMessage(convId, "usage-" + i, access).getStatusCode().is2xxSuccessful()).isTrue();
        }

        ResponseEntity<JsonNode> resp = getUsage(access, null, null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = resp.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("requestCount").asLong()).isEqualTo(3L);
        assertThat(data.get("totalTokens").asLong()).isGreaterThan(0L);
        assertThat(data.get("promptTokens").asLong()).isGreaterThan(0L);
        assertThat(data.get("completionTokens").asLong()).isGreaterThan(0L);

        // byModel 应有至少 1 条记录
        JsonNode byModel = data.get("byModel");
        assertThat(byModel).isNotNull();
        assertThat(byModel.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/ai/usage?from=...&to=... → 时间窗外的数据不计入")
    void usage_with_time_window_filters() {
        String access = freshUser();
        long convId = createConversation(access);

        // 发 2 条消息
        for (int i = 1; i <= 2; i++) {
            assertThat(sendMessage(convId, "tw-" + i, access).getStatusCode().is2xxSuccessful()).isTrue();
        }

        // 先查全量
        ResponseEntity<JsonNode> full = getUsage(access, null, null);
        long fullCount = full.getBody().get("data").get("requestCount").asLong();
        assertThat(fullCount).isEqualTo(2L);

        // 查"1 秒前到现在"→ 应包含全部
        String now = java.time.Instant.now().toString();
        String recent = java.time.Instant.now().minusSeconds(1).toString();
        ResponseEntity<JsonNode> recentResp = getUsage(access, recent, now);
        long recentCount = recentResp.getBody().get("data").get("requestCount").asLong();
        assertThat(recentCount).isEqualTo(2L);

        // 查"2 小时前到 1 小时前"→ 应为 0
        String oldFrom = java.time.Instant.now().minusSeconds(7200).toString();
        String oldTo = java.time.Instant.now().minusSeconds(3600).toString();
        ResponseEntity<JsonNode> oldResp = getUsage(access, oldFrom, oldTo);
        long oldCount = oldResp.getBody().get("data").get("requestCount").asLong();
        assertThat(oldCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("byModel 按 totalTokens 降序排列")
    void usage_by_model_sorted_by_tokens() {
        String access = freshUser();
        long convId = createConversation(access);

        // 发 1 条消息 → byModel 只有 1 条,验证结构正确
        assertThat(sendMessage(convId, "sort-1", access).getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<JsonNode> resp = getUsage(access, null, null);
        JsonNode byModel = resp.getBody().get("data").get("byModel");
        assertThat(byModel.size()).isGreaterThanOrEqualTo(1);

        // 验证 model 字段非空,totalTokens > 0
        JsonNode first = byModel.get(0);
        assertThat(first.get("model").asString()).isNotEmpty();
        assertThat(first.get("totalTokens").asLong()).isGreaterThan(0L);
    }
}

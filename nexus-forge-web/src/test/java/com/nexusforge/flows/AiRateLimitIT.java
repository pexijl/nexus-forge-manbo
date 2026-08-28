package com.nexusforge.flows;

import com.nexusforge.config.AiProperties;
import com.nexusforge.ratelimit.TokenBucketRateLimiter;
import com.nexusforge.testsupport.IntegrationTestBase;
import com.nexusforge.testsupport.MockChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockChatModel.class)
@TestPropertySource(properties = {
        "spring.ai.providers.openai.api-key=mock-key",
        "spring.ai.quota.enabled=false",
        "spring.ai.rate-limit.user-qps=1.0",
        "spring.ai.rate-limit.user-burst=1",
        "spring.ai.rate-limit.ip-qps=100.0",
        "spring.ai.rate-limit.ip-burst=100"
})
class AiRateLimitIT extends IntegrationTestBase {

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private TokenBucketRateLimiter rateLimiter;

    private HttpClient jdkHttp;
    private String baseUrl;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        db.clean();
        redis.flush();
        // 清除 Caffeine 内存令牌桶缓存,避免跨测试方法残留
        var field = TokenBucketRateLimiter.class.getDeclaredField("buckets");
        field.setAccessible(true);
        Object cache = field.get(rateLimiter);
        var asMap = cache.getClass().getMethod("asMap");
        asMap.trySetAccessible();
        ConcurrentMap<?, ?> map = (ConcurrentMap<?, ?>) asMap.invoke(cache);
        map.clear();

        jdkHttp = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + port;
    }

    private String freshUser() {
        return auth.registerAndLogin("rl");
    }

    private int chatStatus(String accessToken) throws Exception {
        String body = """
                {"model":"openai:gpt-4o-mini","messages":[{"role":"USER","content":"hi"}]}
                """;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ai/chat"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return jdkHttp.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("burst=1 连续 2 次 /api/ai/chat → 第 2 次 429")
    void rate_limit_exceeded_returns_429() throws Exception {
        assertThat(aiProperties.getRateLimit().getUserBurst()).isEqualTo(1);
        String access = freshUser();
        assertThat(chatStatus(access)).isEqualTo(200);
        assertThat(chatStatus(access)).isEqualTo(429);
    }

    @Test
    @DisplayName("不同用户独立令牌桶 → A 被限不影响 B")
    void rate_limit_per_user_isolation() throws Exception {
        String userA = freshUser();
        String userB = freshUser();
        assertThat(chatStatus(userA)).isEqualTo(200);
        assertThat(chatStatus(userA)).isEqualTo(429);
        assertThat(chatStatus(userB)).isEqualTo(200);
    }
}

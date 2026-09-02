package com.nexusforge.flows;

import com.nexusforge.testsupport.IntegrationTestBase;
import com.nexusforge.testsupport.MockChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.nexusforge.enums.ResultCode.UNAUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 网关 P2 SSE 集成测试 —— 验证 {@code /api/ai/chat/stream} 端到端契约。
 *
 * <p>用 {@link HttpURLConnection}(非 JDK HttpClient)读 SSE 流;
 * HttpURLConnection 对 Tomcat chunked transfer encoding 的兼容性更好。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockChatModel.class)
@TestPropertySource(properties = {
        "spring.ai.providers.openai.api-key=mock-key"
})
class AiStreamIT extends IntegrationTestBase {

    private static final Pattern EVENT_LINE = Pattern.compile("^event: (\\S+)$", Pattern.MULTILINE);
    private static final Pattern DATA_LINE = Pattern.compile("^data: (.*)$", Pattern.MULTILINE);
    /** 从 JSON data payload 中提取 "deltaContent":"..." 的值 */
    private static final Pattern DELTA_CONTENT = Pattern.compile("\"deltaContent\":\"([^\"]*?)\"");

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
    }

    private String freshAccessToken() {
        String username = "stream_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                        "email", username + "@example.com",
                        "password", "secret123"),
                tools.jackson.databind.JsonNode.class);
        return auth.loginAccess(username, "secret123");
    }

    private static String sseRequestBody(String userText) {
        return """
                {"model":"openai:gpt-4o-mini",
                 "messages":[{"role":"USER","content":"%s"}]}
                """.formatted(userText);
    }

    private record SseResponse(int statusCode, String contentType, String body) {}

    /** 用 HttpURLConnection 读 SSE 响应 */
    private SseResponse postSse(String token, boolean useQuery, String userText) throws Exception {
        URI uri = URI.create("http://localhost:" + port
                + "/api/ai/chat/stream"
                + (useQuery ? "?access_token=" + token : ""));
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        conn.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (!useQuery) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        conn.getOutputStream().write(sseRequestBody(userText).getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        int status = conn.getResponseCode();
        String ct = conn.getHeaderField("Content-Type");

        // 手动逐行读,容忍 Premature EOF(Tomcat async 响应偶发)
        StringBuilder sb = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(
                status < 400 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
        } catch (java.io.IOException e) {
            // Premature EOF:已收到的数据在 sb 里
        } finally {
            conn.disconnect();
        }
        return new SseResponse(status, ct, sb.toString());
    }
    private static java.util.List<String> extractEvents(String body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = EVENT_LINE.matcher(body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static java.util.List<String> extractData(String body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = DATA_LINE.matcher(body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** 从 data 行(JSON payload)中提取所有 deltaContent 值并拼接 */
    private static String joinDeltaContent(java.util.List<String> dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : dataLines) {
            Matcher m = DELTA_CONTENT.matcher(line);
            while (m.find()) sb.append(m.group(1));
        }
        return sb.toString();
    }

    // ─── 用例 ──────────────────────────────────────────────────

    @Test
    @DisplayName("Bearer header:登录后 POST /api/ai/chat/stream → 200 + 完整 SSE 帧序列")
    void bearer_header_stream_returns_full_sse() throws Exception {
        String access = freshAccessToken();
        SseResponse resp = postSse(access, false, "hello");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.contentType()).startsWith("text/event-stream");

        // AiStreamController 走扁平 SSE 协议(AGENTS.md 约定):只发 data: <ChatChunk JSON>,
        // 不发 event: <name> 行(OpenAI 标准 data: [DONE] 也不发)。所以这里不检查
        // events.contains("delta" / "done"),只查 data 行里 deltaContent 拼出"echo:hello"。
        java.util.List<String> dataLines = extractData(resp.body());
        assertThat(dataLines).isNotEmpty();
        assertThat(joinDeltaContent(dataLines)).contains("echo:hello");
    }

    @Test
    @DisplayName("query token:登录后 POST /api/ai/chat/stream?access_token=... → 200 + 完整 SSE 帧序列")
    void query_token_stream_returns_full_sse() throws Exception {
        String access = freshAccessToken();
        SseResponse resp = postSse(access, true, "world");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.contentType()).startsWith("text/event-stream");

        java.util.List<String> dataLines = extractData(resp.body());
        assertThat(dataLines).isNotEmpty();
        assertThat(joinDeltaContent(dataLines)).contains("echo:world");
    }

    @Test
    @DisplayName("未带任何 token → 401")
    void unauthenticated_stream_returns_401() throws Exception {
        SseResponse resp = postSse(null, false, "hi");
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.body()).contains("\"code\":" + UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("Bearer 无效 token → 401")
    void invalid_bearer_stream_returns_401() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/api/ai/chat/stream");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        conn.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        conn.setRequestProperty("Authorization", "Bearer not-a-real-token");
        conn.getOutputStream().write(sseRequestBody("hi").getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        assertThat(conn.getResponseCode()).isEqualTo(401);
        conn.disconnect();
    }
}
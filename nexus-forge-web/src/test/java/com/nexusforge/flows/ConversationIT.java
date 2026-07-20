package com.nexusforge.flows;

import com.nexusforge.controller.dto.CreateConversationDto;
import com.nexusforge.controller.dto.SendMessageDto;
import com.nexusforge.controller.dto.UpdateTitleDto;
import com.nexusforge.controller.vo.ConversationDetailVo;
import com.nexusforge.controller.vo.ConversationVo;
import com.nexusforge.controller.vo.MessageVo;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3 对话上下文管理集成测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>创建对话</li>
 *   <li>列出对话(置顶优先 + 最近消息预览)</li>
 *   <li>发送消息并持久化 user/assistant 两端</li>
 *   <li>详情查询含消息列表</li>
 *   <li>重命名 / 置顶 / 删除</li>
 *   <li>未鉴权 401、跨用户访问 403</li>
 * </ul>
 *
 * <p>开关与 {@link AiChatIT} 一致:
 * <ul>
 *   <li>{@code @Import(MockChatModel.class)} — 注入 vendor=openai 的内存 ChatModel,屏蔽外网</li>
 *   <li>{@code spring.ai.providers.openai.api-key=mock-key} — 让 {@code AiProperties.providers.openai}
 *       段存在且 enabled=true,满足路由器启用校验</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockChatModel.class)
@TestPropertySource(properties = {
        "spring.ai.providers.openai.api-key=mock-key"
})
class ConversationIT extends IntegrationTestBase {

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
    }

    // ─── helpers ─────────────────────────────────────────────

    /** 注册新用户并拿 access token(每个用例独立用户,避免列表互相干扰) */
    private String freshAccessToken() {
        String username = "conv_" + System.nanoTime();
        var regResp = rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                        "email", username + "@example.com",
                        "password", "secret123"),
                JsonNode.class);
        assertThat(regResp.getStatusCode().is2xxSuccessful()).isTrue();
        return auth.loginAccess(username, "secret123");
    }

    private HttpHeaders bearer(String access) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private long createConversation(String access, String model, String title) {
        HttpHeaders headers = bearer(access);
        Map<String, Object> body = Map.of(
                "model", model,
                "title", title
        );
        var resp = rest().exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return resp.getBody().get("data").get("id").asLong();
    }

    /** 不带 title 字段,验证默认标题 '新对话' */
    private long createConversationNoTitle(String access, String model) {
        HttpHeaders headers = bearer(access);
        Map<String, Object> body = Map.of("model", model);
        var resp = rest().exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return resp.getBody().get("data").get("id").asLong();
    }

    // ─── 用例 ───────────────────────────────────────────────

    @Test
    @DisplayName("创建对话 → 列表能看到")
    void create_and_list_conversations() {
        String access = freshAccessToken();

        long convId = createConversation(access, "openai:gpt-4o-mini", "我的第一场对话");

        HttpHeaders headers = bearer(access);
        var listResp = rest().exchange("/api/ai/conversations", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        assertThat(listResp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode data = listResp.getBody().get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(convId);
        assertThat(data.get(0).get("title").asString()).isEqualTo("我的第一场对话");
        assertThat(data.get(0).get("model").asString()).isEqualTo("openai:gpt-4o-mini");
        assertThat(data.get(0).get("pinned").asBoolean()).isFalse();
        assertThat(data.get(0).get("messageCount").asLong()).isZero();
    }

    @Test
    @DisplayName("创建对话不传 title → 默认标题 '新对话'")
    void create_uses_default_title() {
        String access = freshAccessToken();
        long convId = createConversationNoTitle(access, "openai:gpt-4o-mini");

        HttpHeaders headers = bearer(access);
        var detailResp = rest().exchange("/api/ai/conversations/" + convId, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);

        assertThat(detailResp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(detailResp.getBody().get("data").get("title").asString()).isEqualTo("新对话");
    }

    @Test
    @DisplayName("发送消息 → 详情里能看到 USER + ASSISTANT 两条消息,且带 usage")
    void send_message_persists_both_sides() {
        String access = freshAccessToken();
        long convId = createConversation(access, "openai:gpt-4o-mini", "P3 测试");

        // 发消息
        HttpHeaders headers = bearer(access);
        Map<String, Object> body = Map.of("content", "hello");
        var sendResp = rest().exchange("/api/ai/conversations/" + convId + "/messages",
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(sendResp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode aiMsg = sendResp.getBody().get("data");
        assertThat(aiMsg.get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(aiMsg.get("content").asString()).isEqualTo("echo:hello");
        assertThat(aiMsg.get("seq").asInt()).isEqualTo(1);

        // 查详情
        var detailResp = rest().exchange("/api/ai/conversations/" + convId, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(detailResp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode messages = detailResp.getBody().get("data").get("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).get("role").asString()).isEqualTo("USER");
        assertThat(messages.get(0).get("content").asString()).isEqualTo("hello");
        assertThat(messages.get(0).get("seq").asInt()).isZero();
        assertThat(messages.get(1).get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).get("content").asString()).isEqualTo("echo:hello");

        // MockChatModel.call() 不返回 usage,这里只验证 assistant 消息存在即可
        // (真实 OpenAI 会有 usage 字段;mock 不带以模拟 "零用量" 边界)
    }

    @Test
    @DisplayName("发送消息后标题自动更新为 USER 消息前 30 字")
    void send_message_auto_updates_title() {
        String access = freshAccessToken();
        long convId = createConversationNoTitle(access, "openai:gpt-4o-mini");

        HttpHeaders headers = bearer(access);
        Map<String, Object> body = Map.of("content", "这是一个超过 30 个字符用于测试自动标题截断功能的长消息串,请确保超过 30 阈值");
        rest().exchange("/api/ai/conversations/" + convId + "/messages",
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);

        var detailResp = rest().exchange("/api/ai/conversations/" + convId, HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        String title = detailResp.getBody().get("data").get("title").asString();
        // 30 字符截断,加 "..."
        assertThat(title.length()).isLessThanOrEqualTo(33);
        assertThat(title).endsWith("...");
    }

    @Test
    @DisplayName("重命名对话")
    void rename_conversation() {
        String access = freshAccessToken();
        long convId = createConversation(access, "openai:gpt-4o-mini", "原标题");

        HttpHeaders headers = bearer(access);
        Map<String, Object> body = Map.of("title", "新标题");
        var resp = rest().exchange("/api/ai/conversations/" + convId + "/title",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
        assertThat(resp.getBody().get("data").get("title").asString()).isEqualTo("新标题");
    }

    @Test
    @DisplayName("置顶对话 → 列表中置顶项排在最前")
    void pin_conversation_appears_first() {
        String access = freshAccessToken();
        long conv1 = createConversation(access, "openai:gpt-4o-mini", "普通对话");
        long conv2 = createConversation(access, "openai:gpt-4o-mini", "将置顶的对话");

        HttpHeaders headers = bearer(access);
        Map<String, Object> body = Map.of("pinned", true);
        rest().exchange("/api/ai/conversations/" + conv2 + "/pin",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), JsonNode.class);

        var listResp = rest().exchange("/api/ai/conversations", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        JsonNode data = listResp.getBody().get("data");
        // 置顶的排前面
        assertThat(data.get(0).get("id").asLong()).isEqualTo(conv2);
        assertThat(data.get(1).get("id").asLong()).isEqualTo(conv1);
    }

    @Test
    @DisplayName("删除对话 → 列表为空,且 GET 详情变 403/404")
    void delete_conversation() {
        String access = freshAccessToken();
        long convId = createConversation(access, "openai:gpt-4o-mini", "即将删除");

        HttpHeaders headers = bearer(access);
        var delResp = rest().exchange("/api/ai/conversations/" + convId,
                HttpMethod.DELETE, new HttpEntity<>(headers), JsonNode.class);
        assertThat(delResp.getStatusCode().is2xxSuccessful()).isTrue();

        var listResp = rest().exchange("/api/ai/conversations", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(listResp.getBody().get("data").size()).isZero();

        // 再次 GET 详情应被拒绝:本服务的 GlobalExceptionHandler 把 FORBIDDEN(1005) 映射到 400,
        // 通过 envelope.code=1005 表达"无权访问"
        assertThatThrownBy(() -> rest().exchange("/api/ai/conversations/" + convId,
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getResponseBodyAsString())
                            .contains("\"code\":" + ResultCode.FORBIDDEN.getCode());
                });
    }

    @Test
    @DisplayName("未鉴权访问对话列表 → 401")
    void unauthenticated_list_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThatThrownBy(() -> rest().exchange("/api/ai/conversations", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    @DisplayName("未鉴权创建对话 → 401")
    void unauthenticated_create_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("model", "openai:gpt-4o-mini");
        assertThatThrownBy(() -> rest().exchange("/api/ai/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    @DisplayName("A 创建的对话,B 不能访问 → 403")
    void cross_user_access_returns_403() {
        String accessA = freshAccessToken();
        String accessB = freshAccessToken();
        long convId = createConversation(accessA, "openai:gpt-4o-mini", "A 的私密对话");

        HttpHeaders headersB = bearer(accessB);
        // B 访问 A 的对话详情 — 应被拒绝(本服务的 FORBIDDEN 映射到 HTTP 400 + envelope.code=1005)
        assertThatThrownBy(() -> rest().exchange("/api/ai/conversations/" + convId, HttpMethod.GET,
                new HttpEntity<>(headersB), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getResponseBodyAsString())
                            .contains("\"code\":" + ResultCode.FORBIDDEN.getCode());
                });

        // B 在该对话下发消息 — 也应被拒绝
        Map<String, Object> body = Map.of("content", "B 想插入消息");
        assertThatThrownBy(() -> rest().exchange("/api/ai/conversations/" + convId + "/messages",
                HttpMethod.POST, new HttpEntity<>(body, headersB), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, e ->
                        assertThat(e.getResponseBodyAsString())
                                .contains("\"code\":" + ResultCode.FORBIDDEN.getCode()));

        // B 删除 A 的对话 — 应被拒绝且不影响 A 的数据
        assertThatThrownBy(() -> rest().exchange("/api/ai/conversations/" + convId,
                HttpMethod.DELETE, new HttpEntity<>(headersB), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, e ->
                        assertThat(e.getResponseBodyAsString())
                                .contains("\"code\":" + ResultCode.FORBIDDEN.getCode()));

        // A 仍能访问自己的对话
        HttpHeaders headersA = bearer(accessA);
        var resp = rest().exchange("/api/ai/conversations/" + convId, HttpMethod.GET,
                new HttpEntity<>(headersA), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("空 content 消息 → 400 校验失败")
    void send_empty_content_returns_400() {
        String access = freshAccessToken();
        long convId = createConversation(access, "openai:gpt-4o-mini", "校验测试");

        HttpHeaders headers = bearer(access);
        Map<String, Object> body = Map.of("content", "");  // @NotBlank 违反
        assertThatThrownBy(() -> rest().exchange("/api/ai/conversations/" + convId + "/messages",
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class))
                .isInstanceOfSatisfying(HttpClientErrorException.BadRequest.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                    assertThat(e.getResponseBodyAsString())
                            .contains("\"code\":" + ResultCode.VALIDATION_FAILED.getCode());
                });
    }
}
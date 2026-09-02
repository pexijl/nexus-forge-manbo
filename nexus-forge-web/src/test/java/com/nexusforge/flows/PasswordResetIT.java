package com.nexusforge.flows;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.UserStatus;
import com.nexusforge.password.PasswordResetProperties;
import com.nexusforge.testsupport.IntegrationTestBase;
import com.nexusforge.testsupport.MailCapture;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.nexusforge.enums.ResultCode.INVALID_CREDENTIALS;
import static com.nexusforge.enums.ResultCode.RESET_CODE_INVALID;
import static com.nexusforge.enums.ResultCode.RESET_CODE_SEND_TOO_FREQUENT;
import static com.nexusforge.enums.ResultCode.RESET_CODE_TOO_MANY_ATTEMPTS;
import static com.nexusforge.enums.ResultCode.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 密码重置(邮箱验证码)端到端集成测试。
 *
 * <p>覆盖业务流:</p>
 * <ul>
 *   <li>happy path:注册 → 触发重置 → 读邮件验证码 → 确认改密 → 旧密码失败 + 新密码成功 + 旧 refresh 失效</li>
 *   <li>未知邮箱:200 OK + 不发邮件(防邮箱枚举)</li>
 *   <li>限流:同邮箱 60s 内两次,第二次 429 + RESET_CODE_SEND_TOO_FREQUENT</li>
 *   <li>验证码过期:TTL 缩短到 1s,过期后 confirm → 2013 RESET_CODE_INVALID</li>
 *   <li>被封禁用户:200 OK + 不发邮件</li>
 *   <li>失败次数超限:错码 5 次后,验证码被清,2014 RESET_CODE_TOO_MANY_ATTEMPTS</li>
 * </ul>
 *
 * <p>邮件读取走 {@link MailCapture} 扫 {@code build/dev-mail/},由
 * {@code LoggingEmailSender} 落盘;验证码从 HTML {@code <div class="code">NNNNNN</div>}
 * 用 regex 抓取。RedisCleaner 扩展清 {@code pwd:reset:*} 前缀。</p>
 *
 * <p>case "验证码过期" 用 {@link TestPropertySource} 覆盖
 * {@code password-reset.code-ttl-seconds=1},然后 {@code Thread.sleep(1500)}。
 * 其他 case 走默认配置(5 分钟 TTL,够用)。</p>
 */
@Tag("integration")
class PasswordResetIT extends IntegrationTestBase {

    @Autowired
    private MailCapture mailCapture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetProperties props;

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
        mailCapture.clear();
    }

    // ====================== happy path ======================

    @Test
    @DisplayName("happy path:register → 触发重置 → 读邮件 → confirm → 旧密码失败 + 新密码成功 + 旧 refresh 失效")
    void forgot_password_happy_path() {
        // 1. 注册 + 登录拿 access + refresh
        String username = "alice_" + System.nanoTime();
        String email = username + "@example.com";
        String oldPwd = "oldPass123";
        String newPwd = "newPass456";

        register(username, email, oldPwd);
        String[] tokens = auth.loginBoth(username, oldPwd);
        String oldAccess = tokens[0];
        String oldRefresh = tokens[1];

        // 2. 触发重置
        ResponseEntityOk(resetRequest(email));

        // 3. 读邮件拿 code
        MailCapture.Mail mail = mailCapture.latestTo(email);
        assertThat(mail.subject()).contains("重置你的 Nexus Forge 密码");
        assertThat(mail.code()).matches("\\d{6}");
        String code = mail.code();

        // 4. confirm 改密
        ResponseEntityOk(resetConfirm(email, code, newPwd));

        // 5. 旧密码登录失败(INVALID_CREDENTIALS)
        var badLogin = rest().postForEntity("/api/auth/login",
                Map.of("account", username, "password", oldPwd), JsonNode.class);
        assertThat(badLogin.getBody().get("code").asInt()).isEqualTo(INVALID_CREDENTIALS.getCode());

        // 6. 新密码登录成功
        String[] newTokens = auth.loginBoth(username, newPwd);
        assertThat(newTokens[0]).isNotBlank();

        // 7. 旧 refresh 调 /api/auth/refresh 应失败(TOKEN_VERSION_MISMATCH)
        // 因为 confirm 调了 logoutAllRefreshTokens 删了 userId 对应的 refresh version key
        // 注意:AuthController.refresh 把 AuthException 内部 catch 转 Result.fail(1006),
        // HTTP 仍 200,验 body code 即可
        var refreshOld = rest().postForEntity("/api/auth/refresh",
                Map.of("refreshToken", oldRefresh), JsonNode.class);
        assertThat(refreshOld.getStatusCode().value()).isEqualTo(200);
        assertThat(refreshOld.getBody().get("code").asInt())
                .isEqualTo(ResultCode.TOKEN_REFRESH_FAILED.getCode());
    }

    // ====================== 防枚举 ======================

    @Test
    @DisplayName("未知邮箱:200 OK + 邮件未生成(防邮箱枚举)")
    void forgot_password_unknown_email_returns_200_no_mail() {
        String ghostEmail = "ghost_" + System.nanoTime() + "@nowhere.com";

        ResponseEntityOk(resetRequest(ghostEmail));

        assertThat(mailCapture.countFor(ghostEmail)).isZero();
    }

    @Test
    @DisplayName("被封禁用户:200 OK + 邮件未生成")
    void forgot_password_banned_user_silently_200() {
        // 1. 注册并设为封禁(直接走 repo.save,无 admin API 暴露)
        String username = "eve_" + System.nanoTime();
        String email = username + "@example.com";
        register(username, email, "oldPass123");

        User u = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        u.setStatus(UserStatus.BANNED);
        userRepository.save(u);

        // 2. 触发重置 → 应静默
        ResponseEntityOk(resetRequest(email));

        assertThat(mailCapture.countFor(email)).isZero();
    }

    // ====================== 限流 ======================

    @Test
    @DisplayName("限流:同邮箱 60s 内两次 → 第二次 429 + RESET_CODE_SEND_TOO_FREQUENT")
    void forgot_password_rate_limited_after_repeated_request() {
        String username = "bob_" + System.nanoTime();
        String email = username + "@example.com";
        register(username, email, "oldPass123");

        // 第一次:200 + 邮件生成
        ResponseEntityOk(resetRequest(email));
        assertThat(mailCapture.countFor(email)).isEqualTo(1);

        // 第二次:立即重试 → 限流
        var rateLimited = assertThrows(HttpClientErrorException.TooManyRequests.class, () ->
                resetRequest(email));
        assertThat(rateLimited.getResponseBodyAsString())
                .contains("\"code\":" + RESET_CODE_SEND_TOO_FREQUENT.getCode());

        // 第二次不应再生成邮件
        assertThat(mailCapture.countFor(email)).isEqualTo(1);
    }

    // ====================== 验证码过期 ======================

    /**
     * 子类用 @TestPropertySource 覆盖 TTL 到 1 秒,触发"过期"分支。
     * @Nested 走外层类的 @SpringBootTest 上下文 + 独立 properties,
     * 不会与默认 TTL=300s 的外层测试共享 ApplicationContext。
     */
    @Nested
    @TestPropertySource(properties = "password-reset.code-ttl-seconds=1")
    class ExpiredCode {

        @Test
        @DisplayName("验证码过期:TTL=1s,过期后 confirm → 2013 RESET_CODE_INVALID")
        void forgot_password_expired_code_rejected() throws Exception {
            String username = "carol_" + System.nanoTime();
            String email = username + "@example.com";
            register(username, email, "oldPass123");

            ResponseEntityOk(resetRequest(email));
            MailCapture.Mail mail = mailCapture.latestTo(email);
            String code = mail.code();

            // 等待 1.5s 让 TTL 过期
            TimeUnit.MILLISECONDS.sleep(1500);

            // confirm 应失败
            var expired = assertThrows(HttpClientErrorException.class, () ->
                    resetConfirm(email, code, "newPass456"));
            assertThat(expired.getResponseBodyAsString())
                    .contains("\"code\":" + RESET_CODE_INVALID.getCode());
        }
    }

    // ====================== 失败次数超限 ======================

    @Test
    @DisplayName("失败次数 > 5:验证码被清,2014 RESET_CODE_TOO_MANY_ATTEMPTS")
    void forgot_password_attempts_exceeded_clears_code() throws java.io.IOException, InterruptedException {
        String username = "dave_" + System.nanoTime();
        String email = username + "@example.com";
        register(username, email, "oldPass123");

        ResponseEntityOk(resetRequest(email));
        String realCode = mailCapture.latestTo(email).code();

        // 用 JDK HttpClient 直接发,绕开 RestTemplate / Apache HttpClient 的 429 自动重试。
        // 第一次 429 响应拿到后,如果 client 重试,server 端 codeKey 已被清,返回 2013 INVALID,
        // assertThrows / status 断言看到的是最终 400 + 2013,完全误判。
        java.net.http.HttpClient jdkHttp = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest.Builder baseBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port
                        + "/api/auth/password/reset/confirm"))
                .header("Content-Type", "application/json");

        // 错 5 次:每次都返回 400 + 2013 INVALID;第 6 次 attempts=6,触发 429 + 2014
        for (int i = 0; i < props.getMaxAttempts(); i++) {
            var resp = jdkHttp.send(baseBuilder
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            String.format("{\"email\":\"%s\",\"code\":\"000000\",\"newPassword\":\"newPass456\"}",
                                    email)))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(400);
            assertThat(resp.body()).contains("\"code\":" + RESET_CODE_INVALID.getCode());
        }

        // 第 6 次(用真实 code 也无济于事,attempts 已超限):429 + 2014
        var locked = jdkHttp.send(baseBuilder
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        String.format("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"newPass456\"}",
                                email, realCode)))
                .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.body()).contains("\"code\":" + RESET_CODE_TOO_MANY_ATTEMPTS.getCode());

        // 验证码被清 —— 再用真实 code 试也是 400 + 2013 INVALID
        var afterLock = jdkHttp.send(baseBuilder
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        String.format("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"newPass456\"}",
                                email, realCode)))
                .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(afterLock.statusCode()).isEqualTo(400);
        assertThat(afterLock.body()).contains("\"code\":" + RESET_CODE_INVALID.getCode());
    }

    // ====================== helpers ======================

    private void register(String username, String email, String password) {
        var reg = rest().postForEntity("/api/auth/register",
                Map.of("username", username, "email", email, "password", password),
                JsonNode.class);
        assertThat(reg.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(reg.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
    }

    private org.springframework.http.ResponseEntity<JsonNode> resetRequest(String email) {
        return rest().postForEntity("/api/auth/password/reset/request",
                Map.of("email", email), JsonNode.class);
    }

    private org.springframework.http.ResponseEntity<JsonNode> resetConfirm(String email, String code, String newPwd) {
        return rest().postForEntity("/api/auth/password/reset/confirm",
                Map.of("email", email, "code", code, "newPassword", newPwd),
                JsonNode.class);
    }

    private void ResponseEntityOk(org.springframework.http.ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
    }
}

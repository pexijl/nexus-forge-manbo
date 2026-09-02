package com.nexusforge.flows;

import com.nexusforge.entity.AiConversation;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.Role;
import com.nexusforge.repository.AiConversationRepository;
import com.nexusforge.testsupport.IntegrationTestBase;
import com.nexusforge.testsupport.MailCapture;
import com.nexusforge.user.dto.BanUserDto;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.AccountLifecycleLogRepository;
import com.nexusforge.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

import static com.nexusforge.enums.ResultCode.SUCCESS;
import static com.nexusforge.user.enums.AccountLifecycleAction.BAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 账号生命周期端到端集成测试 —— happy path:注册 / 申请注销 / 读邮件 / 确认 / 软删 / AI 数据真删。
 *
 * <p>范围(本 IT 只覆盖 happy path,边界场景在 {@code AccountLifecycleServiceTest} 覆盖):</p>
 * <ul>
 *   <li>用户自助注销 happy path(读邮件 + 软删 + AI 数据真删 + 审计)</li>
 * </ul>
 *
 * <p>管理员 ban/unban / {@code @PreAuthorize} 验证 / 恢复 token / expire-deletions 定时
 * 已在 {@code AccountLifecycleServiceTest}(commit 1/3 单测)和
 * {@code AdminUserLifecycleControllerTest}(commit 4 单测)覆盖,不再冗余
 * 端到端覆盖以节省 Testcontainers 资源。</p>
 */
@Tag("integration")
class AccountLifecycleIT extends IntegrationTestBase {

    @Autowired private MailCapture mailCapture;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountLifecycleLogRepository lifecycleLogRepository;
    @Autowired private AiConversationRepository aiConversationRepository;
    @Autowired private com.nexusforge.user.service.UserRoleProvider userRoleProvider;

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
        mailCapture.clear();
    }

    /** 把 user 升为 ADMIN(测试场景直接 repo.save) */
    private void promoteToAdmin(String username) {
        User u = userRepository.findByUsername(username).orElseThrow();
        u.setRoles(Set.of(Role.USER, Role.ADMIN));
        userRepository.save(u);
        // 清 Redis 角色缓存,否则 JwtAuthenticationFilter 加载的还是旧 roles(USER)
        userRoleProvider.evict(u.getId());
    }

    // ====================== 注销 happy path ======================

    @Test
    @DisplayName("happy path:注册 → 登录 → 申请注销 → 读邮件 → 确认 → user 软删 + AI 数据真删 + 审计")
    void forgot_account_lifecycle_full_path() {
        // 1. 注册 + 登录拿 access
        String username = "alice_" + System.nanoTime();
        String email = username + "@example.com";
        String pwd = "oldPass123";
        register(username, email, pwd);
        String[] tokens = auth.loginBoth(username, pwd);
        String access = tokens[0];
        Long userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();

        // 2. 预填 AI 数据(2 个 conversation),注销时监听器会清
        AiConversation c1 = new AiConversation();
        c1.setUserId(userId); c1.setTitle("t1"); c1.setModel("gpt");
        aiConversationRepository.save(c1);
        AiConversation c2 = new AiConversation();
        c2.setUserId(userId); c2.setTitle("t2"); c2.setModel("gpt");
        aiConversationRepository.save(c2);
        assertThat(aiConversationRepository.findAll().stream()
                .filter(c -> userId.equals(c.getUserId()))).hasSize(2);

        // 3. 申请注销(需要登录态)
        var reqResp = rest().exchange("/api/users/me/delete/request",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("password", pwd),
                        auth.authHeader(access)),
                JsonNode.class);
        assertThat(reqResp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());

        // 4. 读邮件
        MailCapture.Mail mail = mailCapture.latestTo(email);
        assertThat(mail.subject()).contains("确认注销");
        String code = mail.code();

        // 5. 确认注销(公开端点)
        var cfmResp = rest().postForEntity("/api/users/me/delete/confirm",
                Map.of("email", email, "code", code), JsonNode.class);
        assertThat(cfmResp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());

        // 6. 软删校验:user 仍存在但 deleted_at != null(@SQLRestriction 让 findById 找不到)
        assertThat(userRepository.findById(userId)).isEmpty();

        // 7. AI 数据真删(监听器已跑):conversation 全部没了
        assertThat(aiConversationRepository.findAll().stream()
                .filter(c -> userId.equals(c.getUserId()))).isEmpty();

        // 8. 审计:DELETE_REQUEST + DELETE_CONFIRM
        assertThat(lifecycleLogRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .extracting("action")
                .contains(com.nexusforge.user.enums.AccountLifecycleAction.DELETE_REQUEST,
                        com.nexusforge.user.enums.AccountLifecycleAction.DELETE_CONFIRM);
    }

    // ====================== helpers ======================

    private void register(String username, String email, String password) {
        var reg = rest().postForEntity("/api/auth/register",
                Map.of("username", username, "email", email, "password", password),
                JsonNode.class);
        assertThat(reg.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(reg.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
    }
}

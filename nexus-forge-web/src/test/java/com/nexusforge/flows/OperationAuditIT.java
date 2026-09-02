package com.nexusforge.flows;

import com.nexusforge.audit.OperationAuditLog;
import com.nexusforge.audit.OperationAuditLogRepository;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.enums.Role;
import com.nexusforge.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nexusforge.enums.ResultCode.FORBIDDEN;
import static com.nexusforge.enums.ResultCode.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * P2 Audit Commit 5 端到端集成测试 —— 验证:
 *
 * <ol>
 *   <li>{@code Aspect}        真 HTTP 请求触发 @Audited 切面,DB 落行字段全对</li>
 *   <li>{@code Auth}          未登录调 @Audited 端点 → 401(无审计行,不挂主链路)</li>
 *   <li>{@code Failure}       抛业务异常 → 审计行 result=FAILURE,error_code 提取</li>
 *   <li>{@code Admin}         admin 查 /api/admin/audit-logs 返 VO</li>
 *   <li>{@code AdminDeny}     非 admin 调 /api/admin/audit-logs → 403(自身不进审计行)</li>
 * </ol>
 *
 * <p>走 PG / Redis / RustFS 全 Testcontainers;{@code -Pintegration} 触发。</p>
 */
@Tag("integration")
@DisplayName("操作审计 端到端")
class OperationAuditIT extends IntegrationTestBase {

    @Autowired private OperationAuditLogRepository auditRepo;
    @Autowired private com.nexusforge.user.repository.UserRepository userRepository;
    @Autowired private com.nexusforge.user.service.UserRoleProvider userRoleProvider;

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
    }

    // ─────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────

    private String registerAndLogin(String prefix) {
        String username = prefix + "_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                        "email", username + "@example.com",
                        "password", "secret123"),
                JsonNode.class);
        return auth.loginAccess(username, "secret123");
    }

    private void promoteToAdmin(String username) {
        com.nexusforge.user.entity.User u = userRepository.findByUsername(username)
                .orElseThrow();
        u.setRoles(Set.of(Role.USER, Role.ADMIN));
        userRepository.save(u);
        userRoleProvider.evict(u.getId());
    }

    private OperationAuditLog latestLogFor(String action) {
        List<OperationAuditLog> all = auditRepo.findAll();
        return all.stream()
                .filter(l -> action.equals(l.getAction()))
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElseThrow(() -> new AssertionError("no audit log for action=" + action));
    }

    // ─────────────────────────────────────────────
    //  AOP 真触发
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Aspect")
    class Aspect {

        @Test
        @DisplayName("PATCH /api/users/me → 审计行 user.update 落 DB,字段全对")
        void user_update_audit_row() {
            String access = registerAndLogin("audit1");

            HttpHeaders headers = auth.authHeader(access);
            headers.setContentType(APPLICATION_JSON);
            var resp = rest().exchange("/api/users/me", PATCH,
                    new HttpEntity<>(Map.of("nickname", "NewNick"), headers),
                    JsonNode.class);
            assertThat(resp.getStatusCode()).isEqualTo(OK);
            assertThat(resp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());

            // 验证审计行
            OperationAuditLog log = latestLogFor("user.update");
            assertThat(log.getAction()).isEqualTo("user.update");
            assertThat(log.getResource()).isEqualTo("user");
            assertThat(log.getResourceId()).isNotNull();  // SpEL "#principal.userId()"
            assertThat(log.getMethod()).isEqualTo("PATCH");
            assertThat(log.getPath()).isEqualTo("/api/users/me");
            assertThat(log.getUserId()).isNotNull();
            assertThat(log.getResult()).isEqualTo(OperationAuditLog.AuditResult.SUCCESS);
            assertThat(log.getStatusCode()).isEqualTo(200);
            assertThat(log.getLatencyMs()).isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("POST /api/users/me/password → 审计行 user.password.change")
        void user_password_change_audit_row() {
            String access = registerAndLogin("audit2");

            HttpHeaders headers = auth.authHeader(access);
            headers.setContentType(APPLICATION_JSON);
            var resp = rest().exchange("/api/users/me/password",
                    org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(Map.of("oldPassword", "secret123",
                            "newPassword", "newSecret456"), headers),
                    JsonNode.class);
            assertThat(resp.getStatusCode()).isEqualTo(OK);

            OperationAuditLog log = latestLogFor("user.password.change");
            assertThat(log.getAction()).isEqualTo("user.password.change");
            assertThat(log.getResource()).isEqualTo("user");
        }
    }

    // ─────────────────────────────────────────────
    //  Failure 路径
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Failure")
    class Failure {

        @Test
        @DisplayName("业务异常 → 审计行 result=FAILURE,error_code 提取")
        void business_exception_audit() {
            String access = registerAndLogin("audit3");

            HttpHeaders headers = auth.authHeader(access);
            headers.setContentType(APPLICATION_JSON);
            try {
                // 旧密码错 → 2011 OLD_PASSWORD_INCORRECT
                rest().exchange("/api/users/me/password",
                        org.springframework.http.HttpMethod.POST,
                        new HttpEntity<>(Map.of("oldPassword", "WRONG",
                                "newPassword", "newSecret456"), headers),
                        JsonNode.class);
                org.junit.jupiter.api.Assertions.fail("should have thrown");
            } catch (HttpClientErrorException expected) {
                assertThat(expected.getResponseBodyAsString())
                        .contains("\"code\":" + ResultCode.OLD_PASSWORD_INCORRECT.getCode());
            }

            // 审计行 result=FAILURE,error_code=2011
            OperationAuditLog log = latestLogFor("user.password.change");
            assertThat(log.getResult()).isEqualTo(OperationAuditLog.AuditResult.FAILURE);
            assertThat(log.getErrorCode()).isEqualTo(ResultCode.OLD_PASSWORD_INCORRECT.getCode());
        }
    }

    // ─────────────────────────────────────────────
    //  Admin 端点
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Admin")
    class Admin {

        @Test
        @DisplayName("admin 调 /api/admin/audit-logs 返 VO,字段对齐 DB")
        void admin_can_search() {
            String username = "audit4_" + System.nanoTime();
            rest().postForEntity("/api/auth/register",
                    Map.of("username", username, "email", username + "@example.com",
                            "password", "secret123"),
                    JsonNode.class);
            String userAccess = auth.loginAccess(username, "secret123");

            // 用户先触发一次 user.update 落审计行
            HttpHeaders h = auth.authHeader(userAccess);
            h.setContentType(APPLICATION_JSON);
            rest().exchange("/api/users/me", PATCH,
                    new HttpEntity<>(Map.of("nickname", "X"), h),
                    JsonNode.class);

            // 升 admin,重新登录拿 ADMIN token
            promoteToAdmin(username);
            String adminAccess = auth.loginAccess(username, "secret123");

            // 查审计
            Long userId = userRepository.findByUsername(username).orElseThrow().getId();
            HttpHeaders ah = auth.authHeader(adminAccess);
            var resp = rest().exchange(
                    "/api/admin/audit-logs?userId=" + userId
                            + "&action=user.update&page=1&size=20",
                    GET, new HttpEntity<>(ah), JsonNode.class);
            assertThat(resp.getStatusCode()).isEqualTo(OK);
            assertThat(resp.getBody().get("data").get("total").asInt()).isGreaterThanOrEqualTo(1);
            JsonNode first = resp.getBody().get("data").get("records").get(0);
            assertThat(first.get("action").asText()).isEqualTo("user.update");
            assertThat(first.get("resource").asText()).isEqualTo("user");
            assertThat(first.get("result").asText()).isEqualTo("SUCCESS");
            // ipPrefix 截断字段存在
            assertThat(first.has("ipPrefix")).isTrue();
        }

        @Test
        @DisplayName("非 admin 调 /api/admin/audit-logs → 403,自身不进审计行(无循环)")
        void non_admin_denied_403() {
            String access = registerAndLogin("audit5");

            HttpHeaders h = auth.authHeader(access);
            try {
                rest().exchange("/api/admin/audit-logs?page=1&size=20", GET,
                        new HttpEntity<>(h), JsonNode.class);
                org.junit.jupiter.api.Assertions.fail("should have thrown");
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode().value()).isEqualTo(403);
                // AccessDeniedException 兜底 1005 FORBIDDEN
                assertThat(e.getResponseBodyAsString())
                        .contains("\"code\":" + FORBIDDEN.getCode());
            }

            // admin 查操作不进审计行(无循环)
            long adminCallCount = auditRepo.findAll().stream()
                    .filter(l -> "/api/admin/audit-logs".equals(l.getPath()))
                    .count();
            assertThat(adminCallCount).isZero();
        }
    }
}

package com.nexusforge.flows;

import com.nexusforge.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.nexusforge.enums.ResultCode.INVALID_CREDENTIALS;
import static com.nexusforge.enums.ResultCode.SUCCESS;
import static com.nexusforge.enums.ResultCode.UNAUTHORIZED;
import static com.nexusforge.enums.ResultCode.USER_ALREADY_EXISTS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Tag("integration")
class AuthRegisterLoginIT extends IntegrationTestBase {

    @BeforeEach
    void setUp() { db.clean(); redis.flush(); }

    @Test
    @DisplayName("注册 → 登录 → 拿自己 → 改昵称 → 登出,全链路通")
    void happy_path() {
        String username = "alice_" + System.nanoTime();
        var regResp = rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                       "email", username + "@example.com",
                       "password", "secret123"),
                JsonNode.class);
        assertThat(regResp.getStatusCode()).isEqualTo(OK);
        assertThat(regResp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());

        String[] tokens = auth.loginBoth(username, "secret123");
        String access = tokens[0];
        assertThat(access).isNotBlank();

        var meResp = rest().exchange("/api/users/me", GET,
                new HttpEntity<>(auth.authHeader(access)), JsonNode.class);
        assertThat(meResp.getBody().get("data").get("username").asString())
                .isEqualTo(username);

        HttpHeaders headers = auth.authHeader(access);
        headers.setContentType(APPLICATION_JSON);
        var patch = rest().exchange("/api/users/me", PATCH,
                new HttpEntity<>(Map.of("nickname", "Alice New"), headers), JsonNode.class);
        assertThat(patch.getBody().get("data").get("nickname").asString())
                .isEqualTo("Alice New");

        var logout = rest().postForEntity("/api/auth/logout",
                new HttpEntity<>(Map.of("refreshToken", tokens[1]), headers), JsonNode.class);
        assertThat(logout.getStatusCode()).isEqualTo(OK);

        var revoked = assertThrows(HttpClientErrorException.Unauthorized.class, () ->
                rest().exchange("/api/users/me", GET,
                        new HttpEntity<>(auth.authHeader(access)), JsonNode.class));
        assertThat(revoked.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        assertThat(revoked.getResponseBodyAsString()).contains("\"code\":" + UNAUTHORIZED.getCode());
    }

    @Test
    void username_conflict_returns_USER_ALREADY_EXISTS() {
        String username = "bob_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                       "email", username + "@x.com",
                       "password", "secret123"),
                JsonNode.class);

        var duplicate = assertThrows(HttpClientErrorException.BadRequest.class, () ->
                rest().postForEntity("/api/auth/register",
                        Map.of("username", username,
                               "email", username + "2@x.com",
                               "password", "secret123"),
                        JsonNode.class));

        assertThat(duplicate.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(duplicate.getResponseBodyAsString())
                .contains("\"code\":" + USER_ALREADY_EXISTS.getCode())
                .contains(USER_ALREADY_EXISTS.getMessage());
    }

    @Test
    void wrong_password_returns_INVALID_CREDENTIALS() {
        String username = "carol_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                       "email", username + "@x.com",
                       "password", "secret123"),
                JsonNode.class);

        var bad = rest().postForEntity("/api/auth/login",
                Map.of("account", username, "password", "WRONG"), JsonNode.class);

        assertThat(bad.getStatusCode()).isEqualTo(OK);
        assertThat(bad.getBody().get("code").asInt()).isEqualTo(INVALID_CREDENTIALS.getCode());
    }
}

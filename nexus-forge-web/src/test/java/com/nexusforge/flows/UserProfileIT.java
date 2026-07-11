package com.nexusforge.flows;

import com.nexusforge.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.nexusforge.enums.ResultCode.INVALID_CREDENTIALS;
import static com.nexusforge.enums.ResultCode.OLD_PASSWORD_INCORRECT;
import static com.nexusforge.enums.ResultCode.SUCCESS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Tag("integration")
class UserProfileIT extends IntegrationTestBase {

    @BeforeEach
    void setUp() { db.clean(); redis.flush(); }

    @Test
    void no_token_returns_401() {
        var unauthorized = assertThrows(HttpClientErrorException.Unauthorized.class, () ->
                rest().getForEntity("/api/users/me", JsonNode.class));

        assertThat(unauthorized.getStatusCode()).isEqualTo(UNAUTHORIZED);
        assertThat(unauthorized.getResponseBodyAsString())
                .contains("\"code\":" + com.nexusforge.enums.ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    void forged_token_returns_401() {
        var unauthorized = assertThrows(HttpClientErrorException.Unauthorized.class, () ->
                rest().exchange("/api/users/me", GET,
                        new HttpEntity<>(auth.authHeader("eyJhbGciOiJIUzI1NiJ9.fake.signature")),
                        JsonNode.class));

        assertThat(unauthorized.getStatusCode()).isEqualTo(UNAUTHORIZED);
        assertThat(unauthorized.getResponseBodyAsString())
                .contains("\"code\":" + com.nexusforge.enums.ResultCode.UNAUTHORIZED.getCode());
    }

    @Test
    void change_password_requires_correct_old_password() {
        String username = "hank_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                       "email", username + "@example.com",
                       "password", "secret123"),
                JsonNode.class);
        String access = auth.loginAccess(username, "secret123");
        HttpHeaders headers = auth.authHeader(access);
        headers.setContentType(APPLICATION_JSON);

        var wrongOldPassword = assertThrows(HttpClientErrorException.BadRequest.class, () ->
                rest().postForEntity("/api/users/me/password",
                        new HttpEntity<>(Map.of("oldPassword", "WRONG",
                                               "newPassword", "newSecret456"), headers),
                        JsonNode.class));
        assertThat(wrongOldPassword.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(wrongOldPassword.getResponseBodyAsString())
                .contains("\"code\":" + OLD_PASSWORD_INCORRECT.getCode())
                .contains(OLD_PASSWORD_INCORRECT.getMessage());

        var changed = rest().postForEntity("/api/users/me/password",
                new HttpEntity<>(Map.of("oldPassword", "secret123",
                                       "newPassword", "newSecret456"), headers),
                JsonNode.class);
        assertThat(changed.getStatusCode()).isEqualTo(OK);
        assertThat(changed.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());

        var oldPasswordLogin = rest().postForEntity("/api/auth/login",
                Map.of("account", username, "password", "secret123"), JsonNode.class);
        assertThat(oldPasswordLogin.getStatusCode()).isEqualTo(OK);
        assertThat(oldPasswordLogin.getBody().get("code").asInt())
                .isEqualTo(INVALID_CREDENTIALS.getCode());

        var newPasswordLogin = rest().postForEntity("/api/auth/login",
                Map.of("account", username, "password", "newSecret456"), JsonNode.class);
        assertThat(newPasswordLogin.getStatusCode()).isEqualTo(OK);
        assertThat(newPasswordLogin.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
        assertThat(newPasswordLogin.getBody().get("data").get("access").get("token").asString())
                .isNotBlank();
    }
}
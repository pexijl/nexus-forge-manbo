package com.nexusforge.flows;

import com.nexusforge.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static com.nexusforge.enums.ResultCode.TOKEN_REFRESH_FAILED;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.springframework.http.HttpStatus.OK;

@Tag("integration")
class AuthRefreshIT extends IntegrationTestBase {

    @BeforeEach
    void setUp() { db.clean(); redis.flush(); }

    private String[] registerAndLoginBoth(String usernamePrefix) {
        String username = usernamePrefix + "_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                Map.of("username", username,
                       "email", username + "@example.com",
                       "password", "secret123"),
                JsonNode.class);
        return auth.loginBoth(username, "secret123");
    }

    @Test
    void refresh_returns_new_access_and_refresh() {
        String[] tokens = registerAndLoginBoth("dan");

        var refreshed = rest().postForEntity("/api/auth/refresh",
                Map.of("refreshToken", tokens[1]), JsonNode.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(OK);
        assertThat(refreshed.getBody().get("code").asInt()).isEqualTo(200);
        assertThat(refreshed.getBody().get("data").get("access").get("token").asString())
                .isNotEqualTo(tokens[0]);
        assertThat(refreshed.getBody().get("data").get("refresh").get("token").asString())
                .isNotEqualTo(tokens[1]);
    }

    @Test
    void used_refresh_cannot_be_reused() {
        String[] tokens = registerAndLoginBoth("ed");
        rest().postForEntity("/api/auth/refresh",
                Map.of("refreshToken", tokens[1]), JsonNode.class);

        var replay = rest().postForEntity("/api/auth/refresh",
                Map.of("refreshToken", tokens[1]), JsonNode.class);

        assertThat(replay.getStatusCode()).isEqualTo(OK);
        assertThat(replay.getBody().get("code").asInt()).isEqualTo(TOKEN_REFRESH_FAILED.getCode());
    }

    @Test
    void access_type_token_cannot_be_used_to_refresh() {
        String[] tokens = registerAndLoginBoth("fran");

        var wrong = rest().postForEntity("/api/auth/refresh",
                Map.of("refreshToken", tokens[0]), JsonNode.class);

        assertThat(wrong.getStatusCode()).isEqualTo(OK);
        assertThat(wrong.getBody().get("code").asInt()).isEqualTo(TOKEN_REFRESH_FAILED.getCode());
        assertThat(wrong.getBody().get("message").asString()).contains("Token 类型错误");
    }
}
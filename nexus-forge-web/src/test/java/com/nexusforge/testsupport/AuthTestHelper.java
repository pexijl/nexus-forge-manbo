package com.nexusforge.testsupport;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 测试辅助:封装"注册 → 登录 → 拿 token"的样板。
 * 非 @Component —— 由 IntegrationTestBase.auth() 在 @BeforeEach 里构建并暴露。
 */
public class AuthTestHelper {

    private final RestTemplate rest;

    public AuthTestHelper(RestTemplate rest) {
        this.rest = rest;
    }

    /** 注册 + 登录,返回 access token */
    public String registerAndLogin(String usernamePrefix) {
        String username = usernamePrefix + "_" + System.nanoTime();
        rest.postForEntity("/api/auth/register",
                Map.of("username", username,
                       "email", username + "@example.com",
                       "password", "secret123"),
                JsonNode.class);
        return loginAccess(username, "secret123");
    }

    public String loginAccess(String account, String password) {
        ResponseEntity<JsonNode> r = rest.postForEntity("/api/auth/login",
                Map.of("account", account, "password", password),
                JsonNode.class);
        JsonNode body = r.getBody();
        if (body == null) throw new IllegalStateException("login response body null");
        return body.get("data").get("access").get("token").asString();
    }

    /** 返回 [access, refresh] */
    public String[] loginBoth(String account, String password) {
        ResponseEntity<JsonNode> r = rest.postForEntity("/api/auth/login",
                Map.of("account", account, "password", password),
                JsonNode.class);
        JsonNode b = r.getBody();
        if (b == null) throw new IllegalStateException("login response body null");

        return new String[]{
                b.get("data").get("access").get("token").asString(),
                b.get("data").get("refresh").get("token").asString()
        };
    }

    public HttpHeaders authHeader(String access) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        return h;
    }
}
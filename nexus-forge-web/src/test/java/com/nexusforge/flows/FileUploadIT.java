package com.nexusforge.flows;

import com.nexusforge.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static com.nexusforge.enums.ResultCode.FILE_TOO_LARGE;
import static com.nexusforge.enums.ResultCode.SUCCESS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.CONTENT_TOO_LARGE;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

@Tag("integration")
class FileUploadIT extends IntegrationTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() { db.clean(); redis.flush(); }

    @Test
    void upload_avatar_then_me_returns_url_that_can_be_fetched() throws Exception {
        String access = auth.registerAndLogin("ivy");
        byte[] png = Files.readAllBytes(Path.of("src/test/resources/fixtures/avatar.png"));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(png) {
            @Override public String getFilename() { return "avatar.png"; }
        });

        HttpHeaders headers = auth.authHeader(access);
        headers.setContentType(MULTIPART_FORM_DATA);

        var upload = rest().exchange("/api/users/me/avatar", POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(upload.getStatusCode()).isEqualTo(OK);
        assertThat(upload.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
        assertThat(upload.getBody().get("data").get("avatarUrl").asString())
                .isNotBlank()
                .startsWith(rustfsEndpoint());

        var me = rest().exchange("/api/users/me", GET,
                new HttpEntity<>(auth.authHeader(access)), JsonNode.class);
        String avatarUrl = me.getBody().get("data").get("avatarUrl").asString();
        assertThat(avatarUrl).isNotBlank().startsWith(rustfsEndpoint());
        assertThat(fetchBytes(avatarUrl)).isEqualTo(png);
    }

    @Test
    void oversize_file_returns_413() throws Exception {
        String access = auth.registerAndLogin("jay");
        byte[] tooBig = new byte[6 * 1024 * 1024];
        String boundary = "----nexus-forge-test-" + System.nanoTime();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/users/me/avatar"))
                .header("Authorization", "Bearer " + access)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipartBody(boundary, "file", "big.png", tooBig)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(CONTENT_TOO_LARGE.value());
        assertThat(response.body()).contains("\"code\":" + FILE_TOO_LARGE.getCode());
    }

    @Test
    void delete_avatar_resets_url() {
        String access = auth.registerAndLogin("kate");
        HttpHeaders headers = auth.authHeader(access);

        var deleted = rest().exchange("/api/users/me/avatar", DELETE,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(deleted.getStatusCode()).isEqualTo(OK);
        assertThat(deleted.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());

        JsonNode avatarUrl = deleted.getBody().get("data").get("avatarUrl");
        assertThat(avatarUrl == null || avatarUrl.isNull() || avatarUrl.asString().isEmpty()).isTrue();
    }

    private static byte[] fetchBytes(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(OK.value());
        return response.body();
    }

    private static byte[] multipartBody(String boundary, String fieldName, String filename, byte[] content) {
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(prefix.length + content.length + suffix.length);
        out.writeBytes(prefix);
        out.writeBytes(content);
        out.writeBytes(suffix);
        return out.toByteArray();
    }
}
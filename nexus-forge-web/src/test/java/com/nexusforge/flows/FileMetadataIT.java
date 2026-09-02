package com.nexusforge.flows;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import com.nexusforge.file.repository.FileMetadataRepository;
import com.nexusforge.testsupport.IntegrationTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static com.nexusforge.enums.ResultCode.FILE_FORBIDDEN;
import static com.nexusforge.enums.ResultCode.SUCCESS;
import static com.nexusforge.enums.ResultCode.UNAUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

/**
 * P2 Commit 5:文件元数据端到端集成测试。
 *
 * <p>基础设施复用 {@link IntegrationTestBase}:Testcontainers (PG / Redis / RustFS) +
 * {@code -Pintegration=true} 触发。</p>
 *
 * <h3>覆盖矩阵</h3>
 * <ol>
 *   <li>{@code UploadFlow}        注册 → 上传(AVATAR)→ DB 落 ACTIVE 行 → {@code /mine} 看到</li>
 *   <li>{@code UploadFlow}        biz 必填,缺失返 400</li>
 *   <li>{@code PresignedFlow}     presigned/put 写 PENDING → confirm 翻 ACTIVE</li>
 *   <li>{@code AdminSearch}       admin 看 owner 的所有 biz / status 维度文件</li>
 *   <li>{@code AdminSearch}       非 admin 调 /admin 返 403</li>
 *   <li>{@code SoftDelete}        owner 软删自己的文件,DB 翻 status=DELETED;{@code /mine} 不见</li>
 *   <li>{@code SoftDelete}        非 owner 软删返 FILE_FORBIDDEN</li>
 *   <li>{@code ConfirmEdge}       confirm 不存在的 key → FILE_NOT_FOUND</li>
 * </ol>
 *
 * <p>GDPR 真删路径(注销 → file rows 真删)走
 * {@code AccountLifecycleIT.forgot_account_lifecycle_full_path},
 * 那里 publish {@code UserDataDeletionEvent} 触发本模块的 listener,本 IT
 * 不再冗余端到端覆盖。</p>
 */
@Tag("integration")
@DisplayName("file_metadata 端到端")
class FileMetadataIT extends IntegrationTestBase {

    @Autowired private FileMetadataRepository fileRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        db.clean();
        redis.flush();
    }

    // ─────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────

    private String[] registerAndLogin(String prefix) {
        String username = prefix + "_" + System.nanoTime();
        rest().postForEntity("/api/auth/register",
                java.util.Map.of("username", username,
                        "email", username + "@example.com",
                        "password", "secret123"),
                JsonNode.class);
        return auth.loginBoth(username, "secret123");
    }

    /**
     * 上传文件(biz 必填),返回 metadata id。
     */
    private Long uploadFile(String access, String filename, byte[] content, String biz) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        });
        body.add("biz", biz);
        HttpHeaders h = auth.authHeader(access);
        h.setContentType(MULTIPART_FORM_DATA);
        var resp = rest().exchange("/api/files/upload", POST,
                new HttpEntity<>(body, h), JsonNode.class);
        assertThat(resp.getStatusCode()).isEqualTo(OK);
        assertThat(resp.getBody().get("code").asInt()).isEqualTo(SUCCESS.getCode());
        return resp.getBody().get("data").get("id").asLong();
    }

    // ─────────────────────────────────────────────
    //  Cases
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("UploadFlow")
    class UploadFlow {

        @Test
        @DisplayName("上传 AVATAR → DB 落 ACTIVE 行 → /mine 可见")
        void upload_avatar_persists_and_lists() {
            String access = registerAndLogin("upload1")[0];
            byte[] data = "hello avatar".getBytes();

            Long id = uploadFile(access, "avatar.png", data, "AVATAR");
            assertThat(id).isPositive();

            // DB 验证
            FileMetadata row = fileRepo.findById(id).orElseThrow();
            assertThat(row.getStatus()).isEqualTo(FileStatus.ACTIVE);
            assertThat(row.getOwnerId()).isPositive();
            assertThat(row.getBizType().name()).isEqualTo("AVATAR");
            assertThat(row.getSizeBytes()).isEqualTo(data.length);
            assertThat(row.getConfirmedAt()).isNotNull();

            // /mine 端点
            HttpHeaders h = auth.authHeader(access);
            var mine = rest().exchange("/api/files/mine?page=1&size=20", GET,
                    new HttpEntity<>(h), JsonNode.class);
            assertThat(mine.getStatusCode()).isEqualTo(OK);
            assertThat(mine.getBody().get("data").get("total").asInt()).isEqualTo(1);
            assertThat(mine.getBody().get("data").get("records").get(0).get("id").asLong()).isEqualTo(id);
            assertThat(mine.getBody().get("data").get("records").get(0).get("status").asText())
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("biz 缺失:Spring Validation 返 400")
        void missing_biz_returns_400() {
            String access = registerAndLogin("upload2")[0];
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource("x".getBytes()) {
                @Override public String getFilename() { return "x.png"; }
            });
            // biz 不传
            HttpHeaders h = auth.authHeader(access);
            h.setContentType(MULTIPART_FORM_DATA);
            // 4xx 走 error handler,HttpClientErrorException 是 RuntimeException
            try {
                rest().exchange("/api/files/upload", POST, new HttpEntity<>(body, h), JsonNode.class);
                // 不会到这里
                org.junit.jupiter.api.Assertions.fail("should have thrown");
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode().value()).isBetween(400, 499);
            }
        }
    }

    @Nested
    @DisplayName("PresignedFlow")
    class PresignedFlow {

        @Test
        @DisplayName("presigned/put 写 PENDING → confirm 翻 ACTIVE")
        void presigned_put_then_confirm_flips_to_active() throws java.net.URISyntaxException {
            String access = registerAndLogin("pre1")[0];
            HttpHeaders h = auth.authHeader(access);

            // 1. 颁 presigned PUT URL(biz=ATTACHMENT,filename=report.pdf)
            var presignResp = rest().exchange(
                    "/api/files/presigned/put?biz=ATTACHMENT&filename=report.pdf&expirySeconds=600",
                    GET, new HttpEntity<>(h), JsonNode.class);
            assertThat(presignResp.getStatusCode()).isEqualTo(OK);
            String uploadUrl = presignResp.getBody().get("data").asText();
            assertThat(uploadUrl).isNotBlank();

            // 2. 查 DB 应该有 PENDING 行(没传 fileContent,所以 size=0)
            List<FileMetadata> pending = fileRepo.findAll();
            // 可能 /mine 不返 PENDING(只查 ACTIVE),所以用 repo 全扫
            assertThat(pending).hasSize(1);
            FileMetadata p = pending.get(0);
            assertThat(p.getStatus()).isEqualTo(FileStatus.PENDING);
            assertThat(p.getSizeBytes()).isZero();
            assertThat(p.getBizType().name()).isEqualTo("ATTACHMENT");

            // 3. confirm 翻 ACTIVE
            // key 含 '/' 走 java.net.URI 显式构造,绕过 RestTemplate 自动 URL 重写
            // (RestTemplate 的 UriTemplateHandler 会对 query 里的 '/' 再编码一次,
            //  服务端收到 %2F 后行为不一致 — 参见 IT 验证)
            HttpHeaders confirmH = auth.authHeader(access);
            confirmH.setContentType(APPLICATION_JSON);
            String key = p.getObjectKey();
            java.net.URI confirmUri = new java.net.URI("http", null,
                    "localhost", port, "/api/files/confirm",
                    "key=" + key, null);
            var confirmResp = rest().exchange(confirmUri, POST,
                    new HttpEntity<>(java.util.Map.of(
                            "etag", "etag-from-s3", "size", 2048), confirmH),
                    JsonNode.class);
            assertThat(confirmResp.getStatusCode()).isEqualTo(OK);
            assertThat(confirmResp.getBody().get("data").get("status").asText()).isEqualTo("ACTIVE");
            // VO 不暴露 etag(commit 3 设计:防内部细节泄漏),size / confirmedAt 从 VO 拿
            assertThat(confirmResp.getBody().get("data").get("sizeBytes").asLong()).isEqualTo(2048L);

            // 4. DB 验证 status 翻 ACTIVE,size 校正
            FileMetadata reloaded = fileRepo.findById(p.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(FileStatus.ACTIVE);
            assertThat(reloaded.getSizeBytes()).isEqualTo(2048L);
            assertThat(reloaded.getEtag()).isEqualTo("etag-from-s3");
            assertThat(reloaded.getConfirmedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("AdminSearch")
    class AdminSearch {

        @Test
        @DisplayName("admin 查 owner 的全部文件,跨 biz / status")
        void admin_sees_all_owners_files() {
            // 上传一个头像
            String access = registerAndLogin("admin1")[0];
            Long fileId = uploadFile(access, "a.png", "x".getBytes(), "AVATAR");

            // 把当前用户升 admin(JwtAuthenticationFilter 加载的角色走 Redis 缓存,要 evict)
            com.nexusforge.user.entity.User u = userRepo().findAll().get(0);
            u.setRoles(java.util.Set.of(
                    com.nexusforge.enums.Role.USER, com.nexusforge.enums.Role.ADMIN));
            userRepo().save(u);
            userRoleProvider().evict(u.getId());
            // 重新登录拿带 ADMIN authority 的 token
            String adminAccess = auth.loginAccess(u.getUsername(), "secret123");

            // admin 查自己(owner=self)的全部
            HttpHeaders h = auth.authHeader(adminAccess);
            var resp = rest().exchange(
                    "/api/files/admin?ownerId=" + u.getId() + "&page=1&size=20",
                    GET, new HttpEntity<>(h), JsonNode.class);
            assertThat(resp.getStatusCode()).isEqualTo(OK);
            assertThat(resp.getBody().get("data").get("total").asInt()).isEqualTo(1);
            assertThat(resp.getBody().get("data").get("records").get(0).get("id").asLong())
                    .isEqualTo(fileId);
        }

        @Test
        @DisplayName("非 admin 调 /admin → 403 FORBIDDEN")
        void non_admin_returns_403() {
            String access = registerAndLogin("admin2")[0];
            HttpHeaders h = auth.authHeader(access);
            try {
                rest().exchange("/api/files/admin?ownerId=1&page=1&size=20",
                        GET, new HttpEntity<>(h), JsonNode.class);
                org.junit.jupiter.api.Assertions.fail("should have thrown");
            } catch (HttpClientErrorException e) {
                // @PreAuthorize 抛 AccessDeniedException → GlobalExceptionHandler
                // 兜底 403 + ResultCode.FORBIDDEN(1005),不是文件专属的 FILE_FORBIDDEN(2019)
                assertThat(e.getStatusCode().value()).isEqualTo(403);
                assertThat(e.getResponseBodyAsString())
                        .contains("\"code\":" + com.nexusforge.enums.ResultCode.FORBIDDEN.getCode());
            }
        }
    }

    @Nested
    @DisplayName("SoftDelete")
    class SoftDelete {

        @Test
        @DisplayName("owner 软删自己的文件,DB 翻 status=DELETED;/mine 不见")
        void owner_soft_deletes_own_file() {
            String access = registerAndLogin("sd1")[0];
            Long id = uploadFile(access, "a.png", "x".getBytes(), "ATTACHMENT");

            HttpHeaders h = auth.authHeader(access);
            var del = rest().exchange("/api/files/" + id, DELETE,
                    new HttpEntity<>(h), JsonNode.class);
            assertThat(del.getStatusCode()).isEqualTo(OK);

            // /mine 不再返(@SQLRestriction + status filter)
            var mine = rest().exchange("/api/files/mine?page=1&size=20", GET,
                    new HttpEntity<>(h), JsonNode.class);
            assertThat(mine.getBody().get("data").get("total").asInt()).isZero();

            // DB 验证 status 翻成 DELETED + deleted_at 非空(走原生 SQL 绕 @SQLRestriction)
            Object[] raw = (Object[]) entityManager.createNativeQuery(
                    "SELECT status, deleted_at IS NOT NULL FROM file_metadata WHERE id = :id")
                    .setParameter("id", id)
                    .getSingleResult();
            assertThat(raw[0]).isEqualTo("DELETED");
            assertThat((Boolean) raw[1]).isTrue();
        }

        @Test
        @DisplayName("非 owner 软删 → FILE_FORBIDDEN")
        void non_owner_returns_forbidden() {
            String access1 = registerAndLogin("sd2a")[0];
            Long id = uploadFile(access1, "a.png", "x".getBytes(), "ATTACHMENT");

            String access2 = registerAndLogin("sd2b")[0];
            HttpHeaders h2 = auth.authHeader(access2);
            try {
                rest().exchange("/api/files/" + id, DELETE,
                        new HttpEntity<>(h2), JsonNode.class);
                org.junit.jupiter.api.Assertions.fail("should have thrown");
            } catch (HttpClientErrorException e) {
                assertThat(e.getStatusCode().value()).isEqualTo(403);
                assertThat(e.getResponseBodyAsString())
                        .contains("\"code\":" + FILE_FORBIDDEN.getCode());
            }
        }
    }

    @Nested
    @DisplayName("ConfirmEdge")
    class ConfirmEdge {

        @Test
        @DisplayName("confirm 不存在的 key → FILE_NOT_FOUND")
        void confirm_missing_key() {
            String access = registerAndLogin("ce1")[0];
            HttpHeaders h = auth.authHeader(access);
            h.setContentType(APPLICATION_JSON);
            try {
                rest().exchange(
                        "/api/files/confirm?key=no-such-key",
                        POST, new HttpEntity<>(java.util.Map.of("etag", "x", "size", 1), h),
                        JsonNode.class);
                org.junit.jupiter.api.Assertions.fail("should have thrown");
            } catch (HttpClientErrorException e) {
                // 业务异常 → 200 + Result.fail 包装,或 4xx
                // 看 GlobalExceptionHandler 怎么处理 BusinessException
                // 通常 BusinessException 走 200 + 业务 code,这里测 4xx / 5xx 都接受
                // 关键是响应体里有 FILE_NOT_FOUND code
                String body = e.getResponseBodyAsString();
                assertThat(body).contains("\"code\":" + ResultCode.FILE_NOT_FOUND.getCode());
            }
        }
    }

    // ─────────────────────────────────────────────
    //  Autowire helpers — 用方法包装避免字段冲突 @BeforeEach
    // ─────────────────────────────────────────────

    @Autowired private com.nexusforge.user.repository.UserRepository userRepoField;
    @Autowired private com.nexusforge.user.service.UserRoleProvider userRoleProviderField;

    private com.nexusforge.user.repository.UserRepository userRepo() {
        return userRepoField;
    }

    private com.nexusforge.user.service.UserRoleProvider userRoleProvider() {
        return userRoleProviderField;
    }

    // 不重写 auth() 字段,IntegrationTestBase.auth 已暴露
}

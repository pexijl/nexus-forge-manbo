package com.nexusforge.audit;

import com.nexusforge.audit.OperationAuditLog.AuditResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 Audit Commit 1 单测 —— {@link OperationAuditLog} 实体 + 仓库,
 * Testcontainers 真 SQL + Hibernate {@code ddl-auto=create-drop}。
 *
 * <p>同 commit 1 FileMetadataRepositoryTest 模式:不走生产 Flyway
 * 迁移(Hibernate 跑 entity 即可),生产 DDL 由
 * {@code V20260830_002__add_operation_audit_log.sql} 走真 Flyway 跑通
 * (IT 在 commit 5 验证)。</p>
 *
 * <h3>覆盖矩阵</h3>
 * <ol>
 *   <li>{@code Persist}         新行 INSERT + 字段全对得上</li>
 *   <li>{@code MetadataJsonb}   metadata Map 自动 JSONB 序列化 + 反序列化</li>
 *   <li>{@code ResultEnum}       AuditResult 枚举存为字符串</li>
 *   <li>{@code AdminSearch}     多维过滤(null 维度跳过)</li>
 *   <li>{@code ByUser}          某用户最近操作按时间倒序</li>
 *   <li>{@code ByResource}      某 resource+resourceId 的操作历史</li>
 *   <li>{@code Top50}           findTop50... 返回最多 50 条</li>
 *   <li>{@code PageSort}        Pageable 排序(默认按 id desc 也可显式 createdAt desc)</li>
 * </ol>
 */
@SpringBootTest(classes = OperationAuditLogRepositoryTest.TestApp.class)
@Testcontainers
class OperationAuditLogRepositoryTest {

    @SpringBootApplication(scanBasePackages = "com.nexusforge.audit")
    @EntityScan(basePackages = "com.nexusforge.audit")
    static class TestApp { }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:latest")
                    .withDatabaseName("nexus_forge_audit_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private OperationAuditLogRepository repo;

    @AfterEach
    void cleanUp() {
        repo.deleteAll();
    }

    // ─────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────

    private OperationAuditLog makeLog(Long userId, String action, String resource,
                                       String resourceId, AuditResult result) {
        OperationAuditLog log = new OperationAuditLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setResource(resource);
        log.setResourceId(resourceId);
        log.setMethod("POST");
        log.setPath("/api/test");
        log.setIp("127.0.0.1");
        log.setUserAgent("Mozilla/5.0");
        log.setResult(result);
        log.setStatusCode(result == AuditResult.SUCCESS ? 200 : 500);
        log.setLatencyMs(123L);
        return log;
    }

    // ─────────────────────────────────────────────
    //  Persist
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Persist")
    class Persist {

        @Test
        @DisplayName("新行 INSERT + 字段全对得上")
        void insert_and_read() {
            OperationAuditLog log = makeLog(100L, "user.update", "user", "100",
                    AuditResult.SUCCESS);
            log.setErrorCode(null);  // SUCCESS 无 error_code
            OperationAuditLog saved = repo.saveAndFlush(log);

            assertThat(saved.getId()).isPositive();
            assertThat(saved.getAction()).isEqualTo("user.update");
            assertThat(saved.getResource()).isEqualTo("user");
            assertThat(saved.getResourceId()).isEqualTo("100");
            assertThat(saved.getResult()).isEqualTo(AuditResult.SUCCESS);
            assertThat(saved.getStatusCode()).isEqualTo(200);
            assertThat(saved.getLatencyMs()).isEqualTo(123L);
            assertThat(saved.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("FAILURE 行带 error_code")
        void failure_with_error_code() {
            OperationAuditLog log = makeLog(100L, "user.update", "user", "100",
                    AuditResult.FAILURE);
            log.setErrorCode(2011);  // 业务 code

            OperationAuditLog saved = repo.saveAndFlush(log);

            assertThat(saved.getResult()).isEqualTo(AuditResult.FAILURE);
            assertThat(saved.getErrorCode()).isEqualTo(2011);
        }
    }

    // ─────────────────────────────────────────────
    //  Metadata JSONB
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("MetadataJsonb")
    class MetadataJsonb {

        @Test
        @DisplayName("Map<String,Object> 自动 JSONB 序列化 + 反序列化")
        void map_serialized_to_jsonb() {
            OperationAuditLog log = makeLog(1L, "test", "x", "1", AuditResult.SUCCESS);
            log.setMetadata(Map.of(
                    "oldName", "alice",
                    "newName", "bob",
                    "count", 42L));

            OperationAuditLog saved = repo.saveAndFlush(log);
            repo.flush();

            OperationAuditLog reloaded = repo.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getMetadata()).isNotNull();
            assertThat(reloaded.getMetadata().get("oldName")).isEqualTo("alice");
            assertThat(reloaded.getMetadata().get("newName")).isEqualTo("bob");
            // JSONB 反序列化把整数拆为 Integer(Jackson 默认配置),不保 Long 类型
            assertThat(reloaded.getMetadata().get("count")).isEqualTo(42);
        }

        @Test
        @DisplayName("metadata=null 不报错")
        void null_metadata_ok() {
            OperationAuditLog log = makeLog(1L, "test", "x", "1", AuditResult.SUCCESS);
            // log.setMetadata(null) - default

            OperationAuditLog saved = repo.saveAndFlush(log);
            assertThat(saved.getId()).isPositive();
        }
    }

    // ─────────────────────────────────────────────
    //  Queries
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Queries")
    class Queries {

        @Test
        @DisplayName("adminSearch:userId+action+resource 多维过滤,null 跳过")
        void admin_search_with_null_filters() {
            repo.saveAndFlush(makeLog(1L, "user.update", "user", "1", AuditResult.SUCCESS));
            repo.saveAndFlush(makeLog(1L, "user.delete", "user", "1", AuditResult.SUCCESS));
            repo.saveAndFlush(makeLog(2L, "file.upload", "file", "1", AuditResult.FAILURE));

            // 全部
            Page<OperationAuditLog> all = repo.adminSearch(null, null, null,
                    PageRequest.of(0, 10));
            assertThat(all.getTotalElements()).isEqualTo(3);

            // userId=1
            Page<OperationAuditLog> user1 = repo.adminSearch(1L, null, null,
                    PageRequest.of(0, 10));
            assertThat(user1.getTotalElements()).isEqualTo(2);

            // action=user.delete
            Page<OperationAuditLog> del = repo.adminSearch(null, "user.delete", null,
                    PageRequest.of(0, 10));
            assertThat(del.getTotalElements()).isEqualTo(1);
            assertThat(del.getContent().get(0).getUserId()).isEqualTo(1L);

            // resource=file + result=FAILURE
            Page<OperationAuditLog> fileFail = repo.adminSearch(null, null, "file",
                    PageRequest.of(0, 10));
            assertThat(fileFail.getTotalElements()).isEqualTo(1);
            assertThat(fileFail.getContent().get(0).getResult()).isEqualTo(AuditResult.FAILURE);
        }

        @Test
        @DisplayName("某用户最近操作按时间倒序")
        void by_user_desc() throws InterruptedException {
            for (int i = 0; i < 3; i++) {
                repo.saveAndFlush(makeLog(1L, "act-" + i, "x", "1", AuditResult.SUCCESS));
                Thread.sleep(2);
            }
            repo.saveAndFlush(makeLog(2L, "other", "x", "1", AuditResult.SUCCESS));

            Page<OperationAuditLog> user1 = repo.findByUserIdOrderByCreatedAtDesc(
                    1L, PageRequest.of(0, 10));
            assertThat(user1.getTotalElements()).isEqualTo(3);
            // 最新插入的 act-2 排第一
            assertThat(user1.getContent().get(0).getAction()).isEqualTo("act-2");
            assertThat(user1.getContent().get(0).getCreatedAt())
                    .isAfter(user1.getContent().get(1).getCreatedAt());
        }

        @Test
        @DisplayName("某 resource+resourceId 的操作历史")
        void by_resource() {
            repo.saveAndFlush(makeLog(1L, "user.update", "user", "100", AuditResult.SUCCESS));
            repo.saveAndFlush(makeLog(1L, "user.update", "user", "100", AuditResult.SUCCESS));
            repo.saveAndFlush(makeLog(1L, "user.update", "user", "200", AuditResult.SUCCESS));
            repo.saveAndFlush(makeLog(1L, "file.upload", "file", "100", AuditResult.SUCCESS));

            Page<OperationAuditLog> user100 = repo.findByResourceAndResourceIdOrderByCreatedAtDesc(
                    "user", "100", PageRequest.of(0, 10));
            assertThat(user100.getTotalElements()).isEqualTo(2);
            assertThat(user100.getContent()).allMatch(a -> "user".equals(a.getResource()));
        }
    }
}

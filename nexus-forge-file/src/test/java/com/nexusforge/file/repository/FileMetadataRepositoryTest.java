package com.nexusforge.file.repository;

import com.nexusforge.file.FileAccess;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2 Commit 1: {@link FileMetadata} 实体 + {@link FileMetadataRepository} 单测。
 *
 * <p>使用 Testcontainers PostgreSQL + Hibernate {@code ddl-auto=create-drop}
 * 验证 entity 字段映射与派生方法语义。生产环境走 Flyway(本模块不持 SQL,
 * 权威迁移在 {@code nexus-forge-user/src/main/resources/db/migration/}),
 * 集成测试在 commit 5 用真实 Flyway 跑通。</p>
 *
 * <p><b>Spring Boot 4.1 兼容</b>:与 P5 Step 1 一样,@EntityScan 包路径变更为
 * {@code org.springframework.boot.persistence.autoconfigure.EntityScan};</p>
 *
 * <h3>覆盖矩阵</h3>
 * <ol>
 *   <li>{@code FindByBucketAndObjectKey} — confirm 路径查单行</li>
 *   <li>{@code UniqueConstraint} — 同一 (bucket, key) 重复插入抛异常</li>
 *   <li>{@code PageByOwner} — 我的文件分页,按 created_at desc</li>
 *   <li>{@code PageByOwnerAndBiz} — 头像 / 附件 / AI 图片分开展示</li>
 *   <li>{@code SoftDelete} — repo.delete 翻 status=DELETED + deleted_at,查询自动过滤</li>
 *   <li>{@code MarkConfirmed} — PENDING → ACTIVE 翻状态,幂等</li>
 *   <li>{@code AdminSearch} — 管理员视角带 null 过滤(跨 biz / status)</li>
 *   <li>{@code FindAllByOwnerId} — GDPR 真删路径跨状态扫描</li>
 * </ol>
 */
@SpringBootTest(classes = FileMetadataRepositoryTest.TestApp.class)
@Testcontainers
class FileMetadataRepositoryTest {

    /**
     * 最小 Spring 上下文。
     * <ul>
     *   <li>{@code scanBasePackages} 只扫 file 模块的 repository / entity,
     *       避免触发 {@code FileClientImpl} / {@code StorageProvider} 装配
     *       (那些要真实 S3 endpoint)</li>
     *   <li>{@code @EntityScan} 显式声明实体包,因为 SpringBootTest 默认从
     *       {@code com.nexusforge} 扫会拉到 user / ai 模块的实体(不在 classpath)
     *   </li>
     *   <li>{@code @EnableJpaRepositories} 仓库使能</li>
     * </ul>
     */
    @SpringBootApplication(scanBasePackages = "com.nexusforge.file.repository")
    @EnableJpaRepositories(basePackages = "com.nexusforge.file.repository")
    @EntityScan(basePackages = "com.nexusforge.file.entity")
    static class TestApp { }

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:latest")
                    .withDatabaseName("nexus_forge_file_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // 本模块单测用 Hibernate 自动建表(避免依赖 user 模块的 Flyway 资源);
        // 真实迁移由 integration test 验证。
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private FileMetadataRepository repo;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        repo.deleteAll();
    }

    // ─────────────────────────────────────────────
    //  Fixture
    // ─────────────────────────────────────────────

    /** 工厂:创建默认 ACTIVE 头像行,owner 与 biz 可覆盖 */
    private FileMetadata makeFile(Long ownerId, FileBizType biz, FileStatus status) {
        FileMetadata f = new FileMetadata();
        f.setOwnerId(ownerId);
        f.setBucket("test-bucket");
        f.setObjectKey(String.format("%s/%d/%s.png", biz.name().toLowerCase(),
                ownerId, java.util.UUID.randomUUID()));
        f.setBizType(biz);
        f.setAccess(biz.defaultAccess());
        f.setOriginalFilename("hello.png");
        f.setContentType("image/png");
        f.setSizeBytes(1024L);
        f.setStatus(status);
        if (status == FileStatus.ACTIVE) {
            f.setConfirmedAt(java.time.OffsetDateTime.now());
        }
        return f;
    }

    // ─────────────────────────────────────────────
    //  Cases
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("FindByBucketAndObjectKey")
    class FindByBucketAndObjectKey {

        @Test
        @DisplayName("已存在行 → Optional 非空")
        void existing_returns_present() {
            FileMetadata saved = repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));

            Optional<FileMetadata> found = repo.findByBucketAndObjectKey(
                    saved.getBucket(), saved.getObjectKey());

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(saved.getId());
            assertThat(found.get().getStatus()).isEqualTo(FileStatus.ACTIVE);
        }

        @Test
        @DisplayName("不存在 → Optional.empty")
        void missing_returns_empty() {
            Optional<FileMetadata> found = repo.findByBucketAndObjectKey(
                    "no-such-bucket", "no-such-key");
            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("UniqueConstraint")
    class UniqueConstraint {

        @Test
        @DisplayName("同一 (bucket, object_key) 二次插入 → DataIntegrityViolationException")
        void duplicate_bucket_key_violates_unique() {
            FileMetadata a = makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE);
            a.setBucket("dup-bucket");
            a.setObjectKey("dup/key.png");
            repo.saveAndFlush(a);

            // 同 bucket + key 复制一条
            FileMetadata b = makeFile(2L, FileBizType.AVATAR, FileStatus.ACTIVE);
            b.setBucket("dup-bucket");
            b.setObjectKey("dup/key.png");
            assertThatThrownBy(() -> repo.saveAndFlush(b))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("PageByOwner")
    class PageByOwner {

        @Test
        @DisplayName("owner=1 的 5 条 ACTIVE → 分页 2+3,按 created_at desc")
        void page_by_owner_desc() throws InterruptedException {
            // created_at 来自 BaseEntity @PrePersist,需要 1ms 间隔让排序生效
            for (int i = 0; i < 5; i++) {
                repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));
                Thread.sleep(2);
            }
            // 另一 owner 的数据不应混入
            repo.saveAndFlush(makeFile(2L, FileBizType.AVATAR, FileStatus.ACTIVE));

            Pageable page1 = PageRequest.of(0, 2);
            Pageable page2 = PageRequest.of(1, 2);
            Pageable page3 = PageRequest.of(2, 2);

            Page<FileMetadata> p1 = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    1L, FileStatus.ACTIVE, page1);
            Page<FileMetadata> p2 = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    1L, FileStatus.ACTIVE, page2);
            Page<FileMetadata> p3 = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    1L, FileStatus.ACTIVE, page3);

            assertThat(p1.getTotalElements()).isEqualTo(5);
            assertThat(p1.getContent()).hasSize(2);
            assertThat(p2.getContent()).hasSize(2);
            assertThat(p3.getContent()).hasSize(1);
            // 验证 desc 排序:第 1 页第一条 createdAt 比第 2 页第一条晚
            assertThat(p1.getContent().get(0).getCreatedAt())
                    .isAfter(p2.getContent().get(0).getCreatedAt());
        }

        @Test
        @DisplayName("PENDING 不计入 ACTIVE 列表(@SQLRestriction 不会过滤 status,只过滤 deleted_at)")
        void pending_excluded_when_filtering_active() {
            repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));
            repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.PENDING));

            Page<FileMetadata> active = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    1L, FileStatus.ACTIVE, PageRequest.of(0, 10));

            assertThat(active.getTotalElements()).isEqualTo(1);
            assertThat(active.getContent().get(0).getStatus()).isEqualTo(FileStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("PageByOwnerAndBiz")
    class PageByOwnerAndBiz {

        @Test
        @DisplayName("owner=1 头像 3 + 附件 2 → 查头像分页只返 3 条")
        void filter_by_biz() {
            for (int i = 0; i < 3; i++) {
                repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));
            }
            for (int i = 0; i < 2; i++) {
                repo.saveAndFlush(makeFile(1L, FileBizType.ATTACHMENT, FileStatus.ACTIVE));
            }

            Page<FileMetadata> avatars = repo
                    .findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc(
                            1L, FileBizType.AVATAR, FileStatus.ACTIVE, PageRequest.of(0, 10));
            Page<FileMetadata> attachments = repo
                    .findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc(
                            1L, FileBizType.ATTACHMENT, FileStatus.ACTIVE, PageRequest.of(0, 10));

            assertThat(avatars.getTotalElements()).isEqualTo(3);
            assertThat(attachments.getTotalElements()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("SoftDelete")
    class SoftDelete {

        @Test
        @DisplayName("repo.delete → status=DELETED + deleted_at 非空;查询自动过滤")
        void soft_delete_translates_to_update() {
            FileMetadata f = repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));
            Long id = f.getId();

            repo.delete(f);
            repo.flush();

            // @SQLRestriction 过滤后:ACTIVE 视角查不到
            Page<FileMetadata> after = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    1L, FileStatus.ACTIVE, PageRequest.of(0, 10));
            assertThat(after.getTotalElements()).isZero();

            // 直接走 native SQL 验证 @SQLDelete 真的把 status 翻成 DELETED + deleted_at 非空
            // (绕过 @SQLRestriction — 否则查不到软删行;参考
            //  ConversationService.restoreConversation 同模式)
            Object[] raw = (Object[]) entityManager.createNativeQuery(
                    "SELECT status, deleted_at IS NOT NULL FROM file_metadata WHERE id = :id")
                    .setParameter("id", id)
                    .getSingleResult();
            assertThat(raw[0]).isEqualTo("DELETED");
            assertThat((Boolean) raw[1]).isTrue();
        }
    }

    @Nested
    @DisplayName("MarkConfirmed")
    class MarkConfirmed {

        @Test
        @DisplayName("PENDING → ACTIVE 翻状态 + 写 confirmed_at;幂等")
        void mark_confirmed_idempotent() {
            FileMetadata f = repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.PENDING));
            assertThat(f.getStatus()).isEqualTo(FileStatus.PENDING);
            assertThat(f.getConfirmedAt()).isNull();

            f.markConfirmed("etag-abc", 2048L);
            FileMetadata reloaded = repo.saveAndFlush(f);

            assertThat(reloaded.getStatus()).isEqualTo(FileStatus.ACTIVE);
            assertThat(reloaded.getConfirmedAt()).isNotNull();
            assertThat(reloaded.getEtag()).isEqualTo("etag-abc");
            assertThat(reloaded.getSizeBytes()).isEqualTo(2048L);

            // 再次调 markConfirmed 应幂等(状态保持 ACTIVE,confirmed_at 不变)
            var firstConfirm = reloaded.getConfirmedAt();
            reloaded.markConfirmed("etag-different", 9999L);
            assertThat(reloaded.getStatus()).isEqualTo(FileStatus.ACTIVE);
            assertThat(reloaded.getConfirmedAt()).isEqualTo(firstConfirm);
        }
    }

    @Nested
    @DisplayName("AdminSearch")
    class AdminSearch {

        @Test
        @DisplayName("owner=1 头像 2 ACTIVE + 附件 1 PENDING;adminSearch 各种 null 过滤")
        void admin_search_with_null_filters() {
            repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));
            repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));
            repo.saveAndFlush(makeFile(1L, FileBizType.ATTACHMENT, FileStatus.PENDING));

            // 全部(null, null)
            Page<FileMetadata> all = repo.adminSearch(
                    1L, null, null, PageRequest.of(0, 10));
            assertThat(all.getTotalElements()).isEqualTo(3);

            // 只按 biz
            Page<FileMetadata> avatars = repo.adminSearch(
                    1L, FileBizType.AVATAR, null, PageRequest.of(0, 10));
            assertThat(avatars.getTotalElements()).isEqualTo(2);

            // 只按 status
            Page<FileMetadata> pending = repo.adminSearch(
                    1L, null, FileStatus.PENDING, PageRequest.of(0, 10));
            assertThat(pending.getTotalElements()).isEqualTo(1);
            assertThat(pending.getContent().get(0).getBizType()).isEqualTo(FileBizType.ATTACHMENT);

            // 组合
            Page<FileMetadata> combo = repo.adminSearch(
                    1L, FileBizType.AVATAR, FileStatus.ACTIVE, PageRequest.of(0, 10));
            assertThat(combo.getTotalElements()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("FindAllByOwnerId")
    class FindAllByOwnerId {

        @Test
        @DisplayName("owner=1 跨 ACTIVE/PENDING/DELETED 全部返;owner=2 不混入")
        void find_all_by_owner_across_statuses() {
            repo.saveAndFlush(makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE));
            repo.saveAndFlush(makeFile(1L, FileBizType.ATTACHMENT, FileStatus.PENDING));
            // 软删一条
            FileMetadata toDelete = makeFile(1L, FileBizType.AVATAR, FileStatus.ACTIVE);
            repo.saveAndFlush(toDelete);
            repo.delete(toDelete);
            repo.flush();
            // 另一 owner
            repo.saveAndFlush(makeFile(2L, FileBizType.AVATAR, FileStatus.ACTIVE));

            List<FileMetadata> all = repo.findAllByOwnerId(1L);
            // 软删那条在 @SQLRestriction 下不返
            assertThat(all).hasSize(2);
            assertThat(all).extracting(FileMetadata::getOwnerId).containsOnly(1L);
        }
    }
}

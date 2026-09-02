package com.nexusforge.service;

import com.nexusforge.config.StorageProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import com.nexusforge.file.repository.FileMetadataRepository;
import com.nexusforge.lock.DistributedLockTemplate;
import com.nexusforge.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Commit 2 单测 — {@link FileService} 落库路径。
 *
 * <p>使用 Mockito 隔离,无需 Testcontainers / Docker(commit 1 的
 * {@code FileMetadataRepositoryTest} 仍需 Testcontainers,但本测覆盖
 * service 层业务逻辑)。</p>
 *
 * <h3>覆盖矩阵</h3>
 * <ol>
 *   <li>{@code uploadByBiz} — 新行 PENDING → storage → ACTIVE 流转</li>
 *   <li>{@code uploadByBiz} — 已存在 PENDING 行复用,不再 insert</li>
 *   <li>{@code issueUploadCredential} — 写 PENDING 行,size=0 待 confirm 回填</li>
 *   <li>{@code confirmUpload} — PENDING → ACTIVE 写 etag / size</li>
 *   <li>{@code confirmUpload} — 已 ACTIVE 幂等</li>
 *   <li>{@code confirmUpload} — 不存在 → FILE_NOT_FOUND</li>
 *   <li>{@code confirmUpload} — 已 DELETED → FILE_ALREADY_DELETED</li>
 *   <li>{@code findMyFiles} — biz=null → 不带 biz 过滤;biz 非空 → 带过滤</li>
 *   <li>{@code softDeleteById} — owner 不匹配 → FILE_FORBIDDEN</li>
 *   <li>{@code softDeleteById} — owner 匹配 → repo.delete 触发软删</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock private StorageProvider storageProvider;
    @Mock private FileMetadataRepository fileRepo;
    @Mock private DistributedLockTemplate lockTemplate;

    private StorageProperties storageProps;
    private FileService service;

    @BeforeEach
    void setUp() {
        storageProps = new StorageProperties();
        StorageProperties.VendorConfig vendor = new StorageProperties.VendorConfig();
        vendor.setBucket("test-bucket");
        storageProps.getRustfs().put("default", vendor);
        // 默认 lockTemplate 直接调 supplier 拿值(单测不验证锁行为,锁路径在
        // DistributedLockTemplate 单测覆盖;这里只验委派关系)。lenient
        // 避免 ConfirmUpload / FindMyFiles / SoftDeleteById 等不调
        // uploadByBiz 的子测试报 UnnecessaryStubbingException。
        org.mockito.Mockito.lenient().when(lockTemplate.<FileMetadata>lock(
                        anyString(), any(java.time.Duration.class), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> sup = inv.getArgument(2);
                    @SuppressWarnings("unchecked")
                    FileMetadata result = (FileMetadata) sup.get();
                    return result;
                });
        service = new FileService(storageProvider, storageProps, fileRepo, lockTemplate);
    }

    // ─────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────

    private FileMetadata stubPending(String bucket, String key, FileBizType biz, Long ownerId) {
        FileMetadata f = new FileMetadata();
        f.setBucket(bucket);
        f.setObjectKey(key);
        f.setBizType(biz);
        f.setAccess(biz.defaultAccess());
        f.setOwnerId(ownerId);
        f.setStatus(FileStatus.PENDING);
        f.setSizeBytes(0L);
        f.setContentType("text/plain");
        f.setOriginalFilename("test.txt");
        // id 由 JPA 自动生成,这里手动设 1L 方便断言
        try {
            java.lang.reflect.Field idField = FileMetadata.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(f, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return f;
    }

    private InputStream sampleStream() {
        return new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────
    //  uploadByBiz
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("uploadByBiz")
    class UploadByBiz {

        @Test
        @DisplayName("上传走 lockTemplate.lock(key, 30s, supplier)")
        void upload_uses_lock_template() throws Exception {
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(storageProvider.upload(anyString(), anyString(), any(InputStream.class),
                    anyLong(), anyString()))
                    .thenReturn("etag-locked");

            service.uploadByBiz(FileBizType.AVATAR, 100L, "avatar.png", "image/png",
                    1024L, sampleStream());

            // 验证 lockTemplate.lock 被调,key 是 upload:avatar:100,lease 是 30s
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<java.time.Duration> leaseCaptor =
                    ArgumentCaptor.forClass(java.time.Duration.class);
            verify(lockTemplate, times(1)).lock(
                    keyCaptor.capture(), leaseCaptor.capture(), any());
            assertThat(keyCaptor.getValue()).isEqualTo("upload:avatar:100");
            assertThat(leaseCaptor.getValue()).isEqualTo(java.time.Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("anon 上传( ownerId=null )→ 锁 key 用 'anon' 段")
        void upload_anon_owner() throws Exception {
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(storageProvider.upload(anyString(), anyString(), any(InputStream.class),
                    anyLong(), anyString()))
                    .thenReturn("etag");

            service.uploadByBiz(FileBizType.ATTACHMENT, null, "x.pdf", "application/pdf",
                    100L, sampleStream());

            verify(lockTemplate, times(1)).lock(
                    eq("upload:attachment:anon"), any(java.time.Duration.class), any());
        }

        @Test
        @DisplayName("不同 biz 类型用不同锁 key(防 AVATAR 阻塞 ATTACHMENT)")
        void different_biz_different_lock() throws Exception {
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(storageProvider.upload(anyString(), anyString(), any(InputStream.class),
                    anyLong(), anyString()))
                    .thenReturn("etag");

            service.uploadByBiz(FileBizType.AVATAR, 100L, "a.png", "image/png",
                    100L, sampleStream());
            service.uploadByBiz(FileBizType.ATTACHMENT, 100L, "b.pdf", "application/pdf",
                    100L, sampleStream());

            verify(lockTemplate, times(1)).lock(
                    eq("upload:avatar:100"), any(java.time.Duration.class), any());
            verify(lockTemplate, times(1)).lock(
                    eq("upload:attachment:100"), any(java.time.Duration.class), any());
        }

        @Test
        @DisplayName("新行:写 PENDING → 上传 storage → markConfirmed(ACTIVE)")
        void new_row_full_lifecycle() throws Exception {
            // given — fileRepo.findByBucketAndObjectKey 返空(新行)
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            // 第一次 save(PENDING 插入)返新行,第二次 save(ACTIVE 更新)返同 row
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(storageProvider.upload(anyString(), anyString(), any(InputStream.class),
                    anyLong(), anyString()))
                    .thenReturn("etag-abc");

            // when
            FileMetadata result = service.uploadByBiz(
                    FileBizType.AVATAR, 100L, "avatar.png", "image/png", 1024L,
                    sampleStream());

            // then
            assertThat(result.getStatus()).isEqualTo(FileStatus.ACTIVE);
            assertThat(result.getEtag()).isEqualTo("etag-abc");
            assertThat(result.getOwnerId()).isEqualTo(100L);
            assertThat(result.getBizType()).isEqualTo(FileBizType.AVATAR);

            // 验证 storageProvider.upload 被调用一次
            verify(storageProvider, times(1)).upload(
                    eq("test-bucket"), anyString(), any(InputStream.class),
                    eq(1024L), eq("image/png"));
            // 验证 fileRepo.save 至少 1 次(INSERT)+ 1 次(UPDATE) — 至少 2 次
            verify(fileRepo, times(2)).save(any(FileMetadata.class));
        }

        @Test
        @DisplayName("已存在 PENDING 行:复用,不再 INSERT")
        void existing_pending_row_reused() throws Exception {
            // given — 已存在 PENDING 行
            FileMetadata existing = stubPending("test-bucket", "public/avatar/100/existing.png",
                    FileBizType.AVATAR, 100L);
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.of(existing));
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(storageProvider.upload(anyString(), anyString(), any(InputStream.class),
                    anyLong(), anyString()))
                    .thenReturn("etag-xyz");

            // when
            FileMetadata result = service.uploadByBiz(
                    FileBizType.AVATAR, 100L, "avatar.png", "image/png", 2048L,
                    sampleStream());

            // then — 行被复用,save 只调 1 次(UPDATE 翻 ACTIVE)
            assertThat(result.getStatus()).isEqualTo(FileStatus.ACTIVE);
            assertThat(result.getEtag()).isEqualTo("etag-xyz");
            assertThat(result.getSizeBytes()).isEqualTo(2048L);  // markConfirmed 校正
            verify(fileRepo, times(1)).save(any(FileMetadata.class));
        }

        @Test
        @DisplayName("storage 抛错 → @Transactional 触发回滚,save 仍被调(实际回滚由 Spring 代理做)")
        void storage_failure_rolls_back() throws Exception {
            // 模拟 storage 失败
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(storageProvider.upload(anyString(), anyString(), any(InputStream.class),
                    anyLong(), anyString()))
                    .thenThrow(new RuntimeException("S3 down"));

            // service 自己会抛 — @Transactional 回滚由 Spring 代理做(单测不带 AOP)
            assertThatThrownBy(() -> service.uploadByBiz(
                    FileBizType.AVATAR, 100L, "avatar.png", "image/png", 1024L,
                    sampleStream()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("S3 down");
        }
    }

    // ─────────────────────────────────────────────
    //  issueUploadCredential
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("issueUploadCredential")
    class IssueUploadCredential {

        @Test
        @DisplayName("写 PENDING 行,size=0 待 confirm 回填")
        void writes_pending_row() {
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            FileMetadata result = service.issueUploadCredential(
                    FileBizType.ATTACHMENT, 200L, "report.pdf", "application/pdf");

            assertThat(result.getStatus()).isEqualTo(FileStatus.PENDING);
            assertThat(result.getSizeBytes()).isZero();
            assertThat(result.getOwnerId()).isEqualTo(200L);
            assertThat(result.getContentType()).isEqualTo("application/pdf");
            // 验证只调 1 次 save(INSERT)
            verify(fileRepo, times(1)).save(any(FileMetadata.class));
        }

        @Test
        @DisplayName("已存在 PENDING 行 → 复用,不 INSERT")
        void existing_pending_reused() {
            FileMetadata existing = stubPending("test-bucket", "public/attachment/200/x.pdf",
                    FileBizType.ATTACHMENT, 200L);
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.of(existing));

            FileMetadata result = service.issueUploadCredential(
                    FileBizType.ATTACHMENT, 200L, "report.pdf", "application/pdf");

            assertThat(result.getStatus()).isEqualTo(FileStatus.PENDING);
            // 不调 save
            verify(fileRepo, never()).save(any(FileMetadata.class));
        }
    }

    // ─────────────────────────────────────────────
    //  confirmUpload
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("confirmUpload")
    class ConfirmUpload {

        @Test
        @DisplayName("PENDING → ACTIVE 翻状态 + 写 etag / size")
        void pending_to_active() {
            FileMetadata f = stubPending("test-bucket", "public/avatar/100/k.png",
                    FileBizType.AVATAR, 100L);
            when(fileRepo.findByBucketAndObjectKey(anyString(), eq("public/avatar/100/k.png")))
                    .thenReturn(Optional.of(f));
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            FileMetadata result = service.confirmUpload("public/avatar/100/k.png", "etag-1", 4096L);

            assertThat(result.getStatus()).isEqualTo(FileStatus.ACTIVE);
            assertThat(result.getEtag()).isEqualTo("etag-1");
            assertThat(result.getSizeBytes()).isEqualTo(4096L);
            assertThat(result.getConfirmedAt()).isNotNull();
        }

        @Test
        @DisplayName("已 ACTIVE → 幂等,confirmedAt 不变(其实 markConfirmed 早返,不重写时间戳)")
        void active_is_idempotent() {
            FileMetadata f = stubPending("test-bucket", "k", FileBizType.AVATAR, 100L);
            f.setStatus(FileStatus.ACTIVE);
            f.setConfirmedAt(java.time.OffsetDateTime.now().minusHours(1));
            java.time.OffsetDateTime originalConfirm = f.getConfirmedAt();
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.of(f));
            when(fileRepo.save(any(FileMetadata.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            FileMetadata result = service.confirmUpload("k", "etag-new", 9999L);

            assertThat(result.getStatus()).isEqualTo(FileStatus.ACTIVE);
            // markConfirmed 已 ACTIVE 直接 return,confirmedAt 不变
            assertThat(result.getConfirmedAt()).isEqualTo(originalConfirm);
            // etag / size 也不变(因 markConfirmed 早返)
            assertThat(result.getEtag()).isNotEqualTo("etag-new");
        }

        @Test
        @DisplayName("不存在 → 抛 FILE_NOT_FOUND")
        void not_found_throws() {
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmUpload("missing", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.FILE_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("已 DELETED → 抛 FILE_ALREADY_DELETED")
        void deleted_throws() {
            FileMetadata f = stubPending("test-bucket", "k", FileBizType.AVATAR, 100L);
            f.setStatus(FileStatus.DELETED);
            when(fileRepo.findByBucketAndObjectKey(anyString(), anyString()))
                    .thenReturn(Optional.of(f));

            assertThatThrownBy(() -> service.confirmUpload("k", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.FILE_ALREADY_DELETED.getCode());
        }
    }

    // ─────────────────────────────────────────────
    //  findMyFiles
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("findMyFiles")
    class FindMyFiles {

        @Test
        @DisplayName("biz=null → 不带 biz 过滤")
        void null_biz_omits_biz_filter() {
            Pageable pageReq = PageRequest.of(0, 10);
            Page<FileMetadata> page = new PageImpl<>(List.of(), pageReq, 0);
            when(fileRepo.findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    eq(100L), eq(FileStatus.ACTIVE), eq(pageReq)))
                    .thenReturn(page);

            var result = service.findMyFiles(100L, null, pageReq);

            assertThat(result.getTotal()).isZero();
            // 验证调的是不带 biz 的方法
            verify(fileRepo, times(1)).findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    100L, FileStatus.ACTIVE, pageReq);
            verify(fileRepo, never()).findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc(
                    anyLong(), any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("biz=AVATAR → 带 biz 过滤")
        void biz_filter_applied() {
            Pageable pageReq = PageRequest.of(0, 10);
            Page<FileMetadata> page = new PageImpl<>(List.of(), pageReq, 0);
            when(fileRepo.findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc(
                    eq(100L), eq(FileBizType.AVATAR), eq(FileStatus.ACTIVE), eq(pageReq)))
                    .thenReturn(page);

            var result = service.findMyFiles(100L, FileBizType.AVATAR, pageReq);

            assertThat(result.getTotal()).isZero();
            verify(fileRepo, times(1)).findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc(
                    100L, FileBizType.AVATAR, FileStatus.ACTIVE, pageReq);
            verify(fileRepo, never()).findByOwnerIdAndStatusOrderByCreatedAtDesc(
                    anyLong(), any(), any(Pageable.class));
        }
    }

    // ─────────────────────────────────────────────
    //  softDeleteById
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("softDeleteById")
    class SoftDeleteById {

        @Test
        @DisplayName("owner 不匹配 → FILE_FORBIDDEN,不删")
        void owner_mismatch_throws() {
            FileMetadata f = stubPending("test-bucket", "k", FileBizType.AVATAR, 100L);
            when(fileRepo.findById(1L)).thenReturn(Optional.of(f));

            assertThatThrownBy(() -> service.softDeleteById(1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.FILE_FORBIDDEN.getCode());
            verify(fileRepo, never()).delete(any(FileMetadata.class));
        }

        @Test
        @DisplayName("owner 匹配 → repo.delete 触发软删")
        void owner_match_soft_deletes() {
            FileMetadata f = stubPending("test-bucket", "k", FileBizType.AVATAR, 100L);
            when(fileRepo.findById(1L)).thenReturn(Optional.of(f));
            // 静默 mock void 方法

            service.softDeleteById(1L, 100L);

            verify(fileRepo, times(1)).delete(f);
        }

        @Test
        @DisplayName("owner=null(管理员路径) → 跳过 owner 校验")
        void admin_path_no_owner_check() {
            FileMetadata f = stubPending("test-bucket", "k", FileBizType.AVATAR, 100L);
            when(fileRepo.findById(1L)).thenReturn(Optional.of(f));

            service.softDeleteById(1L, null);

            verify(fileRepo, times(1)).delete(f);
        }
    }
}

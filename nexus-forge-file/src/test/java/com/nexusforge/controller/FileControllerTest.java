package com.nexusforge.controller;

import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.file.FileAccess;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.dto.ConfirmUploadDto;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import com.nexusforge.file.vo.FileMetadataVo;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Commit 3 单测 — {@link FileController} HTTP 端点委派逻辑。
 *
 * <p>Mockito 隔离,只验证:
 * <ul>
 *   <li>owner 从 SecurityContext 提取后传给 service</li>
 *   <li>VO / Result 包装正确</li>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")} 真实跑通在集成测试;
 *       本单测只验证 controller 方法本身</li>
 * </ul>
 *
 * <h3>覆盖矩阵</h3>
 * <ol>
 *   <li>{@code upload}         委派 uploadByBiz + owner 提取</li>
 *   <li>{@code upload}         未登录返 UNAUTHORIZED</li>
 *   <li>{@code confirmUpload}  委派 confirmUpload + DTO 字段映射</li>
 *   <li>{@code listMine}       biz=null / biz=AVATAR 走对应 service 方法</li>
 *   <li>{@code getById}        owner 校验(经 service.findByIdForOwner)</li>
 *   <li>{@code deleteById}     委派 softDeleteById</li>
 *   <li>{@code adminSearch}    委派 adminSearch</li>
 *   <li>{@code presignedPutUrl} 写 PENDING + 返 URL</li>
 * </ol>
 */
class FileControllerTest {

    private FileService service;
    private FileController controller;

    @BeforeEach
    void setUp() {
        service = mock(FileService.class);
        controller = new FileController(service);
        // 模拟已认证的普通用户
        UserPrincipal user = new UserPrincipal(100L, "alice");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(
                        user, "n/a", Set.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────

    private FileMetadata stubEntity(Long id, Long ownerId, FileBizType biz, FileStatus status) {
        FileMetadata f = new FileMetadata();
        f.setBucket("test-bucket");
        f.setObjectKey(String.format("public/%s/%d/%s.png", biz.name().toLowerCase(),
                ownerId, java.util.UUID.randomUUID()));
        f.setBizType(biz);
        f.setAccess(biz.defaultAccess());
        f.setOwnerId(ownerId);
        f.setOriginalFilename("hello.png");
        f.setContentType("image/png");
        f.setSizeBytes(1024L);
        f.setStatus(status);
        if (status == FileStatus.ACTIVE) {
            f.setConfirmedAt(OffsetDateTime.now());
        }
        // 手动设 id
        try {
            java.lang.reflect.Field idField = FileMetadata.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(f, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return f;
    }

    private MockMultipartFile mockFile() {
        return new MockMultipartFile(
                "file", "hello.png", "image/png",
                "hello".getBytes(StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────
    //  upload
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /upload")
    class Upload {

        @Test
        @DisplayName("已登录:委派 uploadByBiz,owner=100L")
        void authenticated_delegates() throws IOException {
            FileMetadata entity = stubEntity(1L, 100L, FileBizType.AVATAR, FileStatus.ACTIVE);
            when(service.uploadByBiz(eq(FileBizType.AVATAR), eq(100L),
                    anyString(), anyString(), anyLong(), any(InputStream.class)))
                    .thenReturn(entity);

            Result<FileMetadataVo> result = controller.upload(mockFile(), FileBizType.AVATAR);

            assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().id()).isEqualTo(1L);
            assertThat(result.getData().bizType()).isEqualTo(FileBizType.AVATAR);
            assertThat(result.getData().status()).isEqualTo(FileStatus.ACTIVE);
            assertThat(result.getData().sizeBytes()).isEqualTo(1024L);
        }

        @Test
        @DisplayName("未登录:返 UNAUTHORIZED,不调 service")
        void unauthenticated_returns_unauthorized() throws IOException {
            SecurityContextHolder.clearContext();
            Result<FileMetadataVo> result = controller.upload(mockFile(), FileBizType.AVATAR);
            assertThat(result.getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
            verify(service, never()).uploadByBiz(any(), any(), any(), any(), anyLong(), any());
        }
    }

    // ─────────────────────────────────────────────
    //  confirmUpload
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /confirm/{key}")
    class ConfirmUpload {

        @Test
        @DisplayName("委派 confirmUpload,DTO 字段透传")
        void delegates_with_dto() {
            FileMetadata entity = stubEntity(5L, 100L, FileBizType.AVATAR, FileStatus.ACTIVE);
            when(service.confirmUpload(eq("public/avatar/100/k.png"), eq("etag-1"), eq(2048L)))
                    .thenReturn(entity);

            Result<FileMetadataVo> result = controller.confirmUpload(
                    "public/avatar/100/k.png", new ConfirmUploadDto("etag-1", 2048L));

            assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(result.getData().id()).isEqualTo(5L);
            assertThat(result.getData().status()).isEqualTo(FileStatus.ACTIVE);
            verify(service, times(1)).confirmUpload("public/avatar/100/k.png", "etag-1", 2048L);
        }
    }

    // ─────────────────────────────────────────────
    //  listMine
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /mine")
    class ListMine {

        @Test
        @DisplayName("biz=null → 委派 findMyFiles(owner, null)")
        void null_biz_delegates() {
            Page<FileMetadata> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
            when(service.findMyFiles(eq(100L), eq(null), any(Pageable.class))).thenReturn(PageResult.of(page));

            Result<PageResult<FileMetadataVo>> result = controller.listMine(null, 1, 20);

            assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(result.getData().getTotal()).isZero();
            verify(service, times(1)).findMyFiles(eq(100L), eq(null), any(Pageable.class));
        }

        @Test
        @DisplayName("biz=AVATAR → 委派 findMyFiles(owner, AVATAR)")
        void biz_avatar_delegates() {
            Page<FileMetadata> page = new PageImpl<>(
                    List.of(stubEntity(1L, 100L, FileBizType.AVATAR, FileStatus.ACTIVE)),
                    PageRequest.of(0, 20), 1);
            when(service.findMyFiles(eq(100L), eq(FileBizType.AVATAR), any(Pageable.class)))
                    .thenReturn(PageResult.of(page));

            Result<PageResult<FileMetadataVo>> result = controller.listMine(FileBizType.AVATAR, 1, 20);

            assertThat(result.getData().getRecords()).hasSize(1);
            assertThat(result.getData().getRecords().get(0).bizType()).isEqualTo(FileBizType.AVATAR);
            verify(service, times(1)).findMyFiles(eq(100L), eq(FileBizType.AVATAR), any(Pageable.class));
        }

        @Test
        @DisplayName("未登录:返 UNAUTHORIZED")
        void unauthenticated_returns_unauthorized() {
            SecurityContextHolder.clearContext();
            Result<PageResult<FileMetadataVo>> result = controller.listMine(null, 1, 20);
            assertThat(result.getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
        }
    }

    // ─────────────────────────────────────────────
    //  getById
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /{id}")
    class GetById {

        @Test
        @DisplayName("owner 匹配 → 返 VO")
        void owner_match_returns_vo() {
            FileMetadata entity = stubEntity(7L, 100L, FileBizType.AVATAR, FileStatus.ACTIVE);
            when(service.findByIdForOwner(7L, 100L)).thenReturn(Optional.of(entity));

            Result<FileMetadataVo> result = controller.getById(7L);

            assertThat(result.getData().id()).isEqualTo(7L);
        }

        @Test
        @DisplayName("不存在 → 抛 BusinessException(FILE_NOT_FOUND)")
        void missing_throws_not_found() {
            when(service.findByIdForOwner(99L, 100L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.getById(99L))
                    .isInstanceOf(com.nexusforge.exception.BusinessException.class)
                    .extracting("code").isEqualTo(ResultCode.FILE_NOT_FOUND.getCode());
        }
    }

    // ─────────────────────────────────────────────
    //  deleteById
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /{id}")
    class DeleteById {

        @Test
        @DisplayName("owner=100 调 softDeleteById(id, 100)")
        void delegates_with_owner() {
            controller.deleteById(7L);
            verify(service, times(1)).softDeleteById(7L, 100L);
        }
    }

    // ─────────────────────────────────────────────
    //  adminSearch
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /admin")
    class AdminSearch {

        @Test
        @DisplayName("委派 adminSearch(owner, biz, status, pageable)")
        void delegates() {
            Page<FileMetadata> page = new PageImpl<>(
                    List.of(stubEntity(1L, 50L, FileBizType.ATTACHMENT, FileStatus.ACTIVE)),
                    PageRequest.of(0, 20), 1);
            when(service.adminSearch(eq(50L), eq(FileBizType.ATTACHMENT), eq(FileStatus.ACTIVE), any(Pageable.class)))
                    .thenReturn(PageResult.of(page));

            Result<PageResult<FileMetadataVo>> result = controller.adminSearch(
                    50L, FileBizType.ATTACHMENT, FileStatus.ACTIVE, 1, 20);

            assertThat(result.getData().getRecords()).hasSize(1);
            // 验证 owner 不被当前 user 覆盖
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(service, times(1)).adminSearch(
                    eq(50L), eq(FileBizType.ATTACHMENT), eq(FileStatus.ACTIVE), pageableCaptor.capture());
            // 默认按 createdAt desc 排序
            assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection().toString())
                    .isEqualTo("DESC");
        }
    }

    // ─────────────────────────────────────────────
    //  presignedPutUrl
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /presigned/put")
    class PresignedPutUrl {

        @Test
        @DisplayName("写 PENDING + 颁发 URL")
        void writes_pending_and_returns_url() {
            FileMetadata entity = stubEntity(11L, 100L, FileBizType.ATTACHMENT, FileStatus.PENDING);
            when(service.issueUploadCredential(eq(FileBizType.ATTACHMENT), eq(100L),
                    anyString(), anyString())).thenReturn(entity);
            when(service.generatePresignedPutUrl(anyString(), any(Integer.class)))
                    .thenReturn("https://s3.example/presigned-put-url");

            Result<String> result = controller.presignedPutUrl(
                    FileBizType.ATTACHMENT, "report.pdf", 600);

            assertThat(result.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
            assertThat(result.getData()).isEqualTo("https://s3.example/presigned-put-url");
            verify(service, times(1)).issueUploadCredential(
                    FileBizType.ATTACHMENT, 100L, "report.pdf", "application/octet-stream");
        }
    }
}

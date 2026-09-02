package com.nexusforge.event;

import com.nexusforge.config.StorageProperties;
import com.nexusforge.file.FileAccess;
import com.nexusforge.file.FileBizType;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import com.nexusforge.file.repository.FileMetadataRepository;
import com.nexusforge.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 Commit 4 单测 — {@link FileUserDataDeletionListener} GDPR 真删路径。
 *
 * <p>Mockito 隔离,无 Docker 依赖。验证:
 * <ul>
 *   <li>正常路径:逐个清 storage + 原生 SQL 物理删</li>
 *   <li>storage 失败不阻断(容错)</li>
 *   <li>null userId 早返</li>
 *   <li>整体失败不抛(主业务已注销,清不清是次要问题)</li>
 * </ul>
 */
class FileUserDataDeletionListenerTest {

    private FileMetadataRepository fileRepo;
    private StorageProvider storageProvider;
    private StorageProperties storageProps;
    private jakarta.persistence.EntityManager entityManager;
    private FileUserDataDeletionListener listener;

    @BeforeEach
    void setUp() {
        fileRepo = mock(FileMetadataRepository.class);
        storageProvider = mock(StorageProvider.class);
        storageProps = new StorageProperties();
        StorageProperties.VendorConfig vendor = new StorageProperties.VendorConfig();
        vendor.setBucket("test-bucket");
        storageProps.getRustfs().put("default", vendor);
        // deep stubs 让 createNativeQuery(...).setParameter(...).executeUpdate() 链式 mock 可行
        entityManager = mock(jakarta.persistence.EntityManager.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        listener = new FileUserDataDeletionListener(fileRepo, storageProvider, storageProps);
        // EntityManager 字段注入(@PersistenceContext 不能直接 new)
        ReflectionTestUtils.setField(listener, "entityManager", entityManager);
    }

    private FileMetadata stubRow(Long id, Long ownerId, String key) {
        FileMetadata f = new FileMetadata();
        f.setBucket("test-bucket");
        f.setObjectKey(key);
        f.setBizType(FileBizType.AVATAR);
        f.setAccess(FileAccess.PUBLIC);
        f.setOwnerId(ownerId);
        f.setStatus(FileStatus.ACTIVE);
        f.setSizeBytes(1024L);
        f.setOriginalFilename("x.png");
        f.setContentType("image/png");
        try {
            java.lang.reflect.Field idField = FileMetadata.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(f, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return f;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private org.mockito.stubbing.Answer<Integer> executeUpdateAnswer(int n) {
        return invocation -> {
            // 简化:所有调用返同一值
            return n;
        };
    }

    // ─────────────────────────────────────────────
    //  Cases
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("HappyPath")
    class HappyPath {

        @Test
        @DisplayName("逐个清 storage + 原生 SQL 物理删")
        void clears_storage_and_physically_deletes_rows() {
            // given
            FileMetadata r1 = stubRow(1L, 100L, "public/avatar/100/a.png");
            FileMetadata r2 = stubRow(2L, 100L, "private/attachment/100/b.pdf");
            when(fileRepo.findAllByOwnerId(100L)).thenReturn(List.of(r1, r2));
            // deep stubs 让 createNativeQuery → setParameter → executeUpdate 链通;
            // 不显式 stub executeUpdate(默认返 0),不验证数值,只验证 SQL 文本。

            // when
            listener.onUserDataDeletion(new UserDataDeletionEvent(100L));

            // then — storage 被清 2 次
            verify(storageProvider, times(1)).delete("test-bucket", "public/avatar/100/a.png");
            verify(storageProvider, times(1)).delete("test-bucket", "private/attachment/100/b.pdf");

            // 原生 SQL DELETE 调一次,带 userId
            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(entityManager, times(1)).createNativeQuery(sqlCaptor.capture());
            assertThat(sqlCaptor.getValue()).contains("DELETE FROM file_metadata")
                    .contains("owner_id = :userId");
        }

        @Test
        @DisplayName("空列表:不调 storage,但仍跑 SQL(可能含已软删)")
        void empty_rows_still_runs_sql() {
            when(fileRepo.findAllByOwnerId(100L)).thenReturn(List.of());

            listener.onUserDataDeletion(new UserDataDeletionEvent(100L));

            verify(storageProvider, never()).delete(anyString(), anyString());
            verify(entityManager, times(1)).createNativeQuery(anyString());
        }
    }

    @Nested
    @DisplayName("FaultTolerance")
    class FaultTolerance {

        @Test
        @DisplayName("storage 失败不阻断,继续物理删")
        void storage_failure_does_not_block_physical_delete() {
            FileMetadata r1 = stubRow(1L, 100L, "key-1");
            FileMetadata r2 = stubRow(2L, 100L, "key-2");
            when(fileRepo.findAllByOwnerId(100L)).thenReturn(List.of(r1, r2));
            // 第一次 storage.delete 抛错,第二次正常(void 方法用 doThrow/doNothing)
            doThrow(new RuntimeException("S3 down"))
                    .when(storageProvider).delete("test-bucket", "key-1");
            // key-2 走默认(无 stub 即不抛)

            listener.onUserDataDeletion(new UserDataDeletionEvent(100L));

            // 两次 delete 都调(没被失败中断)
            verify(storageProvider, times(1)).delete("test-bucket", "key-1");
            verify(storageProvider, times(1)).delete("test-bucket", "key-2");
            // 原生 SQL 仍跑
            verify(entityManager, times(1)).createNativeQuery(anyString());
        }

        @Test
        @DisplayName("整体异常被 catch,不抛给主业务")
        void overall_exception_caught() {
            // 强制让 findAllByOwnerId 抛
            when(fileRepo.findAllByOwnerId(100L))
                    .thenThrow(new RuntimeException("DB down"));

            // 不应抛
            listener.onUserDataDeletion(new UserDataDeletionEvent(100L));

            // 原生 SQL 没跑到
            verify(entityManager, never()).createNativeQuery(anyString());
        }
    }

    @Nested
    @DisplayName("NullUserId")
    class NullUserId {

        @Test
        @DisplayName("userId=null → 早返,不查 repo 不调 storage 不跑 SQL")
        void null_user_id_early_return() {
            listener.onUserDataDeletion(new UserDataDeletionEvent(null));

            verify(fileRepo, never()).findAllByOwnerId(any());
            verify(storageProvider, never()).delete(anyString(), anyString());
            verify(entityManager, never()).createNativeQuery(anyString());
        }
    }
}

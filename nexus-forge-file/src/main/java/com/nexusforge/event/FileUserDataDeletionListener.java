package com.nexusforge.event;

import com.nexusforge.config.StorageProperties;
import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.repository.FileMetadataRepository;
import com.nexusforge.storage.StorageProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文件模块用户数据删除监听器 —— 监听 {@link UserDataDeletionEvent},
 * 真删该用户全部 file_metadata 行 + 清理对象存储对象。
 *
 * <p>设计动机:user 模块不能直接 import file 模块 entity(模块方向保护),
 * 通过事件让 file 模块自主清理。</p>
 *
 * <p><b>为什么用原生 SQL 而不是 JPA 派生方法</b>:</p>
 * <ul>
 *   <li>{@code @SQLRestriction("deleted_at IS NULL")} 会让 JPA 派生
 *       {@code findByOwnerId} 过滤掉已软删的行 —— 但注销要清空所有数据,
 *       包括已软删的(参考 commit 5f368cf 的 restoreConversation 经验)</li>
 *   <li>用 {@link EntityManager#createNativeQuery} 走纯 JDBC 通道,
 *       不受 Hibernate 的 SQL 改写影响</li>
 * </ul>
 *
 * <p><b>删除顺序</b>:</p>
 * <ol>
 *   <li>先查 file_metadata 拿 (bucket, object_key) 列表 —— 这一步用
 *       {@code findByOwnerId} 已足够(已软删的不影响 storage 清理目标,
 *       本来就打算清;只需 owner 匹配)</li>
 *   <li>逐个调 storage.delete 清对象 —— 失败 log warn 不抛(GDPR 容错,
 *       对象清失败靠 storage 后台 gc 兜底,删 row 是法定的)</li>
 *   <li>原生 SQL DELETE FROM file_metadata WHERE owner_id = ? —— 物理删</li>
 * </ol>
 *
 * <p>顺序:先查后删。{@code @SQLRestriction} 拦的是 {@code findById} 等 JPA
 * 派生方法,本监听器直接用 {@code findByOwnerId} 拿当前未软删的 row +
 * 通过 ownerId 维度的原生 SQL 一锅端。已软删的 row 不会再被本方法清对象
 * (它们已没业务引用,storage 后台 gc 自然清),但仍会被原生 SQL 一并物理删。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileUserDataDeletionListener {

    private final FileMetadataRepository fileRepo;
    private final StorageProvider storageProvider;
    private final StorageProperties storageProps;

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    @Transactional
    public void onUserDataDeletion(UserDataDeletionEvent event) {
        Long userId = event.userId();
        if (userId == null) {
            log.warn("[file-data-deletion] event with null userId, ignored");
            return;
        }
        try {
            // 1. 查该用户全部未软删行(已软删的不返,避免重复清 storage)
            List<FileMetadata> rows = fileRepo.findAllByOwnerId(userId);
            String bucket = storageProps.getActive().getBucket();
            int storageCleared = 0;
            for (FileMetadata row : rows) {
                try {
                    storageProvider.delete(bucket, row.getObjectKey());
                    storageCleared++;
                } catch (Exception e) {
                    log.warn("[file-data-deletion] failed to delete object userId={} key={}: {}",
                            userId, row.getObjectKey(), e.getMessage());
                }
            }

            // 2. 物理删全部行(绕 @SQLRestriction,含已软删)
            int rowCount = entityManager.createNativeQuery(
                    "DELETE FROM file_metadata WHERE owner_id = :userId")
                    .setParameter("userId", userId)
                    .executeUpdate();

            log.info("[file-data-deletion] purged userId={} rows={} storageCleared={}",
                    userId, rowCount, storageCleared);
        } catch (Exception e) {
            // 不抛 —— 数据清理失败不影响主业务(账号已注销,数据没清是次要问题,
            // 可后续手工清理;与 AiUserDataDeletionListener 一致的容错策略)
            log.warn("[file-data-deletion] failed for userId={}: {}", userId, e.getMessage());
        }
    }
}

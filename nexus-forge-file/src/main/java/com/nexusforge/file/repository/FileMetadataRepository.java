package com.nexusforge.file.repository;

import com.nexusforge.file.entity.FileMetadata;
import com.nexusforge.file.entity.FileStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文件元数据仓库 —— 业务可查"我上传过的文件"的核心入口。
 *
 * <h3>典型查询</h3>
 * <ul>
 *   <li>{@link #findByOwnerIdAndStatusOrderByCreatedAtDesc}  — 我的文件分页(默认 ACTIVE)</li>
 *   <li>{@link #findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc}  — 按 biz 过滤(头像 / 附件 / AI 图片)</li>
 *   <li>{@link #findByBucketAndObjectKey}  — 凭证 confirm 路径,upsert 前先查</li>
 *   <li>{@link #findAllByOwnerId}  — GDPR 真删路径,跨状态扫描</li>
 * </ul>
 *
 * <p>软删由 {@code @SQLRestriction} 自动加 {@code WHERE deleted_at IS NULL};
 * 真删(物理删除)走 {@code EntityManager.createNativeQuery} 绕过。</p>
 */
@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {

    /**
     * 单 row 唯一查:confirm 路径用 —— 已存在则更新 etag / status,
     * 不存在则插入。
     */
    Optional<FileMetadata> findByBucketAndObjectKey(String bucket, String objectKey);

    /**
     * 我的文件分页(默认只查 ACTIVE)。{@code Pageable} 含排序,
     * 调用方传 {@code PageRequest.of(page, size, Sort.by(...))}。
     */
    Page<FileMetadata> findByOwnerIdAndStatusOrderByCreatedAtDesc(
            Long ownerId, FileStatus status, Pageable pageable);

    /**
     * 我的文件 + biz 过滤(头像 / 附件 / AI 图片分开展示)。
     */
    Page<FileMetadata> findByOwnerIdAndBizTypeAndStatusOrderByCreatedAtDesc(
            Long ownerId, com.nexusforge.file.FileBizType bizType,
            FileStatus status, Pageable pageable);

    /**
     * 管理员视角:按 owner 查询(跨状态,含已软删的 PENDING 凭证)。
     */
    @Query("SELECT f FROM FileMetadata f WHERE f.ownerId = :ownerId "
            + "AND (:bizType IS NULL OR f.bizType = :bizType) "
            + "AND (:status IS NULL OR f.status = :status) "
            + "ORDER BY f.createdAt DESC")
    Page<FileMetadata> adminSearch(
            @Param("ownerId") Long ownerId,
            @Param("bizType") com.nexusforge.file.FileBizType bizType,
            @Param("status") FileStatus status,
            Pageable pageable);

    /**
     * GDPR 真删路径:拿到某 user 全部元数据行(跨状态)做物理删除。
     * 配合 {@code EntityManager.createNativeQuery} 走物理删;
     * 派生方法仅供"先查后删"使用,避免 N+1。
     */
    List<FileMetadata> findAllByOwnerId(Long ownerId);
}

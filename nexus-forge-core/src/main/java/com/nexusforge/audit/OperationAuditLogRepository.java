package com.nexusforge.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 操作审计仓库 —— admin 查全表 + 业务查 owner 维度。
 *
 * <p>典型查询:</p>
 * <ul>
 *   <li>{@link #adminSearch}  —— admin 后台查,userId / action / resource 多维过滤</li>
 *   <li>{@link #findByUserIdOrderByCreatedAtDesc} —— 某用户最近操作历史</li>
 *   <li>{@link #findByResourceAndResourceIdOrderByCreatedAtDesc} —— 某资源所有操作</li>
 * </ul>
 */
@Repository
public interface OperationAuditLogRepository extends JpaRepository<OperationAuditLog, Long> {

    /**
     * Admin 多维过滤查询(全表)。{@code null} 维度的过滤项被跳过(类似
     * file_metadata 的 adminSearch 模式)。
     */
    @Query("SELECT a FROM OperationAuditLog a WHERE "
            + "(:userId IS NULL OR a.userId = :userId) "
            + "AND (:action IS NULL OR a.action = :action) "
            + "AND (:resource IS NULL OR a.resource = :resource) "
            + "ORDER BY a.createdAt DESC")
    Page<OperationAuditLog> adminSearch(
            @Param("userId") Long userId,
            @Param("action") String action,
            @Param("resource") String resource,
            Pageable pageable);

    /**
     * 某用户最近操作(分页,按 createdAt desc)。
     */
    Page<OperationAuditLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 某资源的所有操作历史(分页,按 createdAt desc)。
     */
    Page<OperationAuditLog> findByResourceAndResourceIdOrderByCreatedAtDesc(
            String resource, String resourceId, Pageable pageable);

    /**
     * 某用户最近 N 条(不分页,简单列表)。
     */
    List<OperationAuditLog> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}

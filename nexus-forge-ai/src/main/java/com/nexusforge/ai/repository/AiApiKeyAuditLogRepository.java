package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.AiApiKeyAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * AI vendor API Key 轮换审计仓库(Phase 8)。
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByMetadataVendorOrderByCreatedAtDesc} — 查某 vendor 的所有
 *       轮换记录(走 JSONB {@code metadata->>'vendor'} 索引,见 V20260902_008 GIN 索引)</li>
 *   <li>findAll(Pageable) — 全表分页(admin 通用审计视图,按时间倒序)</li>
 * </ul>
 *
 * <p>无 {@code @SQLDelete} / {@code @SQLRestriction}:审计永不删。
 */
public interface AiApiKeyAuditLogRepository extends JpaRepository<AiApiKeyAuditLog, Long> {

    /**
     * 按 vendor 名过滤(查 JSONB {@code metadata->>'vendor'}),按时间倒序分页。
     * <p>对应 GIN 索引 {@code idx_ai_api_key_audit_log_metadata_vendor}
     * (迁移 V20260902_008);{@code ->>'}'} 表达式对该索引可走 index scan。
     */
    @Query(value = "SELECT a FROM ai_api_key_audit_log a " +
                    "WHERE a.metadata->>'vendor' = :vendor " +
                    "ORDER BY a.created_at DESC",
            countQuery = "SELECT count(*) FROM ai_api_key_audit_log a " +
                    "WHERE a.metadata->>'vendor' = :vendor",
            nativeQuery = true)
    Page<AiApiKeyAuditLog> findByMetadataVendorOrderByCreatedAtDesc(
            @Param("vendor") String vendor, Pageable pageable);
}

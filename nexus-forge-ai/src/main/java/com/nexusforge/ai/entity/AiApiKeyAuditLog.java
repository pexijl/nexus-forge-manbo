package com.nexusforge.ai.entity;

import com.nexusforge.ai.enums.VendorApiKeyAuditAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * AI vendor 系统 API Key 轮换审计日志实体(Phase 8) ——
 * 映射 {@code ai_api_key_audit_log} 表。
 *
 * <p>每次 admin 通过 {@code PUT/DELETE /api/admin/ai/vendors/{v}/api-key} 改 Key,
 * 同步写一条审计行(metadata 含 vendor / fingerprint_before / fingerprint_after /
 * request_ip)。不改 {@code ai_vendor_config},只追加。
 *
 * <h3>跟 {@code AccountLifecycleLog} 的差异</h3>
 * <ul>
 *   <li>无 FK 到 {@code users.id} — 审计表不该被业务表删了级联丢,
 *       真删 users 时审计必须保留(合规追溯) — 同生命周期表</li>
 *   <li>actorRole 用 String(不引 enum)— 跨模块加依赖不值;运营只查"ADMIN 操作"等
 *       几个固定值,无强校验需求</li>
 *   <li>action 用 {@code VendorApiKeyAuditAction} 强校验(SET / CLEAR)</li>
 *   <li>vendor 名进 metadata(冗余,便于按 vendor 过滤),不进顶层列</li>
 * </ul>
 *
 * <h3>设计取舍</h3>
 * <ul>
 *   <li>无 {@code BaseEntity} 继承(审计表不软删、不需要 updatedAt)</li>
 *   <li>不暴露 {@code @SQLDelete} / {@code @SQLRestriction} — 审计永不删</li>
 *   <li>{@code createdAt} 走 {@code @PrePersist} 自填(不依赖 DB DEFAULT,理由跟
 *       {@code AccountLifecycleLog} 一样 — 容器复用场景下 IF NOT EXISTS 路径不会
 *       重建表,DB DEFAULT 行为不可靠)</li>
 * </ul>
 *
 * <p>对应表 {@code ai_api_key_audit_log},迁移 V20260902_008。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_api_key_audit_log")
public class AiApiKeyAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private VendorApiKeyAuditAction action;

    /** 操作人 id — 来自 SecurityContext.UserPrincipal.userId;NULL = SYSTEM 内部事件 */
    @Column(name = "actor_id")
    private Long actorId;

    /** 操作人角色 — "ADMIN" / "SYSTEM"(简单字符串,不引 enum 跨模块依赖) */
    @Column(name = "actor_role", nullable = false, length = 16)
    private String actorRole;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /**
     * JSONB 上下文:典型 key —
     * <ul>
     *   <li>{@code vendor} — 被操作的 vendor 名(冗余存,便于按 vendor 过滤)</li>
     *   <li>{@code fingerprint_before} — 改前 fingerprint(CLEAR 时为已有密文指纹;SET 第一次为 null)</li>
     *   <li>{@code fingerprint_after} — 改后 fingerprint(SET 时为新密文指纹;CLEAR 为 null)</li>
     *   <li>{@code request_ip} — HTTP 客户端 IP(从 {@code HttpServletRequest} 拿)</li>
     * </ul>
     * 通过对比 fingerprint_before vs fingerprint_after 可推断
     * "新装 / 轮换 / 清空"。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

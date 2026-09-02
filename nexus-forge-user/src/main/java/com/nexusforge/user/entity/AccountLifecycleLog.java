package com.nexusforge.user.entity;

import com.nexusforge.user.enums.AccountActorRole;
import com.nexusforge.user.enums.AccountLifecycleAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 账号生命周期审计日志实体 —— 映射 {@code account_lifecycle_log} 表。
 *
 * <p>无 {@code BaseEntity} 继承(审计表不软删、不需要 updatedAt),
 * created_at 直接由 DB DEFAULT now() 填。</p>
 *
 * <p>{@code metadata} 字段用 {@link SqlTypes#JSON} 映射 PostgreSQL JSONB,
 * 通过 Hibernate 6 的 {@link JdbcTypeCode} 声明。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "account_lifecycle_log")
public class AccountLifecycleLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private AccountLifecycleAction action;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 16)
    private AccountActorRole actorRole;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * 不依赖 DB DEFAULT —— 容器复用场景下,首迁时建的表有 DEFAULT,
     * 但 reuse 容器跨多次 IT 启动后 schema 已固化,JPA 跳 INSERT 该列时
     * DB 不会自动填 DEFAULT(只对 ALTER TABLE ADD DEFAULT 之前的列没值;
     * Postgres 对显式列缺失会触发 DEFAULT,但 reuse 容器 IF NOT EXISTS 路径
     * 不会重建表)。改由 entity 自己填,保证正确性。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

package com.nexusforge.base;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 实体基类 —— 审计时间戳 + 软删除统一基础设施。
 *
 * <h3>审计字段</h3>
 * <ul>
 *   <li>{@code createdAt}  创建时间,UTC,@PrePersist 填充,不可改</li>
 *   <li>{@code updatedAt}  最近修改时间,UTC,@PrePersist / @PreUpdate 自动刷新</li>
 * </ul>
 *
 * <h3>软删除(Hibernate 6.3+ 标准做法)</h3>
 * <ul>
 *   <li>{@code deletedAt}  软删除时间,UTC;NULL = 活,非 NULL = 已删</li>
 *   <li>{@code @SQLDelete}     —— {@code repo.delete(entity)} 自动转
 *       {@code UPDATE ... SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL},
 *       避免 Hibernate 抛 {@code TransientObjectException} 之类副作用</li>
 *   <li>{@code @SQLRestriction} —— 所有派生查询自动加
 *       {@code WHERE deleted_at IS NULL} 过滤,业务侧零改动</li>
 * </ul>
 *
 * <h3>使用约束</h3>
 * <ul>
 *   <li>基类**只放公共字段与公共约束**,不放业务方法;{@code restore()} / 业务级
 *       软删入口由 service 层显式提供,避免乱调</li>
 *   <li>真删(物理删除)请保留原 {@code @Modifying @Query("DELETE ...")}
 *       派生方法,作为合规/迁移场景的"逃生通道";{@code repo.delete(entity)}
 *       默认走软删</li>
 *   <li>{@code @SQLRestriction} 会被 JPA 派生查询自动加;若需查"含已删"
 *       列表,用 {@code findByDeletedAtIsNotNull} 或 service 层
 *       {@code entityManager.createNativeQuery} 显式绕过</li>
 * </ul>
 *
 * @see <a href="https://docs.jboss.org/hibernate/orm/6.3/javadocs/org/hibernate/annotations/SQLDelete.html">@SQLDelete</a>
 * @see <a href="https://docs.jboss.org/hibernate/orm/6.3/javadocs/org/hibernate/annotations/SQLRestriction.html">@SQLRestriction</a>
 */
@Getter
@Setter
@MappedSuperclass
@SQLDelete(sql = "UPDATE {h-table} SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL")
@SQLRestriction("deleted_at IS NULL")
public class BaseEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 软删除时间;NULL = 活,非 NULL = 已软删(由 {@code @SQLDelete} 自动写入) */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 软删除是否生效 */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}

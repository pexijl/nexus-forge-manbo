package com.nexusforge.entity;

import com.nexusforge.base.BaseEntity;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * AI 对话实体。一个用户可以有多个对话,每个对话绑定一个模型。
 *
 * <h3>软删除</h3>
 * 软删注解(<b>必须直接放在 {@code @Entity} 上</b>,不能只在
 * {@code @MappedSuperclass BaseEntity} 上 —— Hibernate 6 不从父类继承
 * 软删 SQL 改写语义):
 * <ul>
 *   <li>{@code @SQLDelete}     —— {@code repo.delete(this)} 拦截并转
 *       {@code UPDATE ai_conversations SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL}</li>
 *   <li>{@code @SQLRestriction} —— 所有查询自动加 {@code WHERE deleted_at IS NULL}</li>
 * </ul>
 *
 * <p>注意:这两个注解不能从 {@code BaseEntity} 继承,必须在每个
 * {@code @Entity} 上显式声明。如果后续有新的继承实体接入软删除,
 * 复制这两个注解到对应类即可。</p>
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "ai_conversations")
@SQLDelete(sql = "UPDATE ai_conversations SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL")
@SQLRestriction("deleted_at IS NULL")
public class AiConversation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(nullable = false)
    private Boolean pinned = false;
}
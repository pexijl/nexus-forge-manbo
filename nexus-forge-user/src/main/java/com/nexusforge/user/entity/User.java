package com.nexusforge.user.entity;

import com.nexusforge.base.BaseEntity;
import com.nexusforge.enums.Role;
import com.nexusforge.enums.UserStatus;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户实体类
 *
 * <p>软删除注解必须直接放在 {@code @Entity} 上(Hibernate 6 不从
 * {@code @MappedSuperclass} 继承 SQL 改写语义)。{@code repo.delete(user)}
 * 拦截转 UPDATE;查询自动过滤已软删。</p>
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "users")
@SQLDelete(sql = "UPDATE users SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL")
@SQLRestriction("deleted_at IS NULL")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    private String nickname;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "avatar_key", length = 500)
    private String avatarKey;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.of(Role.USER);

    @Column(name = "plan_quota_override", columnDefinition = "TEXT")
    private String planQuotaOverride;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;
}
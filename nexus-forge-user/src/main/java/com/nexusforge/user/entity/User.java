package com.nexusforge.user.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户实体类
 */
@Data
@Entity
@Table(name = "users")
public class User {
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

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private Integer status = 1;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
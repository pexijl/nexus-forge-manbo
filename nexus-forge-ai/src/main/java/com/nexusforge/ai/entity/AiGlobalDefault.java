package com.nexusforge.ai.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * AI 全局默认 vendor/model 单行表。
 *
 * <p>所有未配置私 Key 的用户走这里(系统共享 Key,免费模式)。
 * 管理员可在 AiAdminController 修改(只能改 vendor / model / enabled;id 永远 = 1)。
 *
 * <p>对应表 {@code ai_global_default},迁移 V20260801_001。
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Entity
@Table(name = "ai_global_default")
public class AiGlobalDefault {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    @Column(nullable = false, length = 32)
    private String vendor;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
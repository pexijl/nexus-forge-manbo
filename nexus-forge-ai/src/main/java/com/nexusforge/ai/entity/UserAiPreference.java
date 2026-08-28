package com.nexusforge.ai.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * 用户 AI 个性化配置(每用户最多 1 行)。
 *
 * <p>三态语义(由 {@code encryptedApiKey} 是否非空决定):
 * <ul>
 *   <li>行不存在 → 走 {@link AiGlobalDefault} + 系统共享 Key</li>
 *   <li>行存在且 {@code encryptedApiKey == null} → 用本行的 vendor/model + 系统共享 Key
 *       (用户只覆盖 vendor/model,不计私 Key)</li>
 *   <li>行存在且 {@code encryptedApiKey != null} → 用本行的 vendor/model + 私 Key(用户自付)</li>
 * </ul>
 *
 * <p>对应表 {@code user_ai_preference},迁移 V20260801_002。
 */
@Getter
@Setter
@ToString(exclude = "encryptedApiKey")   // 密文不进日志
@EqualsAndHashCode(of = "userId")
@Entity
@Table(name = "user_ai_preference")
public class UserAiPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 32)
    private String vendor;

    @Column(nullable = false, length = 128)
    private String model;

    /**
     * 用户私 Key 的 AES-256-GCM 密文。格式:iv(12B) || ciphertext || tag(16B)。
     * NULL = 用系统共享 Key(走 ai_global_default + yaml)。
     */
    @Column(name = "encrypted_api_key")
    private byte[] encryptedApiKey;

    /** 仅展示:Key 前 4 字符 + sha256 前 8 hex;不暴露真值 */
    @Column(name = "api_key_fingerprint", length = 16)
    private String apiKeyFingerprint;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
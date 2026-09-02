package com.nexusforge.ai.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * 用户 AI 私有代理端点(Phase 3 用户级 BYOK 多端点)。
 *
 * <p>一用户可拥有 N 个独立 AI 代理,每个代理有:
 * <ul>
 *   <li>独立 {@code baseUrl}(覆盖 vendor 默认)</li>
 *   <li>独立加密 API Key(必填;BYOK 场景不允许"建代理没 Key")</li>
 *   <li>可选 {@code defaultModel}(留空走 vendor yaml 默认)</li>
 *   <li>{@code isDefault} 标记:用户的"当前活跃代理",即"个人偏好绑定"</li>
 * </ul>
 *
 * <p><b>与 {@link UserAiPreference} 的关系</b>:
 * {@code UserAiPreference} 是 Phase 1-2 的旧"单偏好行"模型(user_id 主键),
 * 只支持 1 vendor + 1 model + 1 apiKey(且无 baseUrl 概念,只能走 vendor 默认 URL);
 * 本表是 Phase 3 新"多代理"模型,支持 N 个独立端点 + 独立 baseUrl。
 *
 * <p><b>共存策略</b>:Phase 3 不迁移旧数据,新用户用本表;旧用户在 {@code PreferenceResolver}
 * 里"有本表默认代理 → 走代理,无默认代理 → 回退旧 preference 行";两套机制并存,
 * Phase 4 视情况做一次性迁移 + 弃用旧表。
 *
 * <h3>软删除策略</h3>
 * 跟 {@link AiModelCatalog} / {@link AiVendorConfig} 一致:硬删除。代理是用户
 * 主动管理的配置,不是业务数据;误删由用户重新创建(无 audit log — Phase 4
 * 视情况加 audit 表)。
 *
 * <h3>为什么不用 {@code BaseEntity}</h3>
 * {@code BaseEntity} 强制带 {@code deletedAt} + {@code @SQLDelete} +
 * {@code @SQLRestriction},跟"硬删除"策略冲突;手动管理 {@code createdAt} /
 * {@code updatedAt} 字段跟同模块其他 entity 风格一致(都是
 * {@code insertable=false, updatable=false},由 DB DEFAULT 填充)。
 *
 * <p>对应表 {@code user_ai_proxy},迁移 V20260902_004。
 */
@Getter
@Setter
@ToString(exclude = "encryptedApiKey")   // 密文不进 toString / 日志
@EqualsAndHashCode(of = "id")
@Entity
@Table(name = "user_ai_proxy")
public class UserAiProxy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 用户自定义别名(同 user 内唯一) */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * OpenAI 协议家族 vendor。由 {@code AiVendorRegistry.OPENAI_COMPATIBLE_VENDORS}
     * 在 service 层校验;anthropic 不在本集合,创建时直接拒绝。
     */
    @Column(nullable = false, length = 32)
    private String vendor;

    /** 独立 base URL(覆盖 vendor 默认) */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /**
     * API Key AES-256-GCM 密文(iv 12B || ciphertext || tag 16B)。
     * 必填:BYOK 场景下"建代理没 Key"没意义。
     */
    @Column(name = "encrypted_api_key", nullable = false)
    private byte[] encryptedApiKey;

    /** 仅展示:Key 前 4 字符 + sha256 前 8 hex;不暴露真值 */
    @Column(name = "api_key_fingerprint", nullable = false, length = 16)
    private String apiKeyFingerprint;

    /** 可选:该 proxy 默认 model(留空走 vendor yaml 默认) */
    @Column(name = "default_model", length = 128)
    private String defaultModel;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /**
     * 用户的"当前活跃代理"。每用户最多 1 个,DB 层 partial unique index +
     * app 层 {@code setDefault} 事务双层防御。
     */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = Boolean.FALSE;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}

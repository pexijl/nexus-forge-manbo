package com.nexusforge.ai.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * AI vendor 配置(Phase 2 多模型管理;Phase 6 起含 system api_key 持久化)。
 *
 * <p>一 vendor 一行(vendor UNIQUE);控制 vendor 的 base_url + enabled + 可选
 * system api_key。admin 通过 {@code AiAdminVendorController} 修改;运行时由
 * {@code VendorConfigService} 读 + Caffeine 缓存 + 事件失效。
 *
 * <h3>跟 model catalog 的关系</h3>
 * <ul>
 *   <li>{@code ai_vendor_config} 管"vendor 是否可用 + base URL + 可选 system api_key"</li>
 *   <li>{@code ai_model_catalog} 管"具体 model 的元信息 + enabled"</li>
 *   <li>网关层校验顺序:先 vendor(粗粒度),后 model catalog(细粒度)</li>
 * </ul>
 *
 * <h3>Phase 6 新增字段</h3>
 * <ul>
 *   <li>{@code encrypted_api_key} BYTEA — AES-256-GCM 密文(iv 12B || ct || tag 16B);
 *       NULL 时系统 Key 路径回退 yaml;非 NULL 时 {@code SystemKeyChatModelFactory} 解密后使用,
 *       改完立即生效(事件清本类 cache)</li>
 *   <li>{@code api_key_fingerprint} VARCHAR(16) — UI 展示用,形如 {@code sk-1••••a3b4c5d6},
 *       跟密文协同存在(CHECK 约束保证密文存在时指纹必存在)</li>
 * </ul>
 *
 * <h3>设计取舍</h3>
 * <ul>
 *   <li><b>不用软删</b>(跟 model catalog 保持一致):配置数据,直接真删;
 *       配合 yaml seed 备份</li>
 *   <li><b>不暴露给 {@code @SQLDelete} / {@code @SQLRestriction}</b>:基类继承
 *       不带这两个,本实体也不引入(避免真删被改写语义)</li>
 *   <li><b>不继承 BaseEntity</b>:本表无 deletedAt;手动管理时间字段,跟
 *       {@code AiGlobalDefault} / {@code UserAiPreference} 风格一致</li>
 *   <li><b>{@code @JsonIgnore} 密文</b>:防御性,避免 Jackson 序列化误暴露密文
 *       (VO 走 {@code VendorConfigVo.from(...)} 显式只透 fingerprint + hasApiKey)</li>
 * </ul>
 *
 * <p>对应表 {@code ai_vendor_config},迁移 V20260902_003 + V20260902_006。
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "vendor")
@Entity
@Table(name = "ai_vendor_config")
public class AiVendorConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String vendor;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Phase 6 — 系统 API Key AES-256-GCM 密文(iv 12B || ciphertext || tag 16B)。
     * <p>NULL 表示"未在 DB 覆盖",系统 Key 路径走 yaml 兜底;
     * 非 NULL 时 {@code VendorConfigService.getEffectiveApiKey} 解密后返回明文。
     * <p>{@code @JsonIgnore} 避免 Jackson 意外序列化密文 — VO 用
     * {@code api_key_fingerprint} + {@code hasApiKey} 替代展示。
     */
    @JsonIgnore
    @Column(name = "encrypted_api_key")
    private byte[] encryptedApiKey;

    /**
     * Phase 6 — API Key 指纹,形如 {@code sk-1••••a3b4c5d6},只用于 UI 展示。
     * <p>CHECK 约束:密文存在时指纹必存在(由 {@code VendorConfigService} 写路径
     * 协同维护);指纹单独存在是合法(历史 / 异常场景,getEffectiveApiKey 此时返 null)。
     */
    @Column(name = "api_key_fingerprint", length = 16)
    private String apiKeyFingerprint;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}

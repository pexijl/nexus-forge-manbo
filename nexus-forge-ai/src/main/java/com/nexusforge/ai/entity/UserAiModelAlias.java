package com.nexusforge.ai.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

/**
 * 用户 model alias 实体(Phase 4 模型别名)。
 *
 * <p>1 user ↔ N 个 alias,每个 alias = (alias 名 → target vendor + target model)。
 * 用户在 chat 请求里把 {@code model} 字段填 alias 名(不带冒号),
 * {@code PreferenceResolver} 命中后改写为 {@code "target_vendor:target_model"}
 * 走原优先级链(系统 Key / BYOK 代理 / 默认代理 / global default)。
 *
 * <p><b>典型用例</b>:
 * <ul>
 *   <li>用户不记 vendor/model 原始名,UI 选"我的 GPT" / "快速响应"等友好名</li>
 *   <li>用户想"快速切换 vendor"——改 alias.target_vendor,所有引用该 alias 的调用切到新 vendor</li>
 *   <li>BYOK + 别名组合:alias target=deepseek / deepseek-v3 + 用户有 deepseek 代理 →
 *       自动走 USER_PRIVATE_KEY(沿用 Phase 3 解析链)</li>
 * </ul>
 *
 * <h3>软删除策略</h3>
 * 跟 {@link AiModelCatalog} / {@link AiVendorConfig} / {@link UserAiProxy} 一致:硬删除。
 * alias 是用户主动管理的配置,不是业务数据;误删由用户重新创建。
 *
 * <h3>为什么不用 {@code BaseEntity}</h3>
 * {@code BaseEntity} 强制带 {@code deletedAt} + {@code @SQLDelete} +
 * {@code @SQLRestriction},跟"硬删除"策略冲突;手动管理 {@code createdAt} /
 * {@code updatedAt} 字段跟同模块其他 entity 风格一致(都是
 * {@code insertable=false, updatable=false},由 DB DEFAULT 填充)。
 *
 * <p>对应表 {@code user_ai_model_alias},迁移 V20260902_005。
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Entity
@Table(name = "user_ai_model_alias")
public class UserAiModelAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 用户友好别名(同 user 内唯一,不含冒号)。
     * <p>service 层校验不能含冒号(与 "vendor:model" 格式区分,避免歧义)。
     */
    @Column(nullable = false, length = 64)
    private String alias;

    /** 解析目标:alias 命中后改写为 "target_vendor:target_model" */
    @Column(name = "target_vendor", nullable = false, length = 32)
    private String targetVendor;

    @Column(name = "target_model", nullable = false, length = 128)
    private String targetModel;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * false 时 alias 跳过(fall through 到原优先级,实现"草稿/未启用"语义)。
     * 用 enabled 而不是软删:用户可以暂时禁用某个 alias,之后重新启用,无需重新创建。
     */
    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}

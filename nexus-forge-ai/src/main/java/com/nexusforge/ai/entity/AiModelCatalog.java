package com.nexusforge.ai.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * AI 模型目录实体(Phase 1 多模型管理 source of truth)。
 *
 * <p>管理员通过 {@code AiAdminModelController} CRUD;运行时由
 * {@code ModelCatalogService} 读 + Caffeine 缓存(5 min TTL + 事件失效)。
 * 网关层 {@code LlmClient} 在每次调用前查 catalog 校验:
 * <ul>
 *   <li>不存在 → {@code LLM_MODEL_NOT_FOUND(3002)}</li>
 *   <li>存在但 {@code enabled=false} → {@code LLM_MODEL_DISABLED(3011)}</li>
 * </ul>
 *
 * <h3>软删除策略</h3>
 * 故意<b>不用</b>软删除:model catalog 是配置数据,不是用户业务数据。
 * admin 误删可重新 INSERT(配合 yaml seed 备份 + audit log 记录操作);
 * 软删会让 admin UI 看到一堆 "已删除" 干扰,得不偿失。
 *
 * <h3>为什么不用 {@code BaseEntity}</h3>
 * {@code BaseEntity} 强制带 {@code deletedAt} + {@code @SQLDelete} + {@code @SQLRestriction},
 * 跟"硬删除"策略冲突。手动管理 {@code createdAt} / {@code updatedAt} 字段
 * 跟 {@code AiGlobalDefault} / {@code UserAiPreference} 风格一致(都是
 * {@code insertable=false, updatable=false},由 DB DEFAULT 填充)。
 *
 * <p>对应表 {@code ai_model_catalog},迁移 V20260902_002。
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Entity
@Table(name = "ai_model_catalog")
public class AiModelCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String vendor;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "context_window")
    private Integer contextWindow;

    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @Column(name = "supports_vision", nullable = false)
    private Boolean supportsVision = Boolean.FALSE;

    @Column(name = "supports_tools", nullable = false)
    private Boolean supportsTools = Boolean.TRUE;

    @Column(name = "supports_streaming", nullable = false)
    private Boolean supportsStreaming = Boolean.TRUE;

    @Column(name = "cost_input_per_1k", precision = 10, scale = 6)
    private BigDecimal costInputPer1k;

    @Column(name = "cost_output_per_1k", precision = 10, scale = 6)
    private BigDecimal costOutputPer1k;

    @Column(nullable = false, length = 32)
    private String tier = "STANDARD";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}

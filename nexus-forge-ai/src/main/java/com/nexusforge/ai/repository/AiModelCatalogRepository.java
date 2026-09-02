package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.AiModelCatalog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * AI 模型目录仓库。
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByVendorAndModelName} — 网关运行时的 catalog 校验热路径</li>
 *   <li>{@link #findAllByEnabledTrue} — {@code /api/ai/models/available} 公共列表</li>
 *   <li>{@link #findPage} — admin 分页 + 过滤(vendor / enabled)</li>
 *   <li>{@link #existsByVendorAndModelName} — 创建时唯一性预检(避免抛 DataIntegrityViolation 难处理)</li>
 * </ul>
 *
 * <p>无 {@code @SQLDelete} / {@code @SQLRestriction}:model catalog 走硬删除
 * 策略(见 {@link AiModelCatalog} 类 Javadoc),仓库不暴露软删派生方法。
 */
public interface AiModelCatalogRepository extends JpaRepository<AiModelCatalog, Long> {

    /**
     * 网关运行时校验热路径(被 {@code ModelCatalogService.findByVendorModel} 包装
     * Caffeine 缓存后调用,5 min TTL + 事件失效)。
     */
    Optional<AiModelCatalog> findByVendorAndModelName(String vendor, String modelName);

    /**
     * 公共可用模型列表(enabled=true 的全集)。给 {@code /api/ai/models/available} 用,
     * 排序按 vendor asc, model_name asc(让结果稳定可分页)。
     */
    @Query("SELECT m FROM AiModelCatalog m WHERE m.enabled = true ORDER BY m.vendor ASC, m.modelName ASC")
    List<AiModelCatalog> findAllByEnabledTrue();

    /**
     * 启用且指定 vendor 的列表。Phase 2 vendor config 上线后,组合 vendor enabled 过滤用。
     */
    List<AiModelCatalog> findByVendorAndEnabledTrueOrderByModelNameAsc(String vendor);

    /**
     * admin 列表分页 + 过滤。过滤条件:
     * <ul>
     *   <li>{@code vendor} 为空 → 不加 vendor 条件</li>
     *   <li>{@code enabled} 为空 → 不过滤 enabled 状态</li>
     * </ul>
     * 排序固定按 id desc(新加的在前),分页由 Pageable 控制。
     *
     * <p>用 {@code @Query} 显式写 JPQL 而不是依赖方法名派生:参数可能为 null
     * 时方法名派生无法表达"条件可选",只能写 JPQL。
     */
    @Query("SELECT m FROM AiModelCatalog m " +
            "WHERE (:vendor IS NULL OR m.vendor = :vendor) " +
            "AND (:enabled IS NULL OR m.enabled = :enabled) " +
            "ORDER BY m.id DESC")
    Page<AiModelCatalog> findPage(@Param("vendor") String vendor,
                                  @Param("enabled") Boolean enabled,
                                  Pageable pageable);

    /**
     * 创建前的唯一性预检。避免依赖
     * {@code DataIntegrityViolationException} 抛错(异常信息暴露 SQL 细节
     * + 不可控,业务层显式预检更清晰)。
     */
    boolean existsByVendorAndModelName(String vendor, String modelName);

    /**
     * 启动期 seed 判断:catalog 是否完全空?
     * {@code count() > 0} → 已 seed 过,跳过;否则跑 yaml seed 一次。
     */
    @Override
    long count();
}

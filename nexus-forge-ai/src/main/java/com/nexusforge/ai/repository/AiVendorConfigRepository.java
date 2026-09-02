package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.AiVendorConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AI vendor 配置仓库。
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByVendor} — 网关运行时热路径(被 VendorConfigService 包装 Caffeine 缓存)</li>
 *   <li>{@link #findAllByOrderByVendorAsc} — admin 列表</li>
 *   <li>{@link #existsByVendor} — 创建时唯一性预检</li>
 * </ul>
 *
 * <p>无 {@code @SQLDelete} / {@code @SQLRestriction}:vendor config 走硬删除策略
 * (见 {@link AiVendorConfig} 类 Javadoc),yaml 是兜底种子。
 */
public interface AiVendorConfigRepository extends JpaRepository<AiVendorConfig, Long> {

    /**
     * 网关运行时校验热路径(被 VendorConfigService.findByVendor 包装
     * Caffeine 缓存后调用)。
     */
    Optional<AiVendorConfig> findByVendor(String vendor);

    /**
     * admin 列表,按 vendor 排序(让结果稳定可分页)。
     */
    List<AiVendorConfig> findAllByOrderByVendorAsc();

    /**
     * 创建前的唯一性预检。避免依赖 DataIntegrityViolationException 抛错。
     */
    boolean existsByVendor(String vendor);

    /**
     * 启动期 seed 判断:vendor config 是否完全空?
     * count() > 0 → 已 seed 过,跳过;否则跑 yaml seed 一次。
     */
    @Override
    long count();
}

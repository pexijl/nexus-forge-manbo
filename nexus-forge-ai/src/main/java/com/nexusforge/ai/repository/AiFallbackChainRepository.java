package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.AiFallbackChain;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * AI 降级链仓库(Phase 7)。
 *
 * <p>全局唯一一行表(单行,id=1 哨兵),服务层永远 upsert id=1,
 * 不暴露 findAll 入口 — 跟 {@code AiGlobalDefaultRepository} 风格一致。
 *
 * <p>无 {@code @SQLDelete} / {@code @SQLRestriction}:配置数据,直接真删;
 * 删除等价于"重置为 yaml 兜底",由 {@code FallbackChainService.clear} 控制。
 */
public interface AiFallbackChainRepository extends JpaRepository<AiFallbackChain, Integer> {
    // 永远只查 id=1
}

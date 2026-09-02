package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.UserAiProxy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 用户 AI 代理仓库(Phase 3 用户级 BYOK)。
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByIdAndUserId} — 单条详情 + 所有权校验(防越权)</li>
 *   <li>{@link #findByUserIdOrderByIsDefaultDescNameAsc} — 用户的代理列表(is_default 优先,alias 字典序)</li>
 *   <li>{@link #findByUserIdAndIsDefaultTrue} — 用户当前活跃代理({@code PreferenceResolver} 解析用)</li>
 *   <li>{@link #existsByUserIdAndName} — 创建前 alias 唯一性预检</li>
 *   <li>{@link #countByUserIdAndIsDefaultTrue} — setDefault 事务里"重置其他"用</li>
 * </ul>
 *
 * <p>无 {@code @SQLDelete} / {@code @SQLRestriction}:用户代理走硬删除策略
 * (见 {@link UserAiProxy} 类 Javadoc)。
 */
public interface UserAiProxyRepository extends JpaRepository<UserAiProxy, Long> {

    /**
     * 单条查询 + 所有权校验。
     * <p>ID 是自增主键,理论全局唯一,但仍用 {@code userId} 二次过滤避免越权
     * (用户 A 不能通过遍历 ID 读用户 B 的代理)。
     */
    Optional<UserAiProxy> findByIdAndUserId(Long id, Long userId);

    /**
     * 列出用户的所有代理,is_default 优先(用户的"当前活跃代理"排第一位),
     * 其余按 alias 字典序;UI 列表天然高亮默认项。
     */
    List<UserAiProxy> findByUserIdOrderByIsDefaultDescNameAsc(Long userId);

    /**
     * 用户的"当前活跃代理"(在 {@code PreferenceResolver} 解析无 explicit proxy/model 时用)。
     * 用户的 is_default 通常 0 或 1 个(partial unique index 保证);用 Optional 处理 0 个。
     */
    Optional<UserAiProxy> findByUserIdAndIsDefaultTrue(Long userId);

    /**
     * 创建前唯一性预检 — 避免抛 {@code DataIntegrityViolationException}
     * 暴露 SQL 细节。返回 true 表示 (userId, name) 已被占用,应直接 409。
     */
    boolean existsByUserIdAndName(Long userId, String name);

    /**
     * 切换默认时,事务里"先把同 user 下的其他 is_default=true 改成 false"用。
     * <p>注意:理论最多 1 行(partial unique index),但 app 层强制保险。
     */
    long countByUserIdAndIsDefaultTrue(Long userId);
}

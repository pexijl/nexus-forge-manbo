package com.nexusforge.ai.repository;

import com.nexusforge.ai.entity.UserAiModelAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 用户 model alias 仓库(Phase 4 模型别名)。
 *
 * <p>核心查询:
 * <ul>
 *   <li>{@link #findByUserIdAndAliasIgnoreCase} — 网关运行时 alias 解析热路径
 *       (大小写不敏感:用户 UI 输 "我的 GPT" / "我的 gpt" / "我的 GPT " 都算同一个)</li>
 *   <li>{@link #findByIdAndUserId} — 单条详情 + 所有权校验(防越权)</li>
 *   <li>{@link #findByUserIdOrderByAliasAsc} — 用户的 alias 列表(UI 展示用)</li>
 *   <li>{@link #existsByUserIdAndAliasIgnoreCase} — 创建前 alias 唯一性预检</li>
 * </ul>
 *
 * <p>无 {@code @SQLDelete} / {@code @SQLRestriction}:alias 走硬删除策略
 * (见 {@link UserAiModelAlias} 类 Javadoc)。
 */
public interface UserAiModelAliasRepository extends JpaRepository<UserAiModelAlias, Long> {

    /**
     * 网关运行时 alias 解析热路径(被 {@code UserAiModelAliasService.findByUserIdAndAlias}
     * 包装 Caffeine 缓存后调用,5 min TTL + 事件失效)。
     * <p>大小写不敏感,前后 trim 由 service 层处理;本查询只走 IgnoreCase。
     */
    Optional<UserAiModelAlias> findByUserIdAndAliasIgnoreCase(Long userId, String alias);

    /**
     * 单条查询 + 所有权校验(防越权,跟 {@code UserAiProxyRepository} 同模式)。
     */
    Optional<UserAiModelAlias> findByIdAndUserId(Long id, Long userId);

    /**
     * 用户的 alias 列表(UI 展示用,按 alias 名字典序)。
     */
    List<UserAiModelAlias> findByUserIdOrderByAliasAsc(Long userId);

    /**
     * 创建前 alias 唯一性预检(同 user 内 alias 名唯一)。
     * 大小写不敏感:用户已有"我的 GPT",新建"我的 gpt" 应被拒。
     */
    boolean existsByUserIdAndAliasIgnoreCase(Long userId, String alias);
}

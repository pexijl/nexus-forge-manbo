package com.nexusforge.repository;

import com.nexusforge.entity.AiConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /**
     * 按用户列出对话,置顶优先,然后按更新时间倒序(全量)
     */
    List<AiConversation> findByUserIdOrderByPinnedDescUpdatedAtDesc(Long userId);

    /**
     * 按用户分页列出对话,置顶优先,然后按更新时间倒序。
     * Spring Data 会自动派生 count 查询,无需额外 @Query(countQuery=...)。
     * 排序由派生方法名固定(Pageable 不再叠加 Sort,避免覆盖置顶规则)。
     */
    Page<AiConversation> findByUserIdOrderByPinnedDescUpdatedAtDesc(Long userId, Pageable pageable);

    /**
     * 查询用户的一个对话(带权限校验)
     */
    Optional<AiConversation> findByIdAndUserId(Long id, Long userId);

    /**
     * 删除用户的某个对话(带权限校验)。
     *
     * <p>此为<b>物理删除</b>(真实 SQL DELETE),仅供合规/GDPR/数据清理等
     * 场景使用。普通业务"删除"应走 service 层的 {@code repo.delete(entity)},
     * 由 {@code @SQLDelete} 拦截转成软删除(只写 {@code deleted_at})。</p>
     */
    @Modifying
    @Query("DELETE FROM AiConversation c WHERE c.id = :id AND c.userId = :userId")
    int hardDeleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 恢复一个已软删的对话(把 deleted_at 置回 NULL)。
     *
     * <p>必须在 service 层用 {@code EntityManager.createNativeQuery}
     * 走,不能在 repo 上用 {@code @Modifying} 实现。原因:Hibernate 6
     * 的 {@code @SQLRestriction} 在生成 SQL 时会给 UPDATE/DELETE 也拼
     * 接 {@code WHERE deleted_at IS NULL},与"恢复已软删记录"的语义
     * 矛盾,导致永远 0 行。{@code @Modifying + nativeQuery=true} 也
     * 受影响;只有 {@code EntityManager} 原生 SQL 能完全绕过。</p>
     *
     * <p>因此本方法标记为不再由 service 调用,保留在 repo 仅供历史参考。
     * service 实现见
     * {@link com.nexusforge.service.ConversationService#restoreConversation}。</p>
     *
     * @deprecated Hibernate 6 行为:@SQLRestriction 对 @Modifying 也会拼接;
     *             改用 service 层的 EntityManager.createNativeQuery
     */
    @Deprecated
    @Modifying
    @Query(value = "UPDATE ai_conversations SET deleted_at = NULL, updated_at = CURRENT_TIMESTAMP " +
                   "WHERE id = :id AND user_id = :userId AND deleted_at IS NOT NULL",
           nativeQuery = true)
    int restoreByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 统计用户对话数(后续可用于配额控制,只统计活的)
     */
    long countByUserId(Long userId);
}
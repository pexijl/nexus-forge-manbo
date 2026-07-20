package com.nexusforge.repository;

import com.nexusforge.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {

    /**
     * 按用户列出对话,置顶优先,然后按更新时间倒序
     */
    List<AiConversation> findByUserIdOrderByPinnedDescUpdatedAtDesc(Long userId);

    /**
     * 查询用户的一个对话(带权限校验)
     */
    Optional<AiConversation> findByIdAndUserId(Long id, Long userId);

    /**
     * 删除用户的某个对话(带权限校验)
     */
    @Modifying
    @Query("DELETE FROM AiConversation c WHERE c.id = :id AND c.userId = :userId")
    int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 统计用户对话数(后续可用于配额控制)
     */
    long countByUserId(Long userId);
}
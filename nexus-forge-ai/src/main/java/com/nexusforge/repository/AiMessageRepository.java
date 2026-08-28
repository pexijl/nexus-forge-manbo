package com.nexusforge.repository;

import com.nexusforge.entity.AiMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    /**
     * 按对话列出消息,按 seq 升序
     */
    List<AiMessage> findByConversationIdOrderBySeqAsc(Long conversationId);

    /**
     * 获取对话的最后 N 条消息(用于构建上下文窗口)
     * 注意:Spring Data 的 Pageable 在自定义查询中需要 countQuery
     */
    @Query("SELECT m FROM AiMessage m WHERE m.conversationId = :convId ORDER BY m.seq DESC LIMIT :limit")
    List<AiMessage> findLastNMessages(@Param("convId") Long convId, @Param("limit") int limit);

    /**
     * 获取对话当前最大 seq 值(用于插入新消息时自增)
     */
    @Query("SELECT COALESCE(MAX(m.seq), -1) FROM AiMessage m WHERE m.conversationId = :convId")
    int findMaxSeq(@Param("convId") Long convId);

    /**
     * 获取对话的消息总数
     */
    long countByConversationId(Long conversationId);
}
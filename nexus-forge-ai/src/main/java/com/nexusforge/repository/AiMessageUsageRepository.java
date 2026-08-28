package com.nexusforge.repository;

import com.nexusforge.entity.AiMessageUsage;
import com.nexusforge.service.UsageAggregateRow;
import com.nexusforge.service.UsageByModelRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AiMessageUsageRepository extends JpaRepository<AiMessageUsage, Long> {

    // P5 Step 1 聚合查询。设计原则:
    //  - 不用 GROUP BY userId,因为每行已经通过 join 限定到单一 user;
    //  - 关联路径:ai_message_usage --(messageId)--> ai_messages --(conversationId)-->
    //    ai_conversations,经 conversation.userId 过滤;
    //  - 窗口过滤走 ai_messages.created_at(BaseEntity 字段),不取 ai_message_usage
    //    的 createdAt(它没有 createdAt,主键就是 messageId,没有时间戳)。
    //  - COALESCE(SUM(x), 0) 防止空窗口下 SUM 返回 null,record 字段是 long primitive,
    //    JPQL new 投影要求非 null 参数。
    //  - 计数走 COUNT(u.messageId) 而非 COUNT(*),后者在 LEFT JOIN 时与 "1 行 1 计数"
    //    不一致;INNER JOIN 等价,但显式更安全。

    /**
     * 按 user 维度 + 时间窗聚合。用于配额检查、用量汇总接口。
     *
     * @param userId  用户 ID
     * @param from    窗口起始(含),通常 {@code now - windowHours}
     * @param to      窗口结束(不含),通常 {@code now}
     */
    @Query("""
            SELECT new com.nexusforge.service.UsageAggregateRow(
                COALESCE(SUM(u.promptTokens), 0),
                COALESCE(SUM(u.completionTokens), 0),
                COALESCE(SUM(u.totalTokens), 0),
                COUNT(u.messageId))
            FROM AiMessageUsage u
                JOIN AiMessage m ON m.id = u.messageId
                JOIN AiConversation c ON c.id = m.conversationId
            WHERE c.userId = :userId
              AND m.createdAt >= :from
              AND m.createdAt <  :to
            """)
    UsageAggregateRow sumByUserAndWindow(@Param("userId") Long userId,
                                         @Param("from") OffsetDateTime from,
                                         @Param("to") OffsetDateTime to);

    /**
     * 按 user + model 维度聚合。用于账单/账单拆分。结果按 {@code totalTokens} 降序。
     */
    @Query("""
            SELECT new com.nexusforge.service.UsageByModelRow(
                u.model,
                COALESCE(SUM(u.promptTokens), 0),
                COALESCE(SUM(u.completionTokens), 0),
                COALESCE(SUM(u.totalTokens), 0),
                COUNT(u.messageId))
            FROM AiMessageUsage u
                JOIN AiMessage m ON m.id = u.messageId
                JOIN AiConversation c ON c.id = m.conversationId
            WHERE c.userId = :userId
              AND m.createdAt >= :from
              AND m.createdAt <  :to
            GROUP BY u.model
            ORDER BY SUM(u.totalTokens) DESC
            """)
    List<UsageByModelRow> sumByUserModelWindow(@Param("userId") Long userId,
                                               @Param("from") OffsetDateTime from,
                                               @Param("to") OffsetDateTime to);

    /**
     * 单会话累计用量。无时间窗,等价于会话全生命周期的 token 总数。
     */
    @Query("""
            SELECT new com.nexusforge.service.UsageAggregateRow(
                COALESCE(SUM(u.promptTokens), 0),
                COALESCE(SUM(u.completionTokens), 0),
                COALESCE(SUM(u.totalTokens), 0),
                COUNT(u.messageId))
            FROM AiMessageUsage u
                JOIN AiMessage m ON m.id = u.messageId
            WHERE m.conversationId = :conversationId
            """)
    UsageAggregateRow sumByConversation(@Param("conversationId") Long conversationId);

    /**
     * 给定消息 ID 列表,按 message_id 倒序返回前 50 条用量。
     * 用于管理员后台"最近 50 次 LLM 调用"面板。
     */
    List<AiMessageUsage> findTop50ByMessageIdInOrderByMessageIdDesc(List<Long> messageIds);
}

package com.nexusforge.event;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 模块用户数据删除监听器 —— 监听 {@link UserDataDeletionEvent},
 * 真删该用户的所有 ai_conversations / ai_messages / ai_message_usage。
 *
 * <p>设计动机:user 模块不能直接 import ai 模块 entity(模块方向保护),
 * 通过事件让 ai 模块自主清理。</p>
 *
 * <p><b>为什么用原生 SQL 而不是 JPA 派生方法</b>:</p>
 * <ul>
 *   <li>{@code @SQLRestriction("deleted_at IS NULL")} 会让 JPA 派生 {@code findByUserId}
 *       过滤掉已软删的 conversation —— 但注销要清空所有数据,包括软删的</li>
 *   <li>用 {@link EntityManager#createNativeQuery} 走纯 JDBC 通道,
 *       不受 Hibernate 的 SQL 改写影响(参考 commit 5f368cf 的 restoreConversation 经验)</li>
 * </ul>
 *
 * <p><b>删除顺序</b>:</p>
 * <ol>
 *   <li>删 {@code ai_message_usage}(依赖 message_id)—— 用 JOIN conversation 限定 user_id</li>
 *   <li>删 {@code ai_messages}(依赖 conversation_id)—— 同上</li>
 *   <li>删 {@code ai_conversations}(user_id 字段直接定位)</li>
 * </ol>
 *
 * <p>顺序很重要:usage 和 messages 的 user_id 是间接的(经 conversation 关联),
 * 必须先于 conversations 删,否则外键约束(若后续加 FK)会失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUserDataDeletionListener {

    @PersistenceContext
    private EntityManager entityManager;

    @EventListener
    @Transactional
    public void onUserDataDeletion(UserDataDeletionEvent event) {
        Long userId = event.userId();
        if (userId == null) {
            log.warn("[ai-data-deletion] event with null userId, ignored");
            return;
        }
        try {
            int usageCount = entityManager.createNativeQuery(
                    "DELETE FROM ai_message_usage WHERE message_id IN (" +
                    "  SELECT m.id FROM ai_messages m" +
                    "    JOIN ai_conversations c ON c.id = m.conversation_id" +
                    "  WHERE c.user_id = :userId" +
                    ")")
                    .setParameter("userId", userId)
                    .executeUpdate();

            int messageCount = entityManager.createNativeQuery(
                    "DELETE FROM ai_messages WHERE conversation_id IN (" +
                    "  SELECT id FROM ai_conversations WHERE user_id = :userId" +
                    ")")
                    .setParameter("userId", userId)
                    .executeUpdate();

            int convCount = entityManager.createNativeQuery(
                    "DELETE FROM ai_conversations WHERE user_id = :userId")
                    .setParameter("userId", userId)
                    .executeUpdate();

            log.info("[ai-data-deletion] purged userId={} conversations={} messages={} usageRows={}",
                    userId, convCount, messageCount, usageCount);
        } catch (Exception e) {
            // 不抛 —— 数据清理失败不影响主业务(账号已注销,数据没清是次要问题,可后续手工清理)
            log.warn("[ai-data-deletion] failed for userId={}: {}", userId, e.getMessage());
        }
    }
}

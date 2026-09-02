package com.nexusforge.entity;

import com.nexusforge.base.BaseEntity;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * AI 对话消息实体。一条消息对应一次 user/assistant/system 交互。
 *
 * <p>软删除注解必须直接放在 {@code @Entity} 上(Hibernate 6 不从
 * {@code @MappedSuperclass} 继承 SQL 改写)。</p>
 */
@Getter
@Setter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "ai_messages")
@SQLDelete(sql = "UPDATE ai_messages SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL")
@SQLRestriction("deleted_at IS NULL")
public class AiMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /**
     * 角色:SYSTEM / USER / ASSISTANT / TOOL
     */
    @Column(nullable = false, length = 16)
    private String role;

    /**
     * 消息文本内容
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 工具调用 JSON(P4 预留,当前始终 null)
     */
    @Column(name = "tool_calls")
    private String toolCalls;

    /**
     * 对话内消息序号,从 0 开始递增
     */
    @Column(nullable = false)
    private Integer seq;
}
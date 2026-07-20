package com.nexusforge.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 消息 token 用量。独立表,与 ai_messages 1:1 关联。
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "ai_message_usage")
public class AiMessageUsage {

    @Id
    @Column(name = "message_id")
    @EqualsAndHashCode.Include
    private Long messageId;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens;

    @Column(nullable = false, length = 64)
    private String model;
}
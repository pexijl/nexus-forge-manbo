package com.nexusforge.service;

import com.nexusforge.config.AiProperties;
import com.nexusforge.entity.AiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文窗口构建器单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>空历史返回空列表</li>
 *   <li>system 消息始终保留</li>
 *   <li>超出预算时从最旧消息开始截断</li>
 *   <li>system 消息自身也占用预算</li>
 *   <li>token 估算基础行为</li>
 * </ul>
 *
 * <p>spring-ai-full-migration Phase 6 重写:返回值类型从 List&lt;com.nexusforge.ai.ChatMessage&gt;
 * 改为 Spring AI 的 {@code List<Message>},断言用 {@code instanceof} 检测具体子类
 * (SystemMessage / UserMessage / AssistantMessage)。
 */
class ContextWindowBuilderTest {

    private AiProperties props;
    private ContextWindowBuilder builder;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        // 小窗口便于测试:100 token,默认 Context 已通过 AiProperties 构造时初始化为 8000,
        // 此处显式覆盖为 100,触发截断逻辑。
        props.getContext().setMaxTokens(100);
        builder = new ContextWindowBuilder(props);
    }

    @Test
    @DisplayName("空历史返回空列表")
    void empty_history_returns_empty_list() {
        List<Message> result = builder.build(List.of(), "test-model");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("system 消息始终保留在头部")
    void system_message_always_preserved() {
        List<AiMessage> history = List.of(
                makeMsg(0, "SYSTEM", "You are a helpful assistant."),
                makeMsg(1, "USER", "Hi")
        );
        List<Message> result = builder.build(history, "test-model");
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("You are a helpful assistant.");
        assertThat(result.get(1)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1).getText()).isEqualTo("Hi");
    }

    @Test
    @DisplayName("超出预算时截断最早的非 system 消息")
    void truncates_old_messages_when_exceeding_budget() {
        // maxTokens=100,每条 "Message N with some padding text" = 32 chars ≈ 16 tokens
        // 注入 20 条 USER/ASSISTANT,按 seq 升序;最新消息应该保留,最早消息应被丢弃
        List<AiMessage> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(makeMsg(i,
                    i % 2 == 0 ? "USER" : "ASSISTANT",
                    "Message " + i + " with some padding text"));
        }

        List<Message> result = builder.build(history, "test-model");

        // 截断后数量应小于 20
        assertThat(result.size()).isLessThan(20);
        assertThat(result.size()).isPositive();
        // 最后一条消息应保留
        assertThat(result.get(result.size() - 1).getText()).contains("Message 19");
        // 第一条应该是 Message 19 减去预算外的旧消息,不应该是 Message 0
        assertThat(result.get(0).getText()).doesNotContain("Message 0 ");
    }

    @Test
    @DisplayName("system 消息自身也占用预算,过长时挤掉后续 user 消息")
    void system_message_counts_against_budget() {
        // 200 chars ≈ 100 tokens = 整个预算;后续 user 消息放不下
        String longSystem = "A".repeat(200);
        List<AiMessage> history = List.of(
                makeMsg(0, "SYSTEM", longSystem),
                makeMsg(1, "USER", "Hi")
        );

        List<Message> result = builder.build(history, "test-model");

        // 只有 system 消息被保留,user 消息被截断
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
    }

    @Test
    @DisplayName("token 估算基础行为")
    void estimate_tokens_basic() {
        assertThat(ContextWindowBuilder.estimateTokens(null)).isZero();
        assertThat(ContextWindowBuilder.estimateTokens("")).isZero();
        assertThat(ContextWindowBuilder.estimateTokens("hello")).isEqualTo(2);   // 5 / 2
        assertThat(ContextWindowBuilder.estimateTokens("你好世界测试")).isEqualTo(3); // 6 / 2
    }

    @Test
    @DisplayName("单 token 消息不会因为最小值被排除")
    void single_token_message_kept() {
        // maxTokens=100,只有 3 条极短消息
        List<AiMessage> history = List.of(
                makeMsg(0, "USER", "A"),
                makeMsg(1, "ASSISTANT", "B"),
                makeMsg(2, "USER", "C")
        );
        List<Message> result = builder.build(history, "test-model");
        assertThat(result).hasSize(3);
    }

    private AiMessage makeMsg(int seq, String role, String content) {
        AiMessage msg = new AiMessage();
        msg.setId((long) seq);
        msg.setConversationId(1L);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSeq(seq);
        return msg;
    }
}

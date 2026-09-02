package com.nexusforge.service;

import com.nexusforge.config.AiProperties;
import com.nexusforge.entity.AiMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * spring-ai-full-migration Phase 2b — 上下文窗口构建器,产出 Spring AI 的 {@link Message} 列表。
 *
 * <p>策略:
 * <ol>
 *   <li>保留所有 SYSTEM 消息(通常只有 1 条,放在最前面)</li>
 *   <li>从最新的消息开始向前取,直到 token 预算用完</li>
 *   <li>token 估算:中文约 1.5 token/字,英文约 0.75 token/word,统一用 chars/2 粗估</li>
 * </ol>
 *
 * <p>DB 的 {@link AiMessage} 仍是 source of truth(暂不迁移到 Spring AI 的
 * {@code Message} 持久化),这里只把 DB 记录转换成 Spring AI 的 {@link Message}。
 *
 * <p>TOOL 角色消息(assistant 的工具回复):我们的 {@code AiMessage.name} 字段保存
 * 工具名但缺 {@code toolCallId}。Phase 2b 暂把它当 {@link UserMessage} 发出
 * (退化,信息保留),Phase 3 重做 tool loop 时再扩列 {@code toolCallId}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextWindowBuilder {

    private final AiProperties props;

    /**
     * 从历史消息构建上下文窗口。
     *
     * @param history DB 中的全部消息(按 seq 升序)
     * @param model   模型标识(日志用)
     * @return 不超过 maxTokens 的 Spring AI Message 列表
     */
    public List<Message> build(List<AiMessage> history, String model) {
        int maxTokens = props.getContext().getMaxTokens();

        // 分离 system 消息和非 system 消息
        List<AiMessage> systemMsgs = new ArrayList<>();
        List<AiMessage> nonSystemMsgs = new ArrayList<>();
        for (AiMessage msg : history) {
            if ("SYSTEM".equals(msg.getRole())) {
                systemMsgs.add(msg);
            } else {
                nonSystemMsgs.add(msg);
            }
        }

        // 预算:先扣除 system 消息的 token
        int usedTokens = 0;
        List<Message> result = new ArrayList<>();
        for (AiMessage sys : systemMsgs) {
            int tokens = estimateTokens(sys.getContent());
            if (usedTokens + tokens > maxTokens) {
                log.warn("[AI] system 消息超出上下文窗口: model={}, tokens={}/{}", model, tokens, maxTokens);
                break;
            }
            result.add(toSpringMessage(sys));
            usedTokens += tokens;
        }

        // 从最新消息向前取,直到预算用完
        List<Message> tail = new ArrayList<>();
        for (int i = nonSystemMsgs.size() - 1; i >= 0; i--) {
            AiMessage msg = nonSystemMsgs.get(i);
            int tokens = estimateTokens(msg.getContent());
            if (usedTokens + tokens > maxTokens) {
                log.info("[AI] 上下文截断: model={}, 已取 {}/{} 条消息, 估算 {}/{} tokens",
                        model, tail.size(), nonSystemMsgs.size(), usedTokens, maxTokens);
                break;
            }
            tail.addFirst(toSpringMessage(msg));
            usedTokens += tokens;
        }

        result.addAll(tail);

        if (result.isEmpty()) {
            log.warn("[AI] 上下文窗口为空: model={}, historySize={}", model, history.size());
        }

        return result;
    }

    /**
     * 粗估 token 数。中文约 1.5 token/字,英文约 0.75 token/word。
     * 统一用字符数 / 2 作为粗估值。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 2);
    }

    /**
     * DB 的 AiMessage 转 Spring AI 的 Message。
     * role 字符串直接映射,TOOL 角色暂降级为 UserMessage(Phase 3 会用 toolCallId 重做)。
     */
    private static Message toSpringMessage(AiMessage msg) {
        String content = msg.getContent() == null ? "" : msg.getContent();
        return switch (msg.getRole()) {
            case "SYSTEM" -> new SystemMessage(content);
            case "USER" -> new UserMessage(content);
            case "ASSISTANT" -> AssistantMessage.builder().content(content).build();
            case "TOOL" -> {
                // Phase 2b 退化:TOOL 结果以 UserMessage 形式发出(保留文本信息,缺 tool_call_id)
                // Phase 3 重做 tool loop 时会改用 ToolResponseMessage 配 toolCallId
                log.debug("[AI] TOOL role 消息暂以 UserMessage 形式发出(Phase 3 改 ToolResponseMessage): id={}",
                        msg.getId());
                yield new UserMessage(content);
            }
            default -> new UserMessage(content);
        };
    }
}

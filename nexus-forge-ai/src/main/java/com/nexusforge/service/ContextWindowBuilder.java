package com.nexusforge.service;

import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.Role;
import com.nexusforge.config.AiProperties;
import com.nexusforge.entity.AiMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口构建器。从历史消息中截取不超过 maxTokens 的窗口。
 *
 * <p>策略:
 * <ol>
 *   <li>保留所有 SYSTEM 消息(通常只有 1 条,放在最前面)</li>
 *   <li>从最新的消息开始向前取,直到 token 预算用完</li>
 *   <li>token 估算:中文约 1.5 token/字,英文约 0.75 token/word,统一用 chars/2 粗估</li>
 * </ol>
 *
 * <p>P5 后可换成 tiktoken 精确计算;P3 先用粗估保证逻辑正确。
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
     * @return 不超过 maxTokens 的 ChatMessage 列表
     */
    public List<ChatMessage> build(List<AiMessage> history, String model) {
        int maxTokens = props.getContext().getMaxTokens();

        // 分离 system 消息和非 system 消息
        List<AiMessage> systemMsgs = new ArrayList<>();
        List<AiMessage> nonSystemMsgs = new ArrayList<>();
        for (AiMessage msg : history) {
            if (Role.SYSTEM.name().equals(msg.getRole())) {
                systemMsgs.add(msg);
            } else {
                nonSystemMsgs.add(msg);
            }
        }

        // 预算:先扣除 system 消息的 token
        int usedTokens = 0;
        List<ChatMessage> result = new ArrayList<>();
        for (AiMessage sys : systemMsgs) {
            int tokens = estimateTokens(sys.getContent());
            if (usedTokens + tokens > maxTokens) {
                log.warn("[AI] system 消息超出上下文窗口: model={}, tokens={}/{}", model, tokens, maxTokens);
                break;
            }
            result.add(toChatMessage(sys));
            usedTokens += tokens;
        }

        // 从最新消息向前取,直到预算用完
        List<ChatMessage> tail = new ArrayList<>();
        for (int i = nonSystemMsgs.size() - 1; i >= 0; i--) {
            AiMessage msg = nonSystemMsgs.get(i);
            int tokens = estimateTokens(msg.getContent());
            if (usedTokens + tokens > maxTokens) {
                log.info("[AI] 上下文截断: model={}, 已取 {}/{} 条消息, 估算 {}/{} tokens",
                        model, tail.size(), nonSystemMsgs.size(), usedTokens, maxTokens);
                break;
            }
            tail.addFirst(toChatMessage(msg));
            usedTokens += tokens;
        }

        // 合并:system 在前,tail 在后
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

    private static ChatMessage toChatMessage(AiMessage msg) {
        return ChatMessage.builder()
                .role(Role.valueOf(msg.getRole()))
                .content(msg.getContent())
                .build();
    }
}
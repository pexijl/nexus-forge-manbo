package com.nexusforge.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * /api/ai/chat 请求体。
 *
 * <p>spring-ai-full-migration Phase 2c — {@code messages} 字段直接用
 * Spring AI 的 {@link Message} 多态类型。Spring Boot 4.x 默认 JSON 栈
 * 是 Jackson 3({@code tools.jackson}),Spring AI 2.0 实际并未自带
 * {@code MessageTypeDeserializer}(已通过反编译
 * {@code spring-ai-model-2.0.0.jar} 确认)。多态路由由项目内
 * {@link com.nexusforge.ai.jackson.MessageJacksonDeserializer} 承担:
 * 按 JSON 的 {@code role} 字段反序列化为
 * {@link org.springframework.ai.chat.messages.UserMessage} /
 * {@link org.springframework.ai.chat.messages.SystemMessage} /
 * {@link org.springframework.ai.chat.messages.AssistantMessage} /
 * {@link org.springframework.ai.chat.messages.ToolResponseMessage}。
 *
 * <p>客户端 JSON wire 格式不变:{@code [{role, content}, ...]} —
 * 与 OpenAI Chat Completions 完全一致,迁移成本为 0。
 *
 * <p>历史字段(温度 / maxTokens / stream / options / tools)被一并
 * 移除 — 它们在前几版 DTO 里只到 {@code toDomain()} 转换层就被丢
 * 弃,LLM 调用链根本没消费。后续真要用再加回(用 Spring AI 强类型
 * {@code ChatOptions} 子类,而不是 Map)。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequestDto {

    @Schema(description = "模型标识;不指定时由网关按 defaultVendor + defaultModel 兜底")
    private String model;

    /**
     * Phase 3 — 可选:用户显式选定的代理 ID(必须属于当前 user)。
     * 优先级最高;比 {@link #model} 字段先解析。
     * 走 {@code user_ai_proxy} 多代理机制,USER_PRIVATE_KEY 模式。
     */
    @Schema(description = "代理 ID(Phase 3 BYOK 多代理);指定时强制走该代理,USER_PRIVATE_KEY 模式",
            example = "5")
    private Long proxyId;

    @NotEmpty
    private List<Message> messages;
}

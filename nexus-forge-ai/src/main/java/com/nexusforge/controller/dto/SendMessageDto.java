package com.nexusforge.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户在已有对话中发送消息。role 固定为 USER,不需要前端传。
 */
@Data
public class SendMessageDto {

    @NotBlank(message = "消息内容不能为空")
    private String content;

    /**
     * 可选:覆盖对话的模型(切换模型)。为空则沿用对话创建时的模型。
     */
    private String model;

    /**
     * Phase 3 — 可选:用户显式选定的代理 ID(必须属于当前 user)。
     * 优先级最高,比 {@link #model} 字段先解析。
     * 走 {@code user_ai_proxy} 多代理机制,USER_PRIVATE_KEY 模式。
     */
    private Long proxyId;
}
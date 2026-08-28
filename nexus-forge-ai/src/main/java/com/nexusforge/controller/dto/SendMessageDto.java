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
}
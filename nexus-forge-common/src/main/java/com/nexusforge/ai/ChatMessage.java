package com.nexusforge.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI对话单条消息实体
 * 封装单次交互中的角色、文本内容、工具调用等信息，用于大模型对话请求与响应传输
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {
    /**
     * 消息角色枚举
     * system：系统提示词；user：用户提问；assistant：AI回答；tool：工具返回结果
     */
    private Role role;

    /**
     * 消息文本内容
     */
    private String content;

    /**
     * 工具调用集合，AI主动调用函数时返回该字段
     */
    private List<ToolCall> toolCalls;

    /**
     * 工具名称，tool角色消息时使用，标识调用的工具函数名
     */
    private String name;
}
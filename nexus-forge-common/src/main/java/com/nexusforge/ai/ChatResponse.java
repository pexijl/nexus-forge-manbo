package com.nexusforge.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 大模型对话响应返回实体
 * 封装AI对话接口返回的全部结果信息，包含生成内容、消耗Token、结束原因、耗时等数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {
    /**
     * 本次对话请求唯一标识ID
     */
    private String id;

    /**
     * 当前调用的大模型名称
     */
    private String model;

    /**
     * 模型生成的回复文本内容
     */
    private String content;

    /**
     * 本次对话Token消耗统计信息
     */
    private ChatUsage usage;

    /**
     * 接口请求整体耗时，单位：毫秒
     */
    private Long latencyMillis;

    /**
     * 对话结束原因
     * stop：正常结束；length：达到最大token限制；tool_calls：触发工具调用；
     * content_filter：内容安全拦截；error：服务异常失败
     */
    private String finishReason;

    /**
     * 工具调用列表，仅当 finishReason=tool_calls 时携带。
     * 流式路径下由 FunctionCallAggregator 在终止帧聚合输出；
     * 同步路径由 OpenAiJsonMapper.fromOpenAi 直接从 message.tool_calls 提取。
     */
    private List<ToolCall> toolCalls;
}

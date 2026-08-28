package com.nexusforge.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 大模型流式对话分片实体
 * 流式输出时分段返回的数据块，增量携带生成文本，结束分片附带用量与终止原因
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatChunk {
    /**
     * 本次对话请求唯一标识ID，同一次流式请求所有分片id一致
     */
    private String id;

    /**
     * 当前调用的大模型标识名称
     */
    private String model;

    /**
     * 增量输出文本片段，流式每一段新增的回复内容
     */
    private String deltaContent;

    /**
     * Token消耗统计用量，仅流式最后一条分片会携带该数据
     */
    private ChatUsage usage;

    /**
     * 对话终止原因，仅流式最后一条分片携带，标识生成结束的类型
     */
    private String finishReason;
    /**
     * 流式增量中的工具调用片段(仅在 tool_calls 流期间出现)。
     * 多帧之间按 index 字段聚合为完整 {@link com.nexusforge.ai.ToolCall},
     * 终止帧(finishReason=tool_calls)由 FunctionCallAggregator 输出完整列表。
     */
    private java.util.List<DeltaToolCall> deltaToolCalls;
    /**
     * 终止帧聚合后的完整 tool call 列表,仅在 {@code finishReason="tool_calls"} 那一帧
     * 由 {@code FunctionCallAggregator} 填入。流中间帧与纯文本流的终止帧都为 {@code null}。
     *
     * <p>与同步响应 {@link ChatResponse#getToolCalls()} 字段语义对齐,流消费方读到该字段
     * 非空即可判定:"模型选择工具调用,等待执行器回填后再次发起对话"。
     */
    private List<ToolCall> toolCalls;
}
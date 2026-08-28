package com.nexusforge.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式 tool_calls 增量片段。
 *
 * <p>OpenAI 风格:
 * <pre>
 *   choices[0].delta.tool_calls[0] = { "index": 0, "id": "call_abc", "function": {"name": "get_weather", "arguments": "{\"city\":"} }
 *   choices[0].delta.tool_calls[1] = { "index": 0,                 "function": {"name": null,         "arguments": "\"Beijing\"}" } }
 * </pre>
 *
 * <p>用 index 字段保证顺序,id 在首帧固定,后续 id/name 字段为 null,
 * arguments 是 JSON 字符串分片,逐帧拼接。
 *
 * <p>聚合由 {@code FunctionCallAggregator} 完成,按 index 分桶,终止帧(finishReason=tool_calls)
 * 一次性输出完整 {@link ToolCall} 列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeltaToolCall {

    /**
     * OpenAI 用 index 标记同一帧中的多个并行 tool call 顺序;
     * 也用于流式增量帧间对齐(同一 index 聚合)。
     */
    private Integer index;

    /**
     * 完整 tool call 的唯一 ID,仅首帧携带,后续为 null。
     */
    private String id;

    /**
     * tool 函数名;name 在 OpenAI 流式里通常仅首帧非空,
     * 后续 delta.name 为 null 表示"沿用上一个"。
     */
    private String name;

    /**
     * 函数入参的 JSON 字符串分片;逐帧拼接后由 FunctionCallAggregator
     * 整体 parse 成 JsonNode 写入 ToolCall.arguments。
     */
    private String argumentsChunk;
}

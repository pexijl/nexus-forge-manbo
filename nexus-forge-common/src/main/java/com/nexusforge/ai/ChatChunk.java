package com.nexusforge.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
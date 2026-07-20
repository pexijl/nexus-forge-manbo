package com.nexusforge.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 大模型工具调用信息实体
 * 用于存储AI触发函数调用时的调用ID、工具函数名、入参JSON数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {
    /**
     * 工具调用唯一标识ID，用于关联工具返回结果
     */
    private String id;

    /**
     * 调用的工具函数名称
     */
    private String name;

    /**
     * 工具函数入参，JSON格式参数对象
     */
    private JsonNode arguments;
}
package com.nexusforge.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 工具函数定义实体
 * 用于向大模型注册可用工具，描述工具名称、功能说明与入参规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {
    /**
     * 工具函数唯一名称，模型通过该名称识别并调用对应工具
     */
    private String name;

    /**
     * 工具功能描述，告知模型该工具的作用、适用场景
     */
    private String description;

    /**
     * 工具入参规范，遵循JSON Schema格式，定义参数名、类型、是否必填等约束
     */
    private JsonNode parameters;
}
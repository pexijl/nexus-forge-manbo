package com.nexusforge.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 大模型对话请求入参实体
 * 封装调用AI对话接口所需全部请求参数，支持流式输出、工具调用、温度采样等配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {
    /**
     * 模型标识，指定要调用的大模型名称，不可为空
     */
    @NotNull
    private String model;

    /**
     * 对话历史消息列表，包含系统提示、用户提问、AI回复等完整上下文，不可为空
     */
    @NotEmpty
    private List<ChatMessage> messages;

    /**
     * 温度系数，控制生成内容随机性；取值0~1，数值越高输出越有创意，越低回答越确定
     */
    private Double temperature;

    /**
     * 单次生成最大token上限，限制AI回复内容长度
     */
    private Integer maxTokens;

    /**
     * 是否开启流式输出，true为分段返回数据，false一次性返回完整结果
     */
    private Boolean stream;

    /**
     * 模型扩展自定义参数，用于承载各厂商特有配置项
     */
    private Map<String, Object> options;

    /**
     * 可用工具函数定义列表，传入后模型可自主判断并调用对应工具
     */
    private List<ToolDefinition> tools;
}
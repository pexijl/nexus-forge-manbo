package com.nexusforge.model;

import lombok.Builder;
import lombok.Data;

/**
 * 对话模型能力标识实体
 * 用于标记当前大模型支持的各类对话功能特性
 */
@Data
@Builder
public class ChatCapabilities {
    /**
     * 是否支持流式输出
     * true：接口可分段返回增量文本（打字机效果）
     * false：一次性返回完整应答结果
     */
    private boolean stream;

    /**
     * 是否支持工具调用 / Function Calling
     * true：模型可识别工具描述、生成工具调用参数
     * false：仅纯文本对话，无法调用外部工具
     */
    private boolean tools;

    /**
     * 是否支持视觉多模态输入
     * true：可传入图片链接/图片内容，图文理解问答
     * false：仅纯文本输入，不识别图片
     */
    private boolean vision;

    /**
     * 是否支持固定JSON结构化输出模式
     * true：可强制模型返回标准JSON字符串，无需额外文本包裹
     * false：模型返回自由文本，需自行解析提取JSON
     * 备注：部分厂商API原生提供该专属能力
     */
    private boolean jsonMode;
}
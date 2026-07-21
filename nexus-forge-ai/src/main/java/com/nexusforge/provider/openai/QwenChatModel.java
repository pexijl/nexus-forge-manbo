package com.nexusforge.provider.openai;

import com.nexusforge.config.AiProperties;
import com.nexusforge.stream.OpenAiStreamParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 阿里通义千问(Qwen) ChatModel 实现。
 *
 * <p>Qwen 通过 DashScope 的 OpenAI-compatible 模式接入,
 * 端点 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}。
 * DashScope 在 OpenAI-compatible 模式下支持 stream / tool_calls(P4 起支持),
 * capabilities 与基类默认一致。
 *
 * <p>默认 base-url: {@code https://dashscope.aliyuncs.com/compatible-mode/v1}<br>
 * 默认模型: {@code qwen-turbo}(国内延迟低;追求质量改 {@code qwen-plus} 或 {@code qwen-max})
 *
 * <p>启用方式:application.yaml 写
 * <pre>
 * spring:
 *   ai:
 *     providers:
 *       qwen:
 *         enabled: true
 *         api-key: ${DASHSCOPE_API_KEY}
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "spring.ai.providers.qwen.enabled", havingValue = "true")
public class QwenChatModel extends OpenAiCompatibleChatModel {

    public QwenChatModel(AiProperties props, ObjectMapper json, OpenAiJsonMapper mapper,
                         OpenAiStreamParser streamParser) {
        // 不传 defaultModel:管理员必须在启动后通过 PUT /api/admin/ai/global-default
        // 显式设置 ai_global_default.vendor/model,否则任何 system 模式调用都会因
        // model 为 null 在 mapper toOpenAi() 时被 PG 上游拒绝(400)。
        // yaml / 应用启动校验(下一步)会让 vendor 注册阶段就 fail-fast。
        super("qwen",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                /* defaultModel */ null,
                props, json, mapper, streamParser);
    }
}

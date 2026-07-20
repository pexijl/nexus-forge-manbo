package com.nexusforge.router;

import com.nexusforge.ai.ChatRequest;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大模型路由处理器
 * 根据 ChatRequest 中传入的 model 字段匹配、选择对应的 ChatModel 实现类
 * 模型名称解析命名约定：
 * <pre>
 *   "openai:gpt-4o-mini"        -> openai 服务商，模型 gpt-4o-mini
 *   "gpt-4o-mini"               -> 使用全局默认服务商(默认 openai)，模型 gpt-4o-mini
 *   "anthropic:claude-3-5-haiku" -> anthropic 服务商，模型 claude-3-5-haiku
 * </pre>
 * 解析完成后封装路由上下文 Resolved，交由外层 LlmClient 调用对应 ChatModel 执行对话请求
 */
@Component
@RequiredArgsConstructor
public class ChatModelRouter {

    /**
     * 所有已加载的大模型SPI实现映射
     * key：服务商名称（ChatModel#name() 返回值），value：对应服务商ChatModel实现实例
     */
    private final Map<String, ChatModel> models;

    /**
     * AI全局配置属性，读取默认厂商、默认模型、各服务商配置开关
     */
    private final AiProperties props;

    /**
     * 对外入口方法：解析请求中的模型标识，路由匹配对应服务商与有效模型名
     * @param request 对话请求对象，携带原始model标识
     * @return 路由解析结果，包含模型实现、服务商、最终生效模型名
     */
    public Resolved resolve(ChatRequest request) {
        // 请求为空 / model字段为空空白，使用全局默认厂商+全局默认模型
        if (request == null || request.getModel() == null || request.getModel().isBlank()) {
            return resolveInternal(props.getDefaultVendor(), props.getDefaultModel());
        }
        String m = request.getModel().trim();
        // 匹配 "vendor:model" 格式，拆分服务商与模型名称
        int idx = m.indexOf(':');
        if (idx > 0) {
            return resolveInternal(m.substring(0, idx), m.substring(idx + 1));
        }
        // 无冒号分隔，使用全局默认服务商，传入值作为模型名
        return resolveInternal(props.getDefaultVendor(), m);
    }

    /**
     * 内部路由解析逻辑：根据指定服务商、原始模型名校验可用性并生成最终路由结果
     * @param vendor 服务商标识
     * @param model 请求传入的原始模型名称
     * @return 封装后的路由解析实体
     */
    private Resolved resolveInternal(String vendor, String model) {
        // 根据服务商名称获取对应的ChatModel实现，统一转小写匹配
        ChatModel impl = models.get(vendor.toLowerCase());
        if (impl == null) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND, "未找到 vendor=" + vendor);
        }
        // 获取配置文件中该服务商的独立配置
        AiProperties.Provider p = props.getProviders().get(vendor);
        // 配置不存在 或 配置显式关闭该服务商，抛出异常禁止路由
        if (p == null || !p.isEnabled()) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND, "vendor=" + vendor + " 已禁用");
        }
        // 模型名为空时，使用该服务商自身配置的默认模型
        String effectiveModel = (model == null || model.isBlank())
                ? p.getDefaultModel()
                : model;
        return new Resolved(impl, vendor, effectiveModel);
    }

    /**
     * 路由解析结果记录类
     * @param model 匹配到的大模型接口实现
     * @param vendor 解析出的服务商标识
     * @param modelName 最终生效使用的模型名称
     */
    public record Resolved(ChatModel model, String vendor, String modelName) {}
}
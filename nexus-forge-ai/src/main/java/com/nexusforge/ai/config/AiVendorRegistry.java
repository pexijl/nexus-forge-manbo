package com.nexusforge.ai.config;

import com.nexusforge.provider.openai.OpenAiJsonMapper;
import com.nexusforge.stream.OpenAiStreamParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 仓库支持的 vendor 名 → 默认 baseUrl / 默认模型 映射。
 *
 * <p>与各 vendor ChatModel 实现类的 {@code defaultBaseUrl / defaultModel} 字段对齐,
 * 这里集中提供一份"配置缺失时的兜底",给私 Key 模式用(系统 yaml 没配该 vendor
 * 时,私 Key 也需要一个能跑的 baseUrl 才能拼 URL)。
 *
 * <p>支持的 vendor:
 * <ul>
 *   <li>openai      → https://api.openai.com/v1,    gpt-4o-mini</li>
 *   <li>deepseek    → https://api.deepseek.com/v1,  deepseek-chat</li>
 *   <li>qwen        → https://dashscope.aliyuncs.com/compatible-mode/v1, qwen-turbo</li>
 *   <li>ollama      → http://localhost:11434/v1,    llama3.1</li>
 *   <li>anthropic   → 不支持私 Key 模式(走独立 Anthropic Messages 协议,非 OpenAI 兼容)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AiVendorRegistry {

    private final OpenAiJsonMapper openAiJsonMapper;
    private final OpenAiStreamParser openAiStreamParser;

    private static final Map<String, VendorSpec> OPENAI_COMPATIBLE = Map.of(
            "openai",   new VendorSpec("https://api.openai.com/v1",                         "gpt-4o-mini"),
            "deepseek", new VendorSpec("https://api.deepseek.com/v1",                       "deepseek-chat"),
            "qwen",     new VendorSpec("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo"),
            "ollama",   new VendorSpec("http://localhost:11434/v1",                         "llama3.1")
    );

    public String defaultBaseUrl(String vendor) {
        VendorSpec spec = OPENAI_COMPATIBLE.get(vendor.toLowerCase(Locale.ROOT));
        return spec == null ? null : spec.baseUrl();
    }

    public String defaultModel(String vendor) {
        VendorSpec spec = OPENAI_COMPATIBLE.get(vendor.toLowerCase(Locale.ROOT));
        return spec == null ? null : spec.model();
    }

    /** vendor 是否支持私 Key 模式(仅 OpenAI-compatible 系列) */
    public boolean supportsPrivateKey(String vendor) {
        return OPENAI_COMPATIBLE.containsKey(vendor.toLowerCase(Locale.ROOT));
    }

    /** 给 VendorChatModelFactory 构造时用的 OpenAiJsonMapper bean */
    public OpenAiJsonMapper openAiJsonMapper() {
        return openAiJsonMapper;
    }

    /** 给 Anthropic 等非 OpenAI 协议的 ChatModel 后续扩展使用 */
    public OpenAiStreamParser openAiStreamParser() {
        return openAiStreamParser;
    }

    private record VendorSpec(String baseUrl, String model) {}
}
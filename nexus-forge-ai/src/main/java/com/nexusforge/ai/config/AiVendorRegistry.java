package com.nexusforge.ai.config;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 仓库支持的 vendor 协议层知识 — 决定哪些 vendor 走 OpenAI 兼容协议
 * (私 Key 模式可走),哪些走专属协议(如 Anthropic 走 Messages API,
 * 不走 OpenAI 兼容)。
 *
 * <p><b>配置层职责不在本类</b>(baseUrl / defaultModel / enabled 等会随时间漂移
 * 的字段,统一在 {@code application.yaml} 的 {@code spring.ai.providers.<vendor>.}
 * 段维护)。本类只承载"协议家族成员关系"这一类纯代码层判断。
 *
 * <p>支持的 OpenAI 兼容 vendor(私 Key 模式可走 {@code VendorChatModelFactory}
 * 用 {@code OpenAiChatModel} 动态构造;系统 Key 模式由
 * {@code ProviderPropertiesBridge} 把 {@code providers.<v>.api-key} 桥接到
 * {@code spring.ai.openai.*} 让 starter 装配对应 ChatModel bean):
 * <ul>
 *   <li><b>国外官方</b>:openai / deepseek / ollama
 *       <i>(deepseek 之前有独立 spring-ai-starter-model-deepseek,Phase X 移除;
 *       DeepSeek API 走 OpenAI Chat Completions 协议家族,系统 Key 模式复用
 *       openai starter,ChatModelRouter 通过 aliasing 把 "deepseek" vendor
 *       路由到 openAiChatModel bean;私 Key 模式仍由 VendorChatModelFactory
 *       用 OpenAiChatModel 动态构造走 DeepSeek base-url)</i></li>
 *   <li><b>国内 OpenAI 兼容</b>:dashscope(阿里通义 qwen)/ glm(智谱)/
 *       kimi(月之暗面 moonshot)/ doubao(字节豆包)/ hunyuan(腾讯混元)</li>
 *   <li><b>通用 OpenAI 兼容中转</b>:siliconflow(硅基流动)/ oneapi /
 *       openrouter / minimax(M2/M3)</li>
 * </ul>
 * 不支持私 Key 的 vendor: anthropic(走独立 Anthropic Messages 协议,非 OpenAI 兼容)。
 *
 * <p><b>新增 OpenAI 兼容 vendor</b>:往 {@link #OPENAI_COMPATIBLE_VENDORS}
 * 加名字(小写)+ 在 yaml 的 {@code spring.ai.providers.<vendor>.*} 段配
 * {@code base-url / default-model / enabled / api-key}。系统 Key 路径
 * 由 {@code ProviderPropertiesBridge} 自动桥接,业务代码零改动。
 *
 * <p>注意:本集合只决定"协议家族"(私 Key 走 OpenAI 协议家族),不决定
 * "系统 Key 走哪个 starter namespace"(由 {@code AiProperties.resolveProtocol}
 * + bridge 推断,见 commit 1 + commit 2)。两者大部分情况重叠,只有
 * {@code anthropic} 例外(本类不支持私 Key,但系统 Key 走 Anthropic starter
 * 完全 OK)。
 */
@Component
public class AiVendorRegistry {

    /**
     * 私 Key 模式可走的 OpenAI 兼容 vendor 集合。
     *
     * <p>命名约定:小写英文 vendor 名(用户 UI 填的 vendor 字符串就是这里
     * 的成员)。同 vendor 的不同别名(例如 {@code glm} 和 {@code zhipu})
     * 全部列出,允许用户 UI 写任何常见叫法。
     */
    private static final Set<String> OPENAI_COMPATIBLE_VENDORS = Set.of(
            // 国外官方
            "openai", "deepseek", "ollama",
            // 国内 OpenAI 兼容 — bridge 全部路由到 spring.ai.openai.*
            "dashscope", "qwen",                  // 阿里通义(OpenAI 兼容 endpoint)
            "glm", "zhipu", "chatglm",            // 智谱 GLM
            "kimi", "moonshot",                   // 月之暗面
            "doubao", "volcengine",               // 字节豆包
            "hunyuan",                            // 腾讯混元
            "minimax",                            // 稀宇科技
            // 通用 OpenAI 兼容中转 / 多模型平台
            "siliconflow", "oneapi", "openrouter"
    );

    /**
     * vendor 是否支持私 Key 模式(仅 OpenAI-compatible 系列)
     */
    public boolean supportsPrivateKey(String vendor) {
        return vendor != null && OPENAI_COMPATIBLE_VENDORS.contains(vendor.toLowerCase(Locale.ROOT));
    }
}

package com.nexusforge.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring AI 大模型全局配置属性类
 * 配置前缀：spring.ai，统一管理AI服务全局开关、默认厂商、各服务商独立配置
 */
@Data
@ConfigurationProperties(prefix = "spring.ai")
public class AiProperties {

    /**
     * AI功能总开关
     * true：启用AI相关能力；false：全局禁用所有大模型调用逻辑
     */
    private boolean enabled = true;

    /**
     * 默认大模型服务商标识
     * 取值示例：openai / anthropic / ollama，代表厂商名称，非具体模型名称
     */
    private String defaultVendor = "openai";

    /**
     * 全局兜底默认模型名称
     * 示例：gpt-4o-mini；路由解析模型名称失败时，自动降级使用该模型
     */
    private String defaultModel = "gpt-4o-mini";

    /**
     * 上下文窗口配置
     * 客户端、服务端统一使用该配置执行超长上下文截断逻辑
     */
    private Context context = new Context();

    /**
     * 同步接口请求超时时间
     * 底层基于JDK HttpClient实现，单位默认60秒
     */
    private Duration requestTimeout = Duration.ofSeconds(60);

    /**
     * 多服务商配置集合
     * key：服务商标识（对应defaultVendor），value：对应服务商独立配置
     */
    @NestedConfigurationProperty
    private Map<String, Provider> providers = new HashMap<>();

    /**
     * 上下文窗口子配置
     */
    @Data
    public static class Context {
        /**
         * 上下文最大Token上限
         * 对话总token超过该值时自动截断历史消息，避免超出模型输入限制
         */
        private int maxTokens = 8000;
    }

    /**
     * 单个大模型服务商独立配置实体
     */
    @Data
    public static class Provider {
        /**
         * 当前服务商启用开关
         * true：允许调用该厂商接口；false：忽略该服务商配置，不进行路由匹配
         */
        private boolean enabled = true;

        /**
         * 服务商接口密钥
         */
        private String apiKey;

        /**
         * 服务商接口基础地址
         * 示例：https://api.openai.com/v1
         */
        private String baseUrl;

        /**
         * 当前服务商专属默认模型
         * 当请求指定该厂商但未传入model名称时，自动使用该模型
         */
        private String defaultModel;

        /**
         * 是否支持流式输出
         * 为null时自动根据模型能力自动推断，手动赋值则以配置为准
         */
        private Boolean supportsStream;

        /**
         * 是否支持工具调用Function Calling
         * 为null时自动根据模型能力自动推断，手动赋值则以配置为准
         */
        private Boolean supportsTools;
    }

    /**
     * OpenAI 服务商专属配置。继承 Provider 全部字段;P4 起可加 OpenAI 特有
     * 配置(如 organization、response_format、seed 等)。
     */
    @Data
    @EqualsAndHashCode(callSuper = false)
    public static class OpenAi extends Provider {}
}
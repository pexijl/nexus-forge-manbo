package com.nexusforge.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 大模型全局配置属性类
 * 配置前缀：spring.ai，统一管理AI服务全局开关、默认厂商、各服务商独立配置
 *
 * <p><b>P5 扩展</b>:增加 {@link QuotaConfig} 与 {@link RateLimitConfig} 两个嵌套配置,
 * 通过 {@code spring.ai.quota.*} 与 {@code spring.ai.rate-limit.*} 注入。
 *
 * <p>{@link Validated} + jakarta.validation 在启动期校验非法值(负数 / 零等),
 * 避免运行时 {@link IllegalArgumentException} 飘到业务层。{@link Valid} 触发嵌套校验,
 * 配合 {@code @Min}/{@code @Positive} 形成"自顶向下"的约束传播。
 */
@Data
@Validated
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
     * P5 — 用量配额(quota)配置。
     * 走 DB SUM 24h 滑窗,长期防刷。
     */
    @Valid
    @NestedConfigurationProperty
    @NotNull
    private QuotaConfig quota = new QuotaConfig();

    /**
     * P5 — 限流(rate limit)配置。
     * 走 Caffeine 本地令牌桶,秒级防突发。
     */
    @Valid
    @NestedConfigurationProperty
    @NotNull
    private RateLimitConfig rateLimit = new RateLimitConfig();

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

    @Data
    public static class Retry {
        /** 重试上限(含首次,默认 3 表示最多 3 次) */
        private int maxAttempts = 3;
        /** 初始退避 */
        private Duration initialBackoff = Duration.ofSeconds(1);
        /** 退避倍数 */
        private double multiplier = 2.0;
        /** 退避上限 */
        private Duration maxBackoff = Duration.ofSeconds(8);
    }

    @Data
    public static class CircuitBreaker {
        /** 窗口期内失败次数上限 */
        private int failureThreshold = 5;
        /** 窗口大小 */
        private Duration windowSize = Duration.ofSeconds(60);
        /** OPEN → HALF_OPEN 等待时长 */
        private Duration halfOpenAfter = Duration.ofSeconds(30);
    }

    @NestedConfigurationProperty
    private Retry retry = new Retry();

    @NestedConfigurationProperty
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /** 降级链,空表示不降级,只走首选 */
    private List<String> fallbackChain = new ArrayList<>();

    /**
     * P5 — 用量配额配置。
     *
     * <p>职责:DB SUM 24h 滑窗,长期防刷,抵御累积型滥用(单用户连续数小时调用)。
     * <p>与 {@link RateLimitConfig} 配合,后者是秒级防突发,前者是长期防刷。
     *
     * <p>模型:每用户按角色(USER / ADMIN)查 {@link QuotaTier},
     * 若该角色无对应配置,落到 {@link #defaultUserTier}。
     */
    @Data
    public static class QuotaConfig {

        /**
         * 配额总开关。
         * true:启用 QuotaService.check;false:放行,仅写用量不入统计。
         */
        private boolean enabled = true;

        /**
         * 默认角色标签(无 tier 命中时 fallback)。
         * 取 {@link #tiers} 的 key,大小写不敏感。
         */
        @NotNull
        private String defaultUserTier = "USER";

        /**
         * 角色 → 配额档位 映射。
         * key 形如 {@code "USER"} / {@code "ADMIN"};value 的 dailyTokenLimit
         * 与 requestLimit 均可为 null(null = 不限,只记录用量)。
         *
         * <p>{@code @Valid} 触发 {@link QuotaTier} 内部校验(@Min / @Positive 传播)。
         */
        @Valid
        @NotNull
        private Map<String, QuotaTier> tiers = defaultTiers();

        /**
         * 默认 tier 工厂。USER 档 1M token/天 + 5000 次/天;
         * ADMIN 档不限(null null)。生产可在 application.yaml 覆盖。
         */
        private static Map<String, QuotaTier> defaultTiers() {
            Map<String, QuotaTier> m = new HashMap<>();
            QuotaTier user = new QuotaTier();
            user.setDailyTokenLimit(1_000_000L);
            user.setRequestLimit(5_000L);
            m.put("USER", user);
            QuotaTier admin = new QuotaTier();
            admin.setDailyTokenLimit(null);
            admin.setRequestLimit(null);
            m.put("ADMIN", admin);
            return m;
        }
    }

    /**
     * P5 — 单档位配额。dailyTokenLimit 与 requestLimit 独立判断,
     * 任一非 null 都会触发对应校验。两者都为 null = 不限。
     */
    @Data
    public static class QuotaTier {
        /** 24h 窗口内累计 token 上限,null 表示不限 */
        @Min(0)
        private Long dailyTokenLimit;
        /** 24h 窗口内累计请求数上限,null 表示不限 */
        @Min(0)
        private Long requestLimit;
    }

    /**
     * P5 — 限流配置。Caffeine 本地令牌桶,秒级防突发。
     * 区别于 {@link QuotaConfig}:QPS 是瞬时,quota 是累积。
     *
     * <p>{@link #userQps} / {@link #userBurst} 走 userId 维度;
     * {@link #ipQps} / {@link #ipBurst} 走 IP 维度(防未登录场景)。
     */
    @Data
    public static class RateLimitConfig {

        /** 限流总开关。true:Caffeine RateLimiter 生效;false:放行。 */
        private boolean enabled = true;

        /** 单 user 平均 QPS。{@code 1.0} = 1 req/s。 */
        @Positive
        private double userQps = 1.0;

        /** 单 user 突发容量。0 = 不允许突发。 */
        @Min(0)
        private int userBurst = 5;

        /** 单 IP 平均 QPS(防未登录用户刷)。 */
        @Positive
        private double ipQps = 5.0;

        /** 单 IP 突发容量。 */
        @Min(0)
        private int ipBurst = 20;
    }

    /**
     * OpenAI 兼容子类(占位,各国产 / Ollama 都 extend 这个)。
     */
    @Data
    public static class OpenAiCompatible extends Provider {
        // 现有 OpenAi 的全部字段保留;各国产 / Ollama 都 extend 这个
    }
}

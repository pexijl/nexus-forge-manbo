package com.nexusforge.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * spring-ai-full-migration Phase 5 — 把 {@code spring.ai.providers.<vendor>.*}
 * (api-key / base-url / default-model) 桥接到各 Spring AI vendor starter
 * 认识的 {@code spring.ai.{starter-namespace}.*} namespace,让对应
 * {@code ChatModel} bean(由 {@code spring-ai-starter-model-{vendor}} 装配)
 * 看到正确的 key/value。
 *
 * <p><b>执行时机</b>:Spring Boot 启动最早阶段,在所有 starter
 * {@code AutoConfiguration} 装配 {@code ChatModel} bean 之前。注册入口在
 * {@code resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}。
 *
 * <p><b>Single source of truth</b>(Phase 5 之前):本 bridge 写出的属性以最高优先级
 * ({@link org.springframework.core.env.MutablePropertySources#addLast
 * addLast} 后再统一 {@code addFirst}) 注入,保证
 * {@code spring.ai.providers.<vendor>.api-key} 永远覆盖用户历史写在
 * {@code spring.ai.openai.api-key} 的同名字段。其他 starter namespace 字段
 * (temperature / max-tokens / seed / response_format 等)bridge 不会动,
 * 用户可继续在 starter namespace 自由配。
 *
 * <p><b>Phase 1 起 — model catalog 已迁移到 DB</b>:
 * <ul>
 *   <li>本 bridge 仍负责把 yaml 桥到 starter namespace(让 Spring AI 各 vendor
 *       starter 装配 {@code ChatModel} bean)— 这是 ChatModel bean 装配的输入,
 *       DB 替不了</li>
 *   <li>但 <b>model catalog(admin 可 CRUD 的可用 model 列表)已是 DB 持久化</b>,
 *       走 {@code ai_model_catalog} 表 + {@code ModelCatalogService}(Caffeine 缓存
 *       + 事件失效),不再从 yaml 读</li>
 *   <li>首次启动期 seed runner(见 {@code AiAutoConfiguration.aiModelCatalogSeedRunner})
 *       把 yaml {@code providers.<v>.default-model} 一次性拷到 DB(只在 catalog
 *       完全空时跑);之后 DB 优先,yaml 改 default-model 不再影响 catalog</li>
 *   <li>结论:本桥的职责从"配置 source of truth"细化为"ChatModel bean 装配
 *       的输入映射";"哪些 model 可用"由 DB 决定</li>
 * </ul>
 *
 * <p><b>协议路由</b>:commit 1 的 {@link AiProperties.Protocol} 枚举 + 推断规则
 * 决定每个 vendor 走哪个 starter namespace;
 * {@code protocol} 字段显式设置时优先于 key 名推断(anthropic/ollama
 * → 各自协议,deepseek + 其他 OpenAI 兼容厂商 → OPENAI)。{@link #inferProtocol}
 * 内部复制了一份 {@link AiProperties#resolveProtocol} 的逻辑 — 本类在
 * {@code AiProperties} bean 装配前运行,没法依赖 bean 实例;两份逻辑通过单元测试
 * 同步对齐。
 *
 * <p><b>enabled 语义</b>:{@code providers.{vendor}.enabled=false} 的 vendor
 * 整段跳过桥接(即使配了 apiKey),避免关停 vendor 的 key 出现在 env 里,
 * 污染后续 starter 装配阶段。
 *
 * <p><b>空值处理</b>:apiKey/base-url/default-model 是空串时不桥接,避免
 * 空字符串覆盖 starter namespace 的 {@code ${ENV:default}} 占位符(空串
 * 是 bind 过的值,不是"未设")。
 *
 * <p><b>协议路由表</b>(见 {@link #starterNamespace}):
 * <ul>
 *   <li>{@code OPENAI} → {@code spring.ai.openai.*}(覆盖 OpenAI / DeepSeek /
 *       Ollama / DashScope / GLM / 各种 OpenAI 兼容中转 — DeepSeek 已统一走
 *       OPENAI 协议,详见 build.gradle 注释)</li>
 *   <li>{@code ANTHROPIC} → {@code spring.ai.anthropic.*}</li>
 *   <li>{@code OLLAMA} → {@code spring.ai.ollama.*}</li>
 * </ul>
 */
public class ProviderPropertiesBridge implements EnvironmentPostProcessor {

    private static final String PROVIDERS_PREFIX = "spring.ai.providers";

    /** 桥接输出的 propertySource 名,日志 / 调试用。 */
    static final String BRIDGE_PROPERTY_SOURCE_NAME = "providerPropertiesBridge";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        // 1. 读 spring.ai.providers.* 整棵子树;Map<vendorKey, Map<subKey, value>>
        Map<String, Object> providersRaw = readProvidersTree(env);
        if (providersRaw == null || providersRaw.isEmpty()) {
            return;
        }

        // 2. 逐 vendor 桥接
        Map<String, Object> bridged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : providersRaw.entrySet()) {
            String vendor = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof Map<?, ?>)) {
                // 叶子值(用户误配了 spring.ai.providers.deepseek = "some-string")跳过
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sub = (Map<String, Object>) value;

            Boolean enabled = asBoolean(sub.get("enabled"));
            if (enabled != null && !enabled) {
                // enabled=false 整段跳过
                continue;
            }

            AiProperties.Protocol protocol = inferProtocol(env, vendor);
            String starter = starterNamespace(protocol);

            putIfPresent(sub, "api-key", bridged, starter + ".api-key");
            putIfPresent(sub, "base-url", bridged, starter + ".base-url");
            putIfPresent(sub, "default-model", bridged, starter + ".chat.options.model");
        }

        if (bridged.isEmpty()) {
            return;
        }

        // 3. 写出去:用 addFirst 让 bridge 结果优先级最高(single source of truth)。
        // 注意:addFirst 是放到 PropertySources 链头部(starter 的
        // application.yaml 链尾部),所以 bridge 永远赢。
        MapPropertySource bridgeSource = new MapPropertySource(
                BRIDGE_PROPERTY_SOURCE_NAME, bridged);
        env.getPropertySources().addFirst(bridgeSource);
    }

    /**
     * 读 {@code spring.ai.providers.*} 子树。用 {@link Binder} 而非手动 walk
     * {@link org.springframework.core.env.PropertySource} — Binder 自动
     * 遍历所有 sources(profile / default / map-ps 都算)并处理 placeholder 解析。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readProvidersTree(ConfigurableEnvironment env) {
        try {
            return Binder.get(env)
                    .bind(PROVIDERS_PREFIX, Bindable.mapOf(String.class, Object.class))
                    .orElse(null);
        } catch (Exception e) {
            // binder 阶段失败(类型不匹配等)不该阻塞启动,桥接跳过即可。
            // 真问题由 AiProperties 自己的 binder 暴露。
            return null;
        }
    }

    /**
     * 推断 vendor 走哪个 starter 协议家族。逻辑跟
     * {@link AiProperties#resolveProtocol} 一致;这里不复用 instance
     * method 是因为本类在 {@code AiProperties} bean 装配前运行。
     *
     * <p>两份逻辑通过 {@code AiPropertiesProviderProtocolTest} 的 case 1-9
     * + {@code ProviderPropertiesBridgeTest} 的相关 case 共同覆盖,改一处
     * 必须改另一处(commit message 也会同步提)。
     */
    static AiProperties.Protocol inferProtocol(ConfigurableEnvironment env, String vendorKey) {
        // 1. 显式 protocol 字段
        String explicit = env.getProperty(PROVIDERS_PREFIX + "." + vendorKey + ".protocol");
        if (explicit != null && !explicit.isBlank()) {
            try {
                return AiProperties.Protocol.valueOf(
                        explicit.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 显式 protocol 写错值 → 降级到 key 推断(不该阻塞启动;
                // 真正的 fail 由 AiProperties binder 在后面抛出)
            }
        }
        // 2. key 名推断
        if (vendorKey == null || vendorKey.isBlank()) {
            return AiProperties.Protocol.OPENAI;
        }
        return switch (vendorKey.toLowerCase(Locale.ROOT)) {
            case "anthropic" -> AiProperties.Protocol.ANTHROPIC;
            case "ollama" -> AiProperties.Protocol.OLLAMA;
            // deepseek 不再独立 starter,走 OPENAI 协议家族复用 openai starter
            default -> AiProperties.Protocol.OPENAI;
        };
    }

    /**
     * 把 {@link AiProperties.Protocol} 映射到 Spring AI starter 的 namespace prefix。
     * 桥接写出的 key 形如 {@code <prefix>.api-key} / {@code <prefix>.base-url} /
     * {@code <prefix>.chat.options.model}。
     */
    static String starterNamespace(AiProperties.Protocol p) {
        return switch (p) {
            case OPENAI -> "spring.ai.openai";
            case ANTHROPIC -> "spring.ai.anthropic";
            case OLLAMA -> "spring.ai.ollama";
        };
    }

    private static void putIfPresent(Map<String, Object> from, String key,
                                     Map<String, Object> to, String toKey) {
        Object v = from.get(key);
        if (v == null) {
            return;
        }
        String s = v.toString();
        if (s.isBlank()) {
            // 空串不桥接(避免覆盖 starter 的 ${ENV:default} 占位符)
            return;
        }
        to.put(toKey, s);
    }

    private static Boolean asBoolean(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        }
        return null;
    }
}

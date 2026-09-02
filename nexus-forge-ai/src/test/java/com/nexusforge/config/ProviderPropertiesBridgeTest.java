package com.nexusforge.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * spring-ai-full-migration Phase 5: {@link ProviderPropertiesBridge} 把
 * {@code spring.ai.providers.<vendor>.*} 桥接到对应 starter namespace 的行为。
 *
 * <p>本测试不依赖 SpringApplication 启动,用 {@link StandardEnvironment}
 * + 手动 addLast {@link MapPropertySource} 模拟 yaml 加载,然后直接调
 * {@code bridge.postProcessEnvironment(env, null)}。SpringApplication 参数
 * 桥接器不读,传 null 也安全;为对齐接口签名,这里 mock 一个空实例。
 *
 * <p>本测试不覆盖推断逻辑本身(那是 {@code AiPropertiesProviderProtocolTest}
 * 的职责),只覆盖"推断结果 → 桥接到哪个 namespace + 3 个字段的传递"
 * 链路 + edge case(空值 / enabled=false / 不存在的 vendor namespace)。
 */
@DisplayName("ProviderPropertiesBridge 桥接")
class ProviderPropertiesBridgeTest {

    private ConfigurableEnvironment env;
    private final ProviderPropertiesBridge bridge = new ProviderPropertiesBridge();
    private static final SpringApplication APP = mock(SpringApplication.class);

    @BeforeEach
    void setUp() {
        env = new StandardEnvironment();
        // 清理 StandardEnvironment 默认加的 systemProperties / systemEnvironment,
        // 避免 CI 机器上意外 env 变量污染测试断言(例如 OPENAI_API_KEY)。
        // MutablePropertySources 没有 removeIf,只能 collect names 然后 remove。
        env.getPropertySources().stream()
                .map(org.springframework.core.env.PropertySource::getName)
                .filter(n -> "systemProperties".equals(n) || "systemEnvironment".equals(n))
                .forEach(env.getPropertySources()::remove);
    }

    /** 模拟 yaml 加载:把测试输入的 properties 放到 env 里。 */
    private void loadYaml(Map<String, Object> props) {
        MapPropertySource yaml = new MapPropertySource("testYaml", new LinkedHashMap<>(props));
        env.getPropertySources().addLast(yaml);
    }

    // ─────────────────── 协议路由 ───────────────────

    @Nested
    @DisplayName("协议路由(推断 → starter namespace)")
    class ProtocolRouting {

        @Test
        @DisplayName("deepseek → spring.ai.openai.*(DeepSeek 已统一走 OpenAI starter,不再有独立 deepseek namespace)")
        void deepseek_bridges_to_openai_namespace() {
            loadYaml(Map.of(
                    "spring.ai.providers.deepseek.api-key", "sk-deepseek",
                    "spring.ai.providers.deepseek.base-url", "https://api.deepseek.com",
                    "spring.ai.providers.deepseek.default-model", "deepseek-v4-flash"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getProperty("spring.ai.openai.api-key"))
                    .as("deepseek key 桥接到 spring.ai.openai.api-key")
                    .isEqualTo("sk-deepseek");
            assertThat(env.getProperty("spring.ai.openai.base-url"))
                    .isEqualTo("https://api.deepseek.com");
            assertThat(env.getProperty("spring.ai.openai.chat.options.model"))
                    .isEqualTo("deepseek-v4-flash");
            // spring.ai.deepseek.* 已无 starter 装配,bridge 不写
            assertThat(env.getProperty("spring.ai.deepseek.api-key")).isNull();
        }

        @Test
        @DisplayName("anthropic → spring.ai.anthropic.*")
        void anthropic_bridges_to_anthropic_namespace() {
            loadYaml(Map.of(
                    "spring.ai.providers.anthropic.api-key", "sk-anthropic",
                    "spring.ai.providers.anthropic.default-model", "claude-3-5-haiku-latest"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getProperty("spring.ai.anthropic.api-key"))
                    .isEqualTo("sk-anthropic");
            assertThat(env.getProperty("spring.ai.anthropic.chat.options.model"))
                    .isEqualTo("claude-3-5-haiku-latest");
        }

        @Test
        @DisplayName("ollama → spring.ai.ollama.*(无 apiKey 也能桥 base-url)")
        void ollama_bridges_to_ollama_namespace() {
            loadYaml(Map.of(
                    "spring.ai.providers.ollama.base-url", "http://localhost:11434",
                    "spring.ai.providers.ollama.default-model", "llama3.1"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getProperty("spring.ai.ollama.base-url"))
                    .isEqualTo("http://localhost:11434");
            assertThat(env.getProperty("spring.ai.ollama.chat.options.model"))
                    .isEqualTo("llama3.1");
            // ollama 没有 api-key starter 字段,不桥
            assertThat(env.getProperty("spring.ai.ollama.api-key")).isNull();
        }

        @Test
        @DisplayName("openai / dashscope / glm / minimax → spring.ai.openai.*(OpenAI 协议家族)")
        void openai_family_bridges_to_openai_namespace() {
            for (String vendor : new String[]{"openai", "dashscope", "glm", "minimax"}) {
                // 每个 vendor 独立 env 跑(避免 Map 互相覆盖断言)
                env = new StandardEnvironment();
                env.getPropertySources().stream()
                        .map(org.springframework.core.env.PropertySource::getName)
                        .filter(n -> "systemProperties".equals(n) || "systemEnvironment".equals(n))
                        .forEach(env.getPropertySources()::remove);
                loadYaml(Map.of(
                        "spring.ai.providers." + vendor + ".api-key", "sk-" + vendor
                ));
                bridge.postProcessEnvironment(env, APP);

                assertThat(env.getProperty("spring.ai.openai.api-key"))
                        .as("vendor=%s 应桥到 spring.ai.openai.api-key", vendor)
                        .isEqualTo("sk-" + vendor);
            }
        }

        @Test
        @DisplayName("显式 protocol 优先:providers.openai.protocol=anthropic → 桥到 anthropic namespace")
        void explicit_protocol_overrides_key_inference() {
            loadYaml(Map.of(
                    "spring.ai.providers.openai.protocol", "anthropic",
                    "spring.ai.providers.openai.api-key", "sk-openai-via-anthropic"
            ));
            bridge.postProcessEnvironment(env, APP);

            // key 名是 openai(默认推断 OPENAI),但 protocol 显式 anthropic → 桥到 anthropic
            assertThat(env.getProperty("spring.ai.anthropic.api-key"))
                    .isEqualTo("sk-openai-via-anthropic");
            assertThat(env.getProperty("spring.ai.openai.api-key")).isNull();
        }

        @Test
        @DisplayName("显式 protocol=openai 保留为 openai namespace(回归覆盖)")
        void explicit_protocol_openai_routes_to_openai() {
            // 显式 protocol: openai 跟默认推断一致 — 但走"显式"路径,
            // 确保没有 bug 让 OPENAI 被误桥到别的 namespace
            loadYaml(Map.of(
                    "spring.ai.providers.openai.protocol", "openai",
                    "spring.ai.providers.openai.api-key", "sk-explicit-openai"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getProperty("spring.ai.openai.api-key"))
                    .isEqualTo("sk-explicit-openai");
        }
    }

    // ─────────────────── 字段处理 ───────────────────

    @Nested
    @DisplayName("字段桥接行为")
    class FieldHandling {

        @Test
        @DisplayName("空 apiKey 字符串不桥接(避免空覆盖 starter 的 ${ENV:default} 占位符)")
        void blank_api_key_not_bridged() {
            loadYaml(Map.of(
                    "spring.ai.providers.openai.api-key", "",
                    "spring.ai.providers.openai.base-url", "https://api.openai.com/v1"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getProperty("spring.ai.openai.api-key")).isNull();
            // base-url 仍然桥(非空)
            assertThat(env.getProperty("spring.ai.openai.base-url"))
                    .isEqualTo("https://api.openai.com/v1");
        }

        @Test
        @DisplayName("只配了 base-url 没配 apiKey → 只桥 base-url")
        void only_base_url_bridges_only_base_url() {
            loadYaml(Map.of(
                    "spring.ai.providers.deepseek.base-url", "https://api.deepseek.com"
            ));
            bridge.postProcessEnvironment(env, APP);

            // deepseek key → openai namespace
            assertThat(env.getProperty("spring.ai.openai.base-url"))
                    .isEqualTo("https://api.deepseek.com");
            assertThat(env.getProperty("spring.ai.openai.api-key")).isNull();
            assertThat(env.getProperty("spring.ai.openai.chat.options.model")).isNull();
        }

        @Test
        @DisplayName("其他 starter namespace 字段不被桥接覆盖(temperature / max-tokens 等保留)")
        void other_starter_namespace_fields_preserved() {
            // 模拟用户历史在 starter namespace 配了 temperature
            loadYaml(Map.of(
                    "spring.ai.openai.chat.options.temperature", "0.7",
                    "spring.ai.openai.chat.options.max-tokens", "4096",
                    "spring.ai.providers.openai.api-key", "sk-openai"
            ));
            bridge.postProcessEnvironment(env, APP);

            // bridge 只写它关心的 3 个字段,temperature/max-tokens 保留
            assertThat(env.getProperty("spring.ai.openai.chat.options.temperature"))
                    .isEqualTo("0.7");
            assertThat(env.getProperty("spring.ai.openai.chat.options.max-tokens"))
                    .isEqualTo("4096");
            assertThat(env.getProperty("spring.ai.openai.api-key"))
                    .isEqualTo("sk-openai");
        }

        @Test
        @DisplayName("addFirst 优先级:用户原 starter 配的 api-key 被 providers.* 覆盖")
        void bridge_overrides_existing_starter_namespace_key() {
            loadYaml(Map.of(
                    // 用户在 starter namespace 配了"历史值"
                    "spring.ai.openai.api-key", "sk-original-from-yaml",
                    "spring.ai.providers.openai.api-key", "sk-from-providers"
            ));
            bridge.postProcessEnvironment(env, APP);

            // bridge 写出去的优先级最高,值是 providers.* 的
            assertThat(env.getProperty("spring.ai.openai.api-key"))
                    .isEqualTo("sk-from-providers");
        }
    }

    // ─────────────────── enabled 语义 ───────────────────

    @Nested
    @DisplayName("enabled 语义")
    class EnabledSemantics {

        @Test
        @DisplayName("enabled=false 的 vendor 整段跳过(即使配了 apiKey)")
        void disabled_vendor_not_bridged() {
            loadYaml(Map.of(
                    "spring.ai.providers.deepseek.enabled", "false",
                    "spring.ai.providers.deepseek.api-key", "sk-should-not-appear",
                    "spring.ai.providers.deepseek.base-url", "https://api.deepseek.com",
                    "spring.ai.providers.openai.api-key", "sk-openai"
            ));
            bridge.postProcessEnvironment(env, APP);

            // deepseek enabled=false → 整段不桥(无论是 deepseek namespace 还是
            // openai namespace — 因为 deepseek 推断协议是 OPENAI)
            assertThat(env.getProperty("spring.ai.openai.api-key"))
                    .as("deepseek enabled=false,即使 deepseek 协议推断是 OPENAI,也不该被桥到 spring.ai.openai.api-key")
                    .isEqualTo("sk-openai"); // 来自另一段 providers.openai,不是被 deepseek 覆盖
            // deepseek 的 base-url 也不该被桥到 openai namespace
            assertThat(env.getProperty("spring.ai.openai.base-url")).isNull();
        }

        @Test
        @DisplayName("enabled=true 显式设 → 正常桥接")
        void enabled_true_bridges_normally() {
            loadYaml(Map.of(
                    "spring.ai.providers.openai.enabled", "true",
                    "spring.ai.providers.openai.api-key", "sk-openai"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getProperty("spring.ai.openai.api-key"))
                    .isEqualTo("sk-openai");
        }
    }

    // ─────────────────── 边界 / No-op ───────────────────

    @Nested
    @DisplayName("No-op / 边界")
    class NoOp {

        @Test
        @DisplayName("无任何 spring.ai.providers.* 配置 → 不动 env,不加 property source")
        void no_providers_no_op() {
            loadYaml(Map.of(
                    "some.other.config", "value",
                    "spring.ai.enabled", "true"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getProperty("spring.ai.openai.api-key")).isNull();
            assertThat(env.getProperty("spring.ai.deepseek.api-key")).isNull();
            assertThat(env.getPropertySources().contains(
                    ProviderPropertiesBridge.BRIDGE_PROPERTY_SOURCE_NAME))
                    .as("无 providers.* 配置时不应该加 bridge property source")
                    .isFalse();
        }

        @Test
        @DisplayName("providers.* 全是空段(只设 enabled=false)→ bridge property source 不创建")
        void all_disabled_no_bridge_source() {
            loadYaml(Map.of(
                    "spring.ai.providers.deepseek.enabled", "false",
                    "spring.ai.providers.openai.enabled", "false"
            ));
            bridge.postProcessEnvironment(env, APP);

            assertThat(env.getPropertySources().contains(
                    ProviderPropertiesBridge.BRIDGE_PROPERTY_SOURCE_NAME))
                    .isFalse();
        }

        @Test
        @DisplayName("原 spring.ai.providers.<vendor>.api-key 值保留(bridge 只 addFirst 桥接,不动原值)")
        void providers_api_key_preserved() {
            loadYaml(Map.of(
                    "spring.ai.providers.deepseek.api-key", "sk-deepseek"
            ));
            bridge.postProcessEnvironment(env, APP);

            // 原值(用户配置的 spring.ai.providers.*)保留
            assertThat(env.getProperty("spring.ai.providers.deepseek.api-key"))
                    .isEqualTo("sk-deepseek");
            // bridge 输出也写出去(starter 读这个)— deepseek key 路由到 openai namespace
            assertThat(env.getProperty("spring.ai.openai.api-key"))
                    .isEqualTo("sk-deepseek");
        }

        @Test
        @DisplayName("推断逻辑与 AiProperties.resolveProtocol 保持一致(deepseek key → OPENAI)")
        void infer_protocol_matches_ai_properties_logic() {
            // 这是 commit 1 + commit 2 两边推断逻辑必须同步的对齐测试
            AiProperties props = new AiProperties();
            for (String vendor : new String[]{
                    "deepseek", "anthropic", "ollama",
                    "openai", "dashscope", "glm", "minimax", "unknown-vendor"}) {
                AiProperties.Protocol viaInstance = props.resolveProtocol(vendor);
                AiProperties.Protocol viaStatic = ProviderPropertiesBridge.inferProtocol(env, vendor);
                assertThat(viaStatic)
                        .as("vendor=%s 推断逻辑两边必须一致", vendor)
                        .isEqualTo(viaInstance);
                // 显式断言 deepseek 走 OPENAI(防止后续改动悄悄改回 DEEPSEEK)
                if ("deepseek".equals(vendor)) {
                    assertThat(viaInstance).isEqualTo(AiProperties.Protocol.OPENAI);
                }
            }
        }
    }
}

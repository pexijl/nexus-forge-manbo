package com.nexusforge.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spring-ai-full-migration Phase 5 + DeepSeek 移除: {@link AiProperties.Provider#protocol}
 * 字段 binder + {@link AiProperties#resolveProtocol(String)} 推断逻辑。
 *
 * <p>本测试只覆盖 Phase 5 引入的 protocol 字段相关行为 — 其他 AiProperties 行为
 * (quota / rate-limit / 校验)由 {@link AiPropertiesBindingTest} 覆盖,避免重复。
 *
 * <p>关键设计点(详细见 {@link AiProperties.Provider#protocol} 字段 javadoc):
 * <ol>
 *   <li>显式 {@code protocol} 优先于 key 名推断;</li>
 *   <li>未设置时按 key 名走默认映射(anthropic/ollama → 各自协议,
 *       deepseek + 其他 OpenAI 兼容厂商 → OPENAI — DeepSeek 之前有独立
 *       starter,本项目已统一移除);</li>
 *   <li>大小写不敏感;</li>
 *   <li>null/空 vendor key 安全(降级 OPENAI,不抛)。</li>
 * </ol>
 */
@DisplayName("AiProperties Provider.protocol + resolveProtocol")
class AiPropertiesProviderProtocolTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(EnableConfig.class);

    @Configuration
    @EnableConfigurationProperties(AiProperties.class)
    static class EnableConfig { }

    // ─────────────────── Protocol enum ───────────────────

    @Nested
    @DisplayName("Protocol enum 包含 3 个 Spring AI 协议家族")
    class ProtocolEnumValues {

        @Test
        @DisplayName("枚举值 OPENAI / ANTHROPIC / OLLAMA 全部存在(DEEPSEEK 已移除)")
        void all_three_protocols_declared() {
            assertThat(AiProperties.Protocol.values())
                    .containsExactly(
                            AiProperties.Protocol.OPENAI,
                            AiProperties.Protocol.ANTHROPIC,
                            AiProperties.Protocol.OLLAMA
                    );
        }

        @Test
        @DisplayName("valueOf 大小写敏感(enum 标准语义)")
        void valueOf_case_sensitive() {
            // 跟 Spring Boot relaxed binding 区别:enum 自身 valueOf 仍大小写敏感
            // (binder 阶段才会被 relaxed binding 转大写)
            assertThat(AiProperties.Protocol.valueOf("OPENAI"))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
            assertThatCodeLowercaseRejected();
        }

        @Test
        @DisplayName("DEEPSEEK 枚举值已移除(DeepSeek 改走 OpenAI starter)")
        void deepseek_enum_value_removed() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> AiProperties.Protocol.valueOf("DEEPSEEK"))
                    .as("DEEPSEEK 枚举值已移除,valueOf 应抛 IllegalArgumentException")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private void assertThatCodeLowercaseRejected() {
            try {
                AiProperties.Protocol.valueOf("openai");
                org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // 期望路径
            }
        }
    }

    // ─────────────────── Protocol binder ───────────────────

    @Nested
    @DisplayName("Provider.protocol 字段 binder")
    class ProtocolBinding {

        @Test
        @DisplayName("YAML 显式设 spring.ai.providers.openai.protocol=anthropic → Provider.protocol=ANTHROPIC")
        void explicit_protocol_binds() {
            runner.withPropertyValues(
                    "spring.ai.providers.openai.protocol=anthropic"
            ).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                AiProperties props = ctx.getBean(AiProperties.class);
                AiProperties.Provider p = props.getProviders().get("openai");
                assertThat(p).isNotNull();
                assertThat(p.getProtocol()).isEqualTo(AiProperties.Protocol.ANTHROPIC);
            });
        }

        @Test
        @DisplayName("YAML 写小写 'openai' 也能 binder(Spring Boot relaxed binding 转大写)")
        void lowercase_yaml_value_binds() {
            runner.withPropertyValues(
                    "spring.ai.providers.openai.protocol=openai"
            ).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                AiProperties.Provider p = ctx.getBean(AiProperties.class)
                        .getProviders().get("openai");
                assertThat(p).isNotNull();
                assertThat(p.getProtocol()).isEqualTo(AiProperties.Protocol.OPENAI);
            });
        }

        @Test
        @DisplayName("YAML 写错的值(openai2)→ binder 抛 BindException(不让容器静默启动)")
        void invalid_protocol_value_fails_bind() {
            runner.withPropertyValues(
                    "spring.ai.providers.openai.protocol=openai2"
            ).run(ctx -> {
                assertThat(ctx).hasFailed();
                // 失败根因是 ConversionFailedException(enum 转换不上),
                // binder 阶段就拒绝,符合"fail-fast"原则
                assertThat(ctx.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("openai2");
            });
        }

        @Test
        @DisplayName("YAML 显式写 protocol=deepseek(枚举已移除)→ binder 抛 BindException")
        void deepseek_protocol_value_fails_bind() {
            // 旧 yaml 可能写 protocol: deepseek,移除 DEEPSEEK 枚举后,binder 阶段
            // 就会 fail-fast,暴露给用户改 yaml — 比静默用错值好
            runner.withPropertyValues(
                    "spring.ai.providers.openai.protocol=deepseek"
            ).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("deepseek");
            });
        }

        @Test
        @DisplayName("YAML 不写 protocol 字段 → Provider.protocol=null(由 resolveProtocol 推断)")
        void no_protocol_field_leaves_null() {
            runner.withPropertyValues(
                    "spring.ai.providers.openai.base-url=https://api.openai.com/v1"
            ).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                AiProperties.Provider p = ctx.getBean(AiProperties.class)
                        .getProviders().get("openai");
                assertThat(p).isNotNull();
                assertThat(p.getProtocol()).isNull();
                assertThat(p.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
            });
        }
    }

    // ─────────────────── resolveProtocol 推断 ───────────────────

    @Nested
    @DisplayName("AiProperties.resolveProtocol 推断")
    class ResolveProtocol {

        @Test
        @DisplayName("deepseek → OPENAI(DeepSeek 已统一走 OpenAI starter)")
        void deepseek_inferred_as_openai() {
            AiProperties props = new AiProperties();
            assertThat(props.resolveProtocol("deepseek"))
                    .as("deepseek key 推断为 OPENAI,DeepSeek API 走 OpenAI Chat Completions 协议")
                    .isEqualTo(AiProperties.Protocol.OPENAI);
        }

        @Test
        @DisplayName("anthropic → ANTHROPIC")
        void anthropic_inferred() {
            AiProperties props = new AiProperties();
            assertThat(props.resolveProtocol("anthropic"))
                    .isEqualTo(AiProperties.Protocol.ANTHROPIC);
        }

        @Test
        @DisplayName("ollama → OLLAMA")
        void ollama_inferred() {
            AiProperties props = new AiProperties();
            assertThat(props.resolveProtocol("ollama"))
                    .isEqualTo(AiProperties.Protocol.OLLAMA);
        }

        @Test
        @DisplayName("openai / dashscope / glm / minimax / 中转站 → OPENAI(OpenAI 协议家族共用)")
        void openai_family_inferred() {
            AiProperties props = new AiProperties();
            for (String vendor : new String[]{"openai", "dashscope", "glm", "minimax",
                    "siliconflow", "oneapi", "kimi", "doubao", "hunyuan"}) {
                assertThat(props.resolveProtocol(vendor))
                        .as("vendor=%s 应走 OPENAI 协议家族", vendor)
                        .isEqualTo(AiProperties.Protocol.OPENAI);
            }
        }

        @Test
        @DisplayName("大小写不敏感(DeepSeek / DEEPSEEK 都映射到 OPENAI,Anthropic → ANTHROPIC,Ollama → OLLAMA)")
        void case_insensitive() {
            AiProperties props = new AiProperties();
            assertThat(props.resolveProtocol("DeepSeek"))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
            assertThat(props.resolveProtocol("DEEPSEEK"))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
            assertThat(props.resolveProtocol("Anthropic"))
                    .isEqualTo(AiProperties.Protocol.ANTHROPIC);
            assertThat(props.resolveProtocol("Ollama"))
                    .isEqualTo(AiProperties.Protocol.OLLAMA);
        }

        @Test
        @DisplayName("null / 空 vendor key → OPENAI(安全降级,不抛)")
        void null_and_blank_vendor_safe() {
            AiProperties props = new AiProperties();
            assertThat(props.resolveProtocol(null))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
            assertThat(props.resolveProtocol(""))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
            assertThat(props.resolveProtocol("   "))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
        }

        @Test
        @DisplayName("未知 vendor 名(随便写的 key)→ OPENAI(降级安全,失败留给启动期 ChatModel 装配暴露)")
        void unknown_vendor_falls_back_to_openai() {
            AiProperties props = new AiProperties();
            assertThat(props.resolveProtocol("totally-fake-vendor"))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
            assertThat(props.resolveProtocol("qwq"))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
        }

        @Test
        @DisplayName("Provider.protocol 显式设值时优先于 key 名推断")
        void explicit_protocol_wins_over_key_inference() {
            AiProperties props = new AiProperties();
            // key 名是 openai(默认会推断 OPENAI),但 protocol 显式设 ANTHROPIC
            // (场景:openai vendor key 接 Anthropic Messages 兼容中转)
            AiProperties.Provider p = new AiProperties.Provider();
            p.setProtocol(AiProperties.Protocol.ANTHROPIC);
            props.getProviders().put("openai", p);

            assertThat(props.resolveProtocol("openai"))
                    .isEqualTo(AiProperties.Protocol.ANTHROPIC);
        }

        @Test
        @DisplayName("Provider 存在但 protocol=null 时仍走 key 名推断(非 fallback 到 OPENAI)")
        void provider_without_protocol_still_uses_key_inference() {
            AiProperties props = new AiProperties();
            // Provider 存在,但 protocol 字段没设(null)→ 走 key 名推断
            AiProperties.Provider p = new AiProperties.Provider();
            p.setEnabled(true);
            p.setBaseUrl("https://api.deepseek.com");
            props.getProviders().put("deepseek", p);

            // deepseek key 推断为 OPENAI(DeepSeek 走 OpenAI starter)
            assertThat(props.resolveProtocol("deepseek"))
                    .isEqualTo(AiProperties.Protocol.OPENAI);
        }
    }
}

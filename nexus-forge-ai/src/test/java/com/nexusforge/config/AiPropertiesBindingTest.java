package com.nexusforge.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 Step 2: {@link AiProperties} YAML 绑定 + 校验。
 *
 * <p>用 {@link ApplicationContextRunner} 走轻量级 context(不启 web / JPA),
 * 验证:
 * <ol>
 *   <li>默认值 — 全部不带 spring.ai.* 块时,默认值落到 defaultTiers;</li>
 *   <li>YAML 覆盖 — 设 spring.ai.quota.enabled=false 后属性正确反序列化;</li>
 *   <li>JSR-303 校验 — 构造一个不合法 QuotaTier 走 {@link Validator} 显式断言
 *       {@code @Min}/{@code @Positive} 命中。Boot 4.1 的
 *       {@code ApplicationContextRunner} 不会自动装配
 *       {@code MethodValidationPostProcessor},故这里用 jakarta.validation API 直接跑。</li>
 * </ol>
 */
@DisplayName("AiProperties YAML 绑定 + 校验")
class AiPropertiesBindingTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) factory.close();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(EnableConfig.class);

    @Configuration
    @EnableConfigurationProperties(AiProperties.class)
    static class EnableConfig { }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("无任何 spring.ai 配置 → 全部走默认(enabled=true, USER tier 1M token, userQps=1.0)")
        void default_values_bind() {
            runner.run(ctx -> {
                assertThat(ctx).hasNotFailed();
                AiProperties props = ctx.getBean(AiProperties.class);

                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getDefaultVendor()).isEqualTo("openai");
                assertThat(props.getDefaultModel()).isEqualTo("gpt-4o-mini");

                // quota
                assertThat(props.getQuota().isEnabled()).isTrue();
                assertThat(props.getQuota().getDefaultUserTier()).isEqualTo("USER");
                assertThat(props.getQuota().getTiers()).containsKeys("USER", "ADMIN");
                assertThat(props.getQuota().getTiers().get("USER").getDailyTokenLimit()).isEqualTo(1_000_000L);
                assertThat(props.getQuota().getTiers().get("USER").getRequestLimit()).isEqualTo(5_000L);
                // ADMIN 默认不限
                assertThat(props.getQuota().getTiers().get("ADMIN").getDailyTokenLimit()).isNull();
                assertThat(props.getQuota().getTiers().get("ADMIN").getRequestLimit()).isNull();

                // rateLimit
                assertThat(props.getRateLimit().isEnabled()).isTrue();
                assertThat(props.getRateLimit().getUserQps()).isEqualTo(1.0);
                assertThat(props.getRateLimit().getUserBurst()).isEqualTo(5);
                assertThat(props.getRateLimit().getIpQps()).isEqualTo(5.0);
                assertThat(props.getRateLimit().getIpBurst()).isEqualTo(20);
            });
        }

        @Test
        @DisplayName("默认 bean 通过 JSR-303 校验(无 violation)")
        void default_bean_is_valid() {
            AiProperties props = new AiProperties();
            Set<ConstraintViolation<AiProperties>> violations = validator.validate(props);
            assertThat(violations).isEmpty();
        }
    }

    @Nested
    @DisplayName("YamlOverride")
    class YamlOverride {

        @Test
        @DisplayName("YAML 覆盖 quota.enabled=false, USER 配 100 token, userQps=2.5")
        void yaml_overrides_apply() {
            runner.withPropertyValues(
                    "spring.ai.quota.enabled=false",
                    "spring.ai.quota.tiers.USER.daily-token-limit=100",
                    "spring.ai.quota.tiers.USER.request-limit=10",
                    "spring.ai.rate-limit.user-qps=2.5",
                    "spring.ai.rate-limit.user-burst=8"
            ).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                AiProperties props = ctx.getBean(AiProperties.class);

                assertThat(props.getQuota().isEnabled()).isFalse();
                assertThat(props.getQuota().getTiers().get("USER").getDailyTokenLimit()).isEqualTo(100L);
                assertThat(props.getQuota().getTiers().get("USER").getRequestLimit()).isEqualTo(10L);
                assertThat(props.getRateLimit().getUserQps()).isEqualTo(2.5);
                assertThat(props.getRateLimit().getUserBurst()).isEqualTo(8);
            });
        }

        @Test
        @DisplayName("新增 role tier — YAML 注入 VIP(走 Map<String, QuotaTier> 路径)")
        void custom_role_tier_adds() {
            runner.withPropertyValues(
                    "spring.ai.quota.tiers.VIP.daily-token-limit=10000000",
                    "spring.ai.quota.tiers.VIP.request-limit=50000"
            ).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                AiProperties props = ctx.getBean(AiProperties.class);
                assertThat(props.getQuota().getTiers()).containsKey("VIP");
                assertThat(props.getQuota().getTiers().get("VIP").getDailyTokenLimit()).isEqualTo(10_000_000L);
            });
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("QuotaTier.dailyTokenLimit=-1 → @Min(0) violation")
        void negative_daily_token_limit_caught() {
            AiProperties props = new AiProperties();
            AiProperties.QuotaTier bad = new AiProperties.QuotaTier();
            bad.setDailyTokenLimit(-1L);
            bad.setRequestLimit(10L);
            props.getQuota().getTiers().put("USER", bad);

            Set<ConstraintViolation<AiProperties>> violations = validator.validate(props);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anySatisfy(v -> {
                        assertThat(v.getPropertyPath().toString())
                                .contains("quota.tiers[USER].dailyTokenLimit");
                        assertThat(v.getMessage()).contains("0");
                    });
        }

        @Test
        @DisplayName("RateLimitConfig.userQps=0 → @Positive violation")
        void zero_user_qps_caught() {
            AiProperties props = new AiProperties();
            props.getRateLimit().setUserQps(0.0);

            Set<ConstraintViolation<AiProperties>> violations = validator.validate(props);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anySatisfy(v -> {
                        assertThat(v.getPropertyPath().toString())
                                .contains("rateLimit.userQps");
                    });
        }

        @Test
        @DisplayName("RateLimitConfig.ipBurst=-1 → @Min(0) violation")
        void negative_ip_burst_caught() {
            AiProperties props = new AiProperties();
            props.getRateLimit().setIpBurst(-1);

            Set<ConstraintViolation<AiProperties>> violations = validator.validate(props);
            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .anySatisfy(v -> {
                        assertThat(v.getPropertyPath().toString())
                                .contains("rateLimit.ipBurst");
                    });
        }
    }
}

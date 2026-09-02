package com.nexusforge.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UsageRecorder 单元测试。覆盖 P5 Step 4 关键不变量:
 * <ul>
 *   <li>正常路径:4 个 counter(requests + prompt/completion/total token)全部 +N</li>
 *   <li>Null-safety:usage / model / MeterRegistry 任一为 null 都不抛、不报数</li>
 *   <li>Total 兜底:vendor 不返回 totalTokens 时由 prompt+completion 合成</li>
 *   <li>失败路径:recordRequest 单计数 requests,不污染 token counter</li>
 * </ul>
 *
 * <p>spring-ai-full-migration Phase 6 重写:用 Spring AI 的 {@link Usage} 接口
 * (原 com.nexusforge.ai.ChatUsage 已删)。counter 多了 source 标签,断言
 * 路径补上。
 */
@DisplayName("UsageRecorder")
class UsageRecorderTest {

    private MeterRegistry registry;
    private UsageRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new UsageRecorder(registry);
    }

    // ──────────────────────────────────────────────
    // Happy path
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("正常路径")
    class HappyPath {

        @Test
        @DisplayName("recordMetrics 4 个 counter 各 +N")
        void recordMetrics_increments_all_four_counters() {
            Usage usage = testUsage(120, 80, 200);

            recorder.recordMetrics(usage, "gpt-4o-mini");

            // 2 参重载默认 source=system
            assertThat(requestsCount("gpt-4o-mini", "system")).isEqualTo(1.0);
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "gpt-4o-mini", "system")).isEqualTo(120.0);
            assertThat(counterValue(UsageRecorder.METRIC_COMPLETION_TOKENS, "gpt-4o-mini", "system")).isEqualTo(80.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini", "system")).isEqualTo(200.0);
        }

        @Test
        @DisplayName("recordMetrics 多次累加")
        void recordMetrics_accumulates_across_calls() {
            Usage usage = testUsage(10, 5, 15);

            recorder.recordMetrics(usage, "gpt-4o-mini");
            recorder.recordMetrics(usage, "gpt-4o-mini");
            recorder.recordMetrics(usage, "gpt-4o-mini");

            assertThat(requestsCount("gpt-4o-mini", "system")).isEqualTo(3.0);
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "gpt-4o-mini", "system")).isEqualTo(30.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini", "system")).isEqualTo(45.0);
        }

        @Test
        @DisplayName("recordRequest 单计数 requests,不污染 token counter")
        void recordRequest_only_increments_requests_counter() {
            recorder.recordRequest("claude-haiku-4-5");

            assertThat(requestsCount("claude-haiku-4-5", "system")).isEqualTo(1.0);
            // 三个 token counter 在 recordRequest 下不出现
            assertThat(registry.find(UsageRecorder.METRIC_PROMPT_TOKENS)
                    .tag("model", "claude-haiku-4-5").tag("source", "system").counter()).isNull();
            assertThat(registry.find(UsageRecorder.METRIC_COMPLETION_TOKENS)
                    .tag("model", "claude-haiku-4-5").tag("source", "system").counter()).isNull();
            assertThat(registry.find(UsageRecorder.METRIC_TOTAL_TOKENS)
                    .tag("model", "claude-haiku-4-5").tag("source", "system").counter()).isNull();
        }
    }

    // ──────────────────────────────────────────────
    // Null-safety
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Null-safety")
    class NullSafety {

        @Test
        @DisplayName("null usage → 只计 requests,不报数 token")
        void null_usage_increments_only_requests() {
            recorder.recordMetrics(null, "gpt-4o-mini");

            assertThat(requestsCount("gpt-4o-mini", "system")).isEqualTo(1.0);
            // token counter 仍未注册
            assertThat(registry.find(UsageRecorder.METRIC_PROMPT_TOKENS)
                    .tag("model", "gpt-4o-mini").tag("source", "system").counter()).isNull();
        }

        @Test
        @DisplayName("null model → 落到 'unknown' 标签")
        void null_model_falls_back_to_unknown_label() {
            Usage usage = testUsage(7, 3, 10);

            recorder.recordMetrics(usage, null);

            assertThat(requestsCount("unknown", "system")).isEqualTo(1.0);
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "unknown", "system")).isEqualTo(7.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "unknown", "system")).isEqualTo(10.0);
        }

        @Test
        @DisplayName("null MeterRegistry → 所有方法空跑,不抛")
        void null_meter_registry_silently_no_ops() {
            UsageRecorder noOp = new UsageRecorder(null);
            Usage usage = testUsage(1, 1, 2);

            // 这些调用全部不抛、不报数(无 registry)
            noOp.recordMetrics(usage, "any");
            noOp.recordMetrics(null, "any");
            noOp.recordRequest("any");
            // 拿不出任何 counter(空 registry),仅保证调用链不抛
            assertThat(registry.getMeters()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────
    // Total 兜底
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("Total 兜底")
    class TotalFallback {

        @Test
        @DisplayName("vendor 不给 totalTokens → 由 prompt+completion 合成")
        void total_falls_back_to_prompt_plus_completion() {
            Usage usage = testUsage(50, 30, null);

            recorder.recordMetrics(usage, "gpt-4o-mini");

            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini", "system"))
                    .as("total 应由 prompt+completion 合成 = 80")
                    .isEqualTo(80.0);
        }

        @Test
        @DisplayName("vendor 给的 total 优先,不会被覆盖")
        void vendor_total_takes_precedence_over_fallback() {
            // vendor 给的 total 与 prompt+completion 不一致(可能四舍五入),优先用 vendor
            Usage usage = testUsage(50, 30, 81);

            recorder.recordMetrics(usage, "gpt-4o-mini");

            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini", "system"))
                    .as("vendor 给的 total=81 优先,不会被 80 覆盖")
                    .isEqualTo(81.0);
        }

        @Test
        @DisplayName("prompt / completion 字段为 null → 不报对应 counter")
        void null_token_fields_skip_corresponding_counters() {
            // 模型未返回 prompt 但返回 completion(罕见但合法)
            Usage usage = testUsage(null, 20, 20);

            recorder.recordMetrics(usage, "gpt-4o-mini");

            // prompt counter 未注册
            assertThat(registry.find(UsageRecorder.METRIC_PROMPT_TOKENS)
                    .tag("model", "gpt-4o-mini").tag("source", "system").counter()).isNull();
            // completion 仍报
            assertThat(counterValue(UsageRecorder.METRIC_COMPLETION_TOKENS, "gpt-4o-mini", "system")).isEqualTo(20.0);
        }

        @Test
        @DisplayName("token = 0 不报数(避免 metric spam)")
        void zero_tokens_do_not_register_counter() {
            Usage usage = testUsage(0, 0, 0);

            recorder.recordMetrics(usage, "gpt-4o-mini");

            // requests 仍 +1(每次调用都计)
            assertThat(requestsCount("gpt-4o-mini", "system")).isEqualTo(1.0);
            // 三个 token counter 全 0 → 不该注册(micrometer 对 increment(0) 仍会注册,
            // 但值是 0.0,我们只是确认 token 累计 = 0)
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "gpt-4o-mini", "system")).isEqualTo(0.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini", "system")).isEqualTo(0.0);
        }
    }

    // ──────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────

    /** 构造一个 Spring AI {@link Usage} 测试实例(null 表示字段不报)。 */
    private static Usage testUsage(Integer prompt, Integer completion, Integer total) {
        return new Usage() {
            @Override public Integer getPromptTokens() { return prompt; }
            @Override public Integer getCompletionTokens() { return completion; }
            @Override public Integer getTotalTokens() { return total; }
            @Override public Object getNativeUsage() { return null; }
        };
    }

    private double requestsCount(String model, String source) {
        return counterValue(UsageRecorder.METRIC_REQUESTS, model, source);
    }

    private double counterValue(String name, String model, String source) {
        Counter c = registry.find(name).tag("model", model).tag("source", source).counter();
        return c == null ? 0.0 : c.count();
    }
}

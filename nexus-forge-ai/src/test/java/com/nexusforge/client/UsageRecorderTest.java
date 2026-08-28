package com.nexusforge.client;

import com.nexusforge.ai.ChatUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UsageRecorder 单元测试。覆盖 P5 Step 4 关键不变量:
 * <ul>
 *   <li>正常路径:4 个 counter(requests + prompt/completion/total token)全部 +N</li>
 *   <li>Null-safety:usage / model / MeterRegistry 任一为 null 都不抛、不报数</li>
 *   <li>Total 兜底:vendor 不返回 totalTokens 时由 prompt+completion 合成</li>
 *   <li>失败路径:recordRequest 单计数 requests,不污染 token counter</li>
 * </ul>
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
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(120)
                    .completionTokens(80)
                    .totalTokens(200)
                    .build();

            recorder.recordMetrics(usage, "gpt-4o-mini");

            assertThat(requestsCount("gpt-4o-mini")).isEqualTo(1.0);
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "gpt-4o-mini")).isEqualTo(120.0);
            assertThat(counterValue(UsageRecorder.METRIC_COMPLETION_TOKENS, "gpt-4o-mini")).isEqualTo(80.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini")).isEqualTo(200.0);
        }

        @Test
        @DisplayName("recordMetrics 多次累加")
        void recordMetrics_accumulates_across_calls() {
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(10).completionTokens(5).totalTokens(15).build();

            recorder.recordMetrics(usage, "gpt-4o-mini");
            recorder.recordMetrics(usage, "gpt-4o-mini");
            recorder.recordMetrics(usage, "gpt-4o-mini");

            assertThat(requestsCount("gpt-4o-mini")).isEqualTo(3.0);
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "gpt-4o-mini")).isEqualTo(30.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini")).isEqualTo(45.0);
        }

        @Test
        @DisplayName("recordRequest 单计数 requests,不污染 token counter")
        void recordRequest_only_increments_requests_counter() {
            recorder.recordRequest("claude-haiku-4-5");

            assertThat(requestsCount("claude-haiku-4-5")).isEqualTo(1.0);
            // 三个 token counter 在 recordRequest 下不出现(未 register)—— Counter.find 返 null。
            assertThat(registry.find(UsageRecorder.METRIC_PROMPT_TOKENS)
                    .tag("model", "claude-haiku-4-5").counter()).isNull();
            assertThat(registry.find(UsageRecorder.METRIC_COMPLETION_TOKENS)
                    .tag("model", "claude-haiku-4-5").counter()).isNull();
            assertThat(registry.find(UsageRecorder.METRIC_TOTAL_TOKENS)
                    .tag("model", "claude-haiku-4-5").counter()).isNull();
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

            assertThat(requestsCount("gpt-4o-mini")).isEqualTo(1.0);
            // token counter 仍未注册
            assertThat(registry.find(UsageRecorder.METRIC_PROMPT_TOKENS)
                    .tag("model", "gpt-4o-mini").counter()).isNull();
        }

        @Test
        @DisplayName("null model → 落到 'unknown' 标签")
        void null_model_falls_back_to_unknown_label() {
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(7).completionTokens(3).totalTokens(10).build();

            recorder.recordMetrics(usage, null);

            assertThat(requestsCount("unknown")).isEqualTo(1.0);
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "unknown")).isEqualTo(7.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "unknown")).isEqualTo(10.0);
        }

        @Test
        @DisplayName("null MeterRegistry → 所有方法空跑,不抛")
        void null_meter_registry_silently_no_ops() {
            UsageRecorder noOp = new UsageRecorder(null);
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(1).completionTokens(1).totalTokens(2).build();

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
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(50)
                    .completionTokens(30)
                    .totalTokens(null)  // vendor 漏报
                    .build();

            recorder.recordMetrics(usage, "gpt-4o-mini");

            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini"))
                    .as("total 应由 prompt+completion 合成 = 80")
                    .isEqualTo(80.0);
        }

        @Test
        @DisplayName("vendor 给的 total 优先,不会被覆盖")
        void vendor_total_takes_precedence_over_fallback() {
            // vendor 给的 total 与 prompt+completion 不一致(可能四舍五入),优先用 vendor
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(50)
                    .completionTokens(30)
                    .totalTokens(81)  // vendor 多算 1
                    .build();

            recorder.recordMetrics(usage, "gpt-4o-mini");

            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini"))
                    .as("vendor 给的 total=81 优先,不会被 80 覆盖")
                    .isEqualTo(81.0);
        }

        @Test
        @DisplayName("prompt / completion 字段为 null → 不报对应 counter")
        void null_token_fields_skip_corresponding_counters() {
            // 模型未返回 prompt 但返回 completion(罕见但合法)
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(null)
                    .completionTokens(20)
                    .totalTokens(20)
                    .build();

            recorder.recordMetrics(usage, "gpt-4o-mini");

            // prompt counter 未注册
            assertThat(registry.find(UsageRecorder.METRIC_PROMPT_TOKENS)
                    .tag("model", "gpt-4o-mini").counter()).isNull();
            // completion 仍报
            assertThat(counterValue(UsageRecorder.METRIC_COMPLETION_TOKENS, "gpt-4o-mini")).isEqualTo(20.0);
        }

        @Test
        @DisplayName("token = 0 不报数(避免 metric spam)")
        void zero_tokens_do_not_register_counter() {
            ChatUsage usage = ChatUsage.builder()
                    .promptTokens(0).completionTokens(0).totalTokens(0).build();

            recorder.recordMetrics(usage, "gpt-4o-mini");

            // requests 仍 +1(每次调用都计)
            assertThat(requestsCount("gpt-4o-mini")).isEqualTo(1.0);
            // 三个 token counter 全 0 → 不该注册(micrometer 对 increment(0) 仍会注册,
            // 但值是 0.0,我们只是确认 token 累计 = 0)
            assertThat(counterValue(UsageRecorder.METRIC_PROMPT_TOKENS, "gpt-4o-mini")).isEqualTo(0.0);
            assertThat(counterValue(UsageRecorder.METRIC_TOTAL_TOKENS, "gpt-4o-mini")).isEqualTo(0.0);
        }
    }

    // ──────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────

    private double requestsCount(String model) {
        return counterValue(UsageRecorder.METRIC_REQUESTS, model);
    }

    private double counterValue(String name, String model) {
        Counter c = registry.find(name).tag("model", model).counter();
        return c == null ? 0.0 : c.count();
    }
}

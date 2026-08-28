package com.nexusforge.client;

import com.nexusforge.ai.ChatUsage;
import com.nexusforge.ai.service.PreferenceResolver.KeySource;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * P5 Step 4 — LLM 调用出口的 Micrometer 埋点门面。
 *
 * <p><b>职责单一</b>:只负责把每次 LLM 调用的 token 消耗 / 请求次数转成 Micrometer 指标。
 * <b>不</b>负责 {@code ai_message_usage} 的持久化——P3 已经在
 * {@link com.nexusforge.service.ConversationService#sendMessage} 完成 save 动作,
 * 这里在 {@code usageRepo.save} 之后立刻
 * {@link #recordMetrics(ChatUsage, String) recordMetrics} 即可,避免重复写库、
 * 行为双发,保持 P3 行为不变。
 *
 * <p>指标清单(全部 counter,以 {@code model} 为标签):
 * <ul>
 *   <li>{@code ai.chat.requests} — 调用次数</li>
 *   <li>{@code ai.chat.tokens.prompt} — 累计 prompt token</li>
 *   <li>{@code ai.chat.tokens.completion} — 累计 completion token</li>
 *   <li>{@code ai.chat.tokens.total} — 累计 total token(在 prompt+completion 之外冗余记录,
 *       方便账单/告警直接读 total 不必两路加和)</li>
 * </ul>
 *
 * <p>所有方法都"幂等且吞异常"——埋点失败绝不影响主链路(LLM 响应已返回给前端)。
 *
 * <p>依赖降级:{@link MeterRegistry} 通过构造器注入。在测试场景下若未启用 actuator,
 * Spring 不会自动装配 {@code MeterRegistry};本 bean 上层 {@code @Component} 仍存在,
 * 调用方传 null 即可走"无埋点"路径。{@code AiAutoConfiguration} 没有强制 wiring,
 * 所以 {@link UsageRecorder} 用宽松的字段持有(MeterRegistry 单独字段、不强制构造器非空)。
 */
@Slf4j
@Component
public class UsageRecorder {

    /** 4 个 counter 的 metric name,集中常量避免散落 typo */
    public static final String METRIC_REQUESTS = "ai.chat.requests";
    public static final String METRIC_PROMPT_TOKENS = "ai.chat.tokens.prompt";
    public static final String METRIC_COMPLETION_TOKENS = "ai.chat.tokens.completion";
    public static final String METRIC_TOTAL_TOKENS = "ai.chat.tokens.total";

    /** "unknown" — model 为 null / 缺失时落到该标签,避免空字符串导致 PromQL 过滤异常 */
    private static final String LABEL_UNKNOWN = "unknown";

    /** 三态 key 源 → metric tag,便于平台用量/私 Key 用量分开统计 */
    private static final String TAG_SOURCE_SYSTEM = "system";
    private static final String TAG_SOURCE_OVERRIDE = "override_system";
    private static final String TAG_SOURCE_PRIVATE = "private";

    /**
     * Micrometer 入口。可为 null(未启用 actuator / 单元测试 mock),此时所有方法空跑。
     */
    private final MeterRegistry meterRegistry;

    /**
     * 完整 ctor。Spring 容器走这条路径,Boot 4.1 在启用了 starter-actuator 后会自动
     * 注入 {@code SimpleMeterRegistry} / PrometheusMeterRegistry 等实现。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public UsageRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 单元测试 / 早期启动场景用:无 Micrometer 注入。{@link #recordMetrics} / {@link #recordRequest}
     * 在这种状态下会静默跳过。
     */
    public UsageRecorder() {
        this(null);
    }

    /**
     * 把一次 LLM 调用的 token 消耗上报为 4 个 counter(请求数 + 3 种 token)。
     * 任何入参 null 都安全跳过——不抛、不打 error。
     *
     * <p>P7 扩展:多了一个 {@code source} 标签,值 {@code system / override_system / private},
     * 便于 Grafana 等监控区分"平台承担"与"用户自付"的用量。
     *
     * @param usage     LLM 返回的用量,可为 null
     * @param model     具体模型名
     * @param keySource 三态 key 源(可传 null,默认 system)
     */
    public void recordMetrics(ChatUsage usage, String model, KeySource keySource) {
        if (meterRegistry == null) {
            return;
        }
        String labelModel = model == null ? LABEL_UNKNOWN : model;
        String labelSource = sourceTag(keySource);
        // 1. 请求数 +1
        meterRegistry.counter(METRIC_REQUESTS, "model", labelModel, "source", labelSource).increment();
        if (usage == null) {
            return;
        }
        // 2. prompt token
        Integer prompt = usage.getPromptTokens();
        if (prompt != null && prompt > 0) {
            meterRegistry.counter(METRIC_PROMPT_TOKENS, "model", labelModel, "source", labelSource).increment(prompt);
        }
        // 3. completion token
        Integer completion = usage.getCompletionTokens();
        if (completion != null && completion > 0) {
            meterRegistry.counter(METRIC_COMPLETION_TOKENS, "model", labelModel, "source", labelSource).increment(completion);
        }
        // 4. total token
        Integer total = usage.getTotalTokens();
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        if (total != null && total > 0) {
            meterRegistry.counter(METRIC_TOTAL_TOKENS, "model", labelModel, "source", labelSource).increment(total);
        }
    }

    /**
     * 兼容旧调用,默认 {@link KeySource#SYSTEM}。
     */
    public void recordMetrics(ChatUsage usage, String model) {
        recordMetrics(usage, model, KeySource.SYSTEM);
    }

    /**
     * 一次"进入 sendMessage 但 LLM 调用失败"的请求计数。
     * 失败路径不记录 token(无 usage),但调用次数必须 +1。
     */
    public void recordRequest(String model) {
        recordRequest(model, KeySource.SYSTEM);
    }

    /**
     * P7 个性化版本,失败路径也带 source 标签。
     */
    public void recordRequest(String model, KeySource keySource) {
        if (meterRegistry == null) {
            return;
        }
        String labelModel = model == null ? LABEL_UNKNOWN : model;
        String labelSource = sourceTag(keySource);
        meterRegistry.counter(METRIC_REQUESTS, "model", labelModel, "source", labelSource).increment();
    }

    private static String sourceTag(KeySource keySource) {
        if (keySource == null) return TAG_SOURCE_SYSTEM;
        return switch (keySource) {
            case SYSTEM -> TAG_SOURCE_SYSTEM;
            case USER_OVERRIDE_SYSTEM_KEY -> TAG_SOURCE_OVERRIDE;
            case USER_PRIVATE_KEY -> TAG_SOURCE_PRIVATE;
        };
    }
}

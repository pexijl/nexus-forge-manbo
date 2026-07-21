package com.nexusforge.client;

import com.nexusforge.ai.ChatUsage;
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
     * @param usage  LLM 返回的用量,可为 null(vendor 不返回 usage / 流式还未收尾)
     * @param model  具体模型名(如 {@code gpt-4o-mini}),可为 null(落到 "unknown" 标签)
     */
    public void recordMetrics(ChatUsage usage, String model) {
        if (meterRegistry == null) {
            return;
        }
        String labelModel = model == null ? LABEL_UNKNOWN : model;
        // 1. 请求数 +1(usage 缺失也照样计,保证"调用次数"是真实请求量,与是否计费无关)
        meterRegistry.counter(METRIC_REQUESTS, "model", labelModel).increment();
        if (usage == null) {
            return;
        }
        // 2. prompt token
        Integer prompt = usage.getPromptTokens();
        if (prompt != null && prompt > 0) {
            meterRegistry.counter(METRIC_PROMPT_TOKENS, "model", labelModel).increment(prompt);
        }
        // 3. completion token
        Integer completion = usage.getCompletionTokens();
        if (completion != null && completion > 0) {
            meterRegistry.counter(METRIC_COMPLETION_TOKENS, "model", labelModel).increment(completion);
        }
        // 4. total token — 优先用 vendor 给的 total;若 total 缺失用 prompt+completion 兜底,
        // 保证账单/告警面板拿到的 total 永远有值。
        Integer total = usage.getTotalTokens();
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        if (total != null && total > 0) {
            meterRegistry.counter(METRIC_TOTAL_TOKENS, "model", labelModel).increment(total);
        }
    }

    /**
     * 一次"进入 sendMessage 但 LLM 调用失败"的请求计数。失败路径不记录 token(无 usage),
     * 但调用次数必须 +1,否则告警面板会丢失失败请求统计。
     *
     * <p>典型调用点:ConversationService.sendMessage 在 {@code lhmClient.call} 抛异常时
     * 走 finally 块调此方法。
     */
    public void recordRequest(String model) {
        if (meterRegistry == null) {
            return;
        }
        String labelModel = model == null ? LABEL_UNKNOWN : model;
        meterRegistry.counter(METRIC_REQUESTS, "model", labelModel).increment();
    }
}

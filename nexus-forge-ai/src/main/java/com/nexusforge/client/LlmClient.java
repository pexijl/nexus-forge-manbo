package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.config.AiProperties;
import com.nexusforge.error.StreamTimeoutException;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.router.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 门面。其他模块(如未来可能的 nexus-forge-visual 摘要生成)只依赖此门面,
 * 不直接依赖 ChatModel SPI。
 *
 * <p>P4 Step 8 增强:
 * <ul>
 *   <li>由 {@link ChatModelRouter#resolveWithFallback(ChatRequest)} 解析首选 + 降级链,
 *       本类按链依次尝试,首选失败({@link ResultCode#LLM_PROVIDER_ERROR} /
 *       {@link ResultCode#LLM_UPSTREAM_TIMEOUT},或首选 vendor 当前处于熔断态)时自动跳到下一跳。</li>
 *   <li>所有跳都用尽时抛 {@link ResultCode#LLM_ALL_VENDORS_FAILED},cause 携带最后一跳的
 *       {@link LlmException}。</li>
 *   <li>{@code props.fallbackChain} 为空时,行为完全等同 P2-P3(单次调用 + 现有错误码)。</li>
 * </ul>
 */
@Slf4j
/**
 * P4 Step 11:bean 注册改由 {@link com.nexusforge.bootstrap.AiAutoConfiguration}
 * 通过 {@code @Bean} 注入。本类不再 {@code @Component},原因同
 * {@link ChatModelRouter} —— 显式 ctor + 多参依赖,Spring 类扫描默认会找
 * {@code <init>()} 失败。
 */
public class LlmClient {

    private final ChatModelRouter router;
    private final AiProperties props;
    /**
     * P4 Step 11:流式响应在出口处套一层 {@link FunctionCallAggregator},
     * 把 OpenAI 风格的 {@code delta.tool_calls[]} 增量帧聚合为终止帧的完整 {@code toolCalls} 列表。
     * 仅 {@link #stream(ChatRequest)} 路径生效;同步 {@link #call(ChatRequest)} 由
     * {@link com.nexusforge.provider.openai.OpenAiJsonMapper#fromOpenAi} 直接从
     * {@code message.tool_calls} 字段提取,不走聚合。
     */
    private final FunctionCallAggregator functionCallAggregator;

    /**
     * Spring 容器使用的完整 ctor。{@link FunctionCallAggregator} 必须是 Spring bean,
     * 因为后续 P4 Step 12+ 可能给它注入 {@code ObjectMapper} 或配置项。
     */
    public LlmClient(ChatModelRouter router, AiProperties props, FunctionCallAggregator functionCallAggregator) {
        this.router = router;
        this.props = props;
        this.functionCallAggregator = functionCallAggregator;
    }

    /**
     * 单元测试用 2-arg ctor:用 {@code new FunctionCallAggregator()} 兜底。
     * 聚合器无状态、无 Spring 注入需求(只持有 {@code ObjectMapper}),测试场景直接 {@code new} 即可。
     */
    public LlmClient(ChatModelRouter router, AiProperties props) {
        this(router, props, new FunctionCallAggregator());
    }

    /**
     * 同步调用门面。
     *
     * <p>P4 起按降级链依次尝试每一跳:
     * <ul>
     *   <li>首选 vendor 命中熔断(由 {@code router.isPrimaryVendorOpen} 判断) → 跳下一跳</li>
     *   <li>首选返回 {@link LlmException} 且 {@link ChatModelRouter#isFallbackTriggering}
     *       判断为"应当降级"的错误码 → 跳下一跳</li>
     *   <li>其它错误(限流 / 配额 / 参数错 / 配置缺失)直接抛出,不降级 —— 改 vendor 也救不了</li>
     *   <li>链耗尽 → 抛 {@link LlmException#LLM_ALL_VENDORS_FAILED}</li>
     * </ul>
     */
    public ChatResponse call(ChatRequest request) {
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(request);
        LlmException lastError = null;
        for (ChatModelRouter.Resolved r : chain) {
            // 首选 vendor 已熔断 → 不浪费一次 HTTP 调用,直接跳下一跳
            if (chain.primaryVendor().equals(r.vendor()) && router.isPrimaryVendorOpen(r)) {
                log.warn("[LLM fallback] 首选 vendor={} 已熔断,跳下一跳", r.vendor());
                continue;
            }
            try {
                long t = System.currentTimeMillis();
                ChatResponse resp = r.model().call(withModel(request, r.modelName()));
                log.info("[LLM] vendor={} model={} latency={}ms tokens={}/{}",
                        r.vendor(), r.modelName(),
                        System.currentTimeMillis() - t,
                        resp.getUsage() == null ? 0 : resp.getUsage().getPromptTokens(),
                        resp.getUsage() == null ? 0 : resp.getUsage().getCompletionTokens());
                if (!r.vendor().equals(chain.primaryVendor())) {
                    log.info("[LLM fallback] 已降级到 vendor={} model={}", r.vendor(), r.modelName());
                }
                return resp;
            } catch (LlmException ex) {
                lastError = ex;
                if (!ChatModelRouter.isFallbackTriggering(ex, r.vendor())) {
                    throw ex;  // 不可降级的错误,直接抛
                }
                log.warn("[LLM fallback] vendor={} 失败 code={} detail={},尝试下一跳",
                        r.vendor(), ex.getCode(), ex.getMessage());
            }
        }
        // 整条链用尽
        LlmException ex = new LlmException(ResultCode.LLM_ALL_VENDORS_FAILED,
                "降级链已耗尽,primary=" + chain.primaryVendor());
        if (lastError != null) ex.initCause(lastError);
        throw ex;
    }

    /**
     * 流式调用门面。
     *
     * <p>P2 增强:
     * <ul>
     *   <li>由 {@link com.nexusforge.ai.model.ChatModel#stream} 设置 {@code request.stream=true}
     *       并下发;调用方无需预填。</li>
     *   <li>在门面层套一层 {@link Flux#timeout(Duration)},作为整流总时长的兜底;
     *       超时映射成 {@link StreamTimeoutException} → {@code LLM_UPSTREAM_TIMEOUT}。</li>
     *   <li>{@code doFinally} 信号日志(vendor / model / signal / token 总数),
     *       用于可观测性与排障。</li>
     *   <li>现有 {@link LlmException} 透传,不做包装。</li>
     * </ul>
     *
     * <p>P4 增强:按降级链展开;流式只对"首 chunk 之前"发生的错误降级;
     * 一旦开始推数据,SSE 已经断开重连代价高,直接让 {@code Flux.error} 透传到上层,
     * 不切换 vendor(避免内容重复)。
     *
     * <p>取消语义:订阅方 dispose → Reactor 上游取消 → WebClient 关闭连接,无泄漏。
     * 计时器在 dispose 后自动清理。
     */
    public Flux<ChatChunk> stream(ChatRequest request) {
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(request);
        Duration timeout = props.getRequestTimeout();

        // 降级链只考虑"还没开始推数据"的失败;首选 vendor 熔断就直接跳下一跳
        ChatModelRouter.Resolved resolved = pickFirstUsableHop(chain);
        if (resolved == null) {
            LlmException ex = new LlmException(ResultCode.LLM_ALL_VENDORS_FAILED,
                    "降级链全部熔断或未配置");
            return Flux.error(ex);
        }
        if (!resolved.vendor().equals(chain.primaryVendor())) {
            log.info("[LLM stream fallback] 已降级到 vendor={} model={}",
                    resolved.vendor(), resolved.modelName());
        }
        ChatRequest req = withModel(request, resolved.modelName());
        req.setStream(Boolean.TRUE);

        // 累计 usage 的小累加器:流结束(complete / cancel / error)时打印总账
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();

        return resolved.model().stream(req)
                .timeout(timeout)
                .doOnNext(chunk -> {
                    if (chunk.getUsage() != null) {
                        promptTokens.addAndGet(chunk.getUsage().getPromptTokens() == null
                                ? 0L : chunk.getUsage().getPromptTokens());
                        completionTokens.addAndGet(chunk.getUsage().getCompletionTokens() == null
                                ? 0L : chunk.getUsage().getCompletionTokens());
                    }
                })
                .doFinally(sig -> log.info(
                        "[LLM stream] vendor={} model={} signal={} tokens={}/{}",
                        resolved.vendor(), resolved.modelName(), sig,
                        promptTokens.get(), completionTokens.get()))
                // P4 Step 11:在出口聚合 delta.tool_calls 增量 → 终止帧的 toolCalls 列表。
                // 放在 doFinally 之后,这样 use 量统计仍然反映 provider 真实帧数;
                // 放在 onErrorMap 之前,这样上游 Flux.error 透传(无终止帧时不补帧)。
                .transform(functionCallAggregator::aggregate)
                .onErrorMap(err -> mapStreamError(err, timeout));
    }

    /**
     * 从链头开始挑第一个未熔断的 hop。若全部熔断返回 null。
     */
    private ChatModelRouter.Resolved pickFirstUsableHop(ChatModelRouter.FallbackChain chain) {
        for (ChatModelRouter.Resolved r : chain) {
            if (!router.isPrimaryVendorOpen(r)) {
                return r;
            }
            log.warn("[LLM fallback] vendor={} 已熔断,跳过", r.vendor());
        }
        return null;
    }

    /**
     * 流错误归一化:
     * <ul>
     *   <li>{@link LlmException} 直接抛(已带业务 code)</li>
     *   <li>{@link TimeoutException} → {@link StreamTimeoutException}</li>
     *   <li>其它 → 透传,让 GlobalExceptionHandler 兜底为 {@code LLM_PROVIDER_ERROR}</li>
     * </ul>
     */
    private static Throwable mapStreamError(Throwable err, Duration timeout) {
        if (err instanceof LlmException) {
            return err;
        }
        if (err instanceof TimeoutException) {
            return new StreamTimeoutException("流式调用超过 " + timeout.toMillis() + "ms");
        }
        return err;
    }

    /**
     * 把 ChatRequest.model 替换为 router 解析后的具体 model(去掉 vendor 前缀)
     */
    private ChatRequest withModel(ChatRequest src, String modelName) {
        return ChatRequest.builder()
                .model(modelName)
                .messages(src.getMessages())
                .temperature(src.getTemperature())
                .maxTokens(src.getMaxTokens())
                .stream(src.getStream())
                .options(src.getOptions())
                .tools(src.getTools())
                .build();
    }
}
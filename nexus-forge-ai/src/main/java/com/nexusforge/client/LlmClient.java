package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.config.AiProperties;
import com.nexusforge.error.StreamTimeoutException;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ChatModelRouter router;
    private final AiProperties props;

    public ChatResponse call(ChatRequest request) {
        ChatModelRouter.Resolved r = router.resolve(request);
        long t = System.currentTimeMillis();
        ChatResponse resp = r.model().call(withModel(request, r.modelName()));
        log.info("[LLM] vendor={} model={} latency={}ms tokens={}/{}",
                r.vendor(), r.modelName(),
                System.currentTimeMillis() - t,
                resp.getUsage() == null ? 0 : resp.getUsage().getPromptTokens(),
                resp.getUsage() == null ? 0 : resp.getUsage().getCompletionTokens());
        return resp;
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
     * <p>取消语义:订阅方 dispose → Reactor 上游取消 → WebClient 关闭连接,无泄漏。
     * 计时器在 dispose 后自动清理。
     */
    public Flux<ChatChunk> stream(ChatRequest request) {
        ChatModelRouter.Resolved r = router.resolve(request);
        ChatRequest req = withModel(request, r.modelName());
        req.setStream(Boolean.TRUE);
        Duration timeout = props.getRequestTimeout();

        // 累计 usage 的小累加器:流结束(complete / cancel / error)时打印总账
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();

        return r.model().stream(req)
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
                        r.vendor(), r.modelName(), sig,
                        promptTokens.get(), completionTokens.get()))
                .onErrorMap(err -> mapStreamError(err, timeout));
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
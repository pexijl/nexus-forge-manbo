package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolCall;
import com.nexusforge.config.AiProperties;
import com.nexusforge.error.StreamTimeoutException;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatModel;
import com.nexusforge.router.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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
     * P7 个性化路径:用户私 Key 模式调用。
     *
     * <p>与 {@link #call(ChatRequest)} 区别:
     * <ul>
     *   <li>不走降级链(用户的 Key 失败就该报给他,不应悄悄切到别人的 Key)</li>
     *   <li>不参与熔断计数(熔断是平台级 SLO,不应被单用户的私 Key 故障污染)</li>
     *   <li>错误归一化(超时 / 网络错 → {@link ResultCode#LLM_UPSTREAM_TIMEOUT / LLM_PROVIDER_ERROR})保持一致</li>
     * </ul>
     */
    public ChatResponse call(ChatRequest request, ChatModel privateKeyModel) {
        long t = System.currentTimeMillis();
        ChatResponse resp;
        try {
            resp = privateKeyModel.call(withModel(request, /* modelName */ privateKeyDefaultModel(privateKeyModel)));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(ResultCode.LLM_PROVIDER_ERROR, "私 Key 调用失败: " + e.getMessage());
        }
        log.info("[LLM private] vendor={} model={} latency={}ms tokens={}/{}",
                privateKeyModel.name(),
                resp.getModel(),
                System.currentTimeMillis() - t,
                resp.getUsage() == null ? 0 : resp.getUsage().getPromptTokens(),
                resp.getUsage() == null ? 0 : resp.getUsage().getCompletionTokens());
        return resp;
    }

    /**
     * P7 系统 Key 路径:由 PreferenceResolver 解析出 vendor/model 后透传。
     *
     * <p>与 {@link #call(ChatRequest)} 区别:
     * <ul>
     *   <li>绕开 {@link ChatModelRouter} 的 yaml-default 兜底(那是 vendor 级 fallback,
     *       不是请求级偏好),让 ai_global_default.model 或 user_ai_preference.model
     *       成为唯一权威。</li>
     *   <li>仍然走降级链(系统 Key 失败时,允许切到下一个 vendor)。</li>
     *   <li>不接私 Key —— 私 Key 路径请用 {@link #call(ChatRequest, ChatModel)}。</li>
     * </ul>
     *
     * @param request    原始 ChatRequest(model 字段会被本方法覆盖成 {@code model})
     * @param vendor     resolved.vendor,如 "qwen"
     * @param model      resolved.model,如 "qwen-turbo";非空时优先于 yaml default
     */
    public ChatResponse call(ChatRequest request, String vendor, String model) {
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(
                withModel(request, vendor + ":" + (model == null ? "" : model)));
        LlmException lastError = null;
        for (ChatModelRouter.Resolved r : chain) {
            if (chain.primaryVendor().equals(r.vendor()) && router.isPrimaryVendorOpen(r)) {
                log.warn("[LLM fallback] 首选 vendor={} 已熔断,跳下一跳", r.vendor());
                continue;
            }
            try {
                long t = System.currentTimeMillis();
                // buildPrefRequest:把 model 写进 req.options["model"] 让 mapper 优先读;
                // 同时保留 req.model(给 router 路由用)。model == null 时不让它进 options,
                // 让 mapper fallback 到 yaml default(此时 PreferenceResolver 不应返回 null)
                ChatResponse resp = r.model().call(buildPrefRequest(request, model));
                log.info("[LLM pref] vendor={} model={} latency={}ms tokens={}/{}",
                        r.vendor(), model,
                        System.currentTimeMillis() - t,
                        resp.getUsage() == null ? 0 : resp.getUsage().getPromptTokens(),
                        resp.getUsage() == null ? 0 : resp.getUsage().getCompletionTokens());
                if (!r.vendor().equals(chain.primaryVendor())) {
                    log.info("[LLM fallback] 已降级到 vendor={} model={}", r.vendor(), model);
                }
                return resp;
            } catch (LlmException ex) {
                lastError = ex;
                if (!ChatModelRouter.isFallbackTriggering(ex, r.vendor())) {
                    throw ex;
                }
                log.warn("[LLM fallback] vendor={} 失败 code={} detail={},尝试下一跳",
                        r.vendor(), ex.getCode(), ex.getMessage());
            }
        }
        LlmException ex = new LlmException(ResultCode.LLM_ALL_VENDORS_FAILED,
                "降级链已耗尽,primary=" + chain.primaryVendor());
        if (lastError != null) ex.initCause(lastError);
        throw ex;
    }

    /**
     * 构造 P7 系统 Key 路径下的 ChatRequest 副本:把 {@code model} 塞进 {@code options["model"]},
     * 让 {@code OpenAiJsonMapper.toOpenAi} 在解析时优先用这个值(yaml 兜底之后)。
     * 其它字段(messages / temperature / maxTokens / stream / tools)与 src 一致。
     */
    private ChatRequest buildPrefRequest(ChatRequest src, String model) {
        Map<String, Object> opts = src.getOptions() == null
                ? new HashMap<>()
                : new HashMap<>(src.getOptions());
        if (model != null && !model.isBlank()) {
            opts.put("model", model);
        }
        return ChatRequest.builder()
                .model(src.getModel())
                .messages(src.getMessages())
                .temperature(src.getTemperature())
                .maxTokens(src.getMaxTokens())
                .stream(src.getStream())
                .options(opts)
                .tools(src.getTools())
                .build();
    }

    /** 从 ChatModel 名字推 modelName(避免 ChatRequest 重复声明) */
    private static String privateKeyDefaultModel(ChatModel m) {
        // ChatModel SPI 的 vendor 名已知;具体 model 由 ChatRequest 自身的 model 字段决定
        // (ChatModel.call 内部会用 cfg.defaultModel 兜底)。这里返回 null 表示"沿用 request.model"。
        return null;
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
     * P7 个性化路径:用户私 Key 模式流式调用。
     *
     * <p>不走降级链、不参与熔断计数;保留 timeout + 用量累加日志。
     */
    public Flux<ChatChunk> stream(ChatRequest request, ChatModel privateKeyModel) {
        Duration timeout = props.getRequestTimeout();
        ChatRequest req = withModel(request, null);
        req.setStream(Boolean.TRUE);

        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();

        return privateKeyModel.stream(req)
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
                        "[LLM stream private] vendor={} model={} signal={} tokens={}/{}",
                        privateKeyModel.name(), req.getModel(), sig,
                        promptTokens.get(), completionTokens.get()))
                .transform(functionCallAggregator::aggregate)
                .onErrorMap(err -> mapStreamError(err, timeout));
    }

    /**
     * P7 系统 Key 路径流式:由 PreferenceResolver 解析 vendor/model。
     *
     * <p>走降级链(系统 Key 失败时切下一个 vendor),但 model 用 PreferenceResolver
     * 透传的 {@code model}(来自 ai_global_default 或 user_ai_preference),不会被
     * yaml 的 default-model 覆盖。
     */
    public Flux<ChatChunk> stream(ChatRequest request, String vendor, String model) {
        Duration timeout = props.getRequestTimeout();
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(
                withModel(request, vendor + ":" + (model == null ? "" : model)));

        ChatModelRouter.Resolved resolved = pickFirstUsableHop(chain);
        if (resolved == null) {
            LlmException ex = new LlmException(ResultCode.LLM_ALL_VENDORS_FAILED,
                    "降级链全部熔断或未配置");
            return Flux.error(ex);
        }
        if (!resolved.vendor().equals(chain.primaryVendor())) {
            log.info("[LLM stream fallback] 已降级到 vendor={} model={}",
                    resolved.vendor(), model);
        }
        ChatRequest req = buildPrefRequest(request, model);
        req.setStream(Boolean.TRUE);

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
                        "[LLM stream pref] vendor={} model={} signal={} tokens={}/{}",
                        resolved.vendor(), model, sig,
                        promptTokens.get(), completionTokens.get()))
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

    // ──────────────────────────────────────────────
    // P4 Step 12:Function Calling 闭环
    // ──────────────────────────────────────────────

    /**
     * 系统 Key 路径:走降级链,命中 {@code finishReason="tool_calls"} 时自动执行工具、
     * 回灌 TOOL 消息、再调一次,直到返回 {@code stop} 或达到 {@code maxTurns} 上限。
     *
     * <p>当前请求 {@code finishReason != "tool_calls"} 或 {@code toolCalls} 为空时,
     * 行为完全等同 {@link #call(ChatRequest)} —— 不会修改原始 {@code request}。
     */
    public ChatResponse callWithToolLoop(ChatRequest req, ToolRegistry registry, int maxTurns) {
        ChatResponse resp = call(req);
        return runToolLoop(req, resp, this::call, registry, maxTurns);
    }

    /**
     * 私 Key 路径:与 {@link #callWithToolLoop(ChatRequest, ToolRegistry, int)} 行为一致,
     * 但每次重调走 {@link #call(ChatRequest, ChatModel)} —— 不走降级链、不污染熔断计数。
     */
    public ChatResponse callWithToolLoop(ChatRequest req, ChatModel privateKeyModel,
                                         ToolRegistry registry, int maxTurns) {
        ChatResponse resp = call(req, privateKeyModel);
        return runToolLoop(req, resp, r -> call(r, privateKeyModel), registry, maxTurns);
    }

    /**
     * 工具调用循环主体。共享给系统路径({@code this::call})和私 Key 路径
     * ({@code r -> call(r, privateKeyModel)})。
     *
     * <p>循环条件:尚未达到 {@code maxTurns} + 当前响应 {@code finishReason="tool_calls"}
     * 且 {@code toolCalls} 非空。每轮:
     * <ol>
     *   <li>把当前 assistant 消息(含 {@code toolCalls})追加到消息尾部</li>
     *   <li>按 {@code toolCall.id} 一一执行工具,执行结果回灌为 {@code role=TOOL} 消息</li>
     *   <li>用更新后的消息集构造新 ChatRequest,经 {@code reCall} 再调一次 LLM</li>
     * </ol>
     *
     * <p>边界:
     * <ul>
     *   <li>工具名未注册或抛异常 → 注入 {@code ToolResult.error(...)} 让模型看到失败信息,而不是默默结束</li>
     *   <li>达到 {@code maxTurns} 仍返回 {@code tool_calls} → 返回最后一次响应(可能 {@code content=null})</li>
     *   <li>循环过程中构造的新 ChatRequest 是副本,原始 {@code req} 不被 mutate</li>
     * </ul>
     */
    private ChatResponse runToolLoop(ChatRequest req, ChatResponse resp,
                                     Function<ChatRequest, ChatResponse> reCall,
                                     ToolRegistry registry, int maxTurns) {
        int turn = 1;
        while (turn < maxTurns
                && "tool_calls".equals(resp.getFinishReason())
                && resp.getToolCalls() != null
                && !resp.getToolCalls().isEmpty()) {
            log.info("[ToolLoop] turn={}/{} toolCalls={}", turn, maxTurns, resp.getToolCalls().size());

            List<ChatMessage> msgs = new ArrayList<>(req.getMessages());
            // 1. 把当前 assistant 消息(带 toolCalls)回灌
            msgs.add(ChatMessage.builder()
                    .role(Role.ASSISTANT)
                    .content(resp.getContent())
                    .toolCalls(resp.getToolCalls())
                    .build());
            // 2. 一一执行工具,把结果回灌为 TOOL 消息
            for (ToolCall tc : resp.getToolCalls()) {
                ToolResult result;
                try {
                    ToolExecutor exec = registry.lookup(tc.getName());
                    if (exec == null) {
                        log.warn("[ToolLoop] tool={} 未注册,注入错误结果", tc.getName());
                        result = ToolResult.error("Unknown tool: " + tc.getName());
                    } else {
                        result = exec.execute(tc.getArguments());
                    }
                } catch (Exception e) {
                    log.warn("[ToolLoop] tool={} 抛出异常: {}", tc.getName(), e.toString());
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    result = ToolResult.error(msg);
                }
                log.info("[ToolLoop] turn={} tool={} id={} isError={} contentLen={}",
                        turn, tc.getName(), tc.getId(), result.isError(), result.content().length());
                msgs.add(ChatMessage.builder()
                        .role(Role.TOOL)
                        .name(tc.getId())
                        .content(result.content())
                        .build());
            }

            // 3. 用更新后的消息集构造新 ChatRequest(保留 model / tools / options 等)
            req = ChatRequest.builder()
                    .model(req.getModel())
                    .messages(msgs)
                    .temperature(req.getTemperature())
                    .maxTokens(req.getMaxTokens())
                    .stream(req.getStream())
                    .options(req.getOptions())
                    .tools(req.getTools())
                    .build();
            // 4. 再调一次
            resp = reCall.apply(req);
            turn++;
        }
        if ("tool_calls".equals(resp.getFinishReason())) {
            log.warn("[ToolLoop] 已达 maxTurns={} 仍未终止(仍为 tool_calls),直接返回最后一次响应", maxTurns);
        }
        return resp;
    }
}
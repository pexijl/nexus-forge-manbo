package com.nexusforge.client;

import com.nexusforge.ai.entity.AiModelCatalog;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.error.StreamTimeoutException;
import com.nexusforge.exception.LlmException;
import com.nexusforge.router.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * spring-ai-full-migration Phase 3 — Spring AI 化 LLM 门面 + 真实 tool loop。
 *
 * <p>同步调用方法(2b 4 个重载)不变,只是内部把
 * {@code ChatModel.call(prompt)} 替换成 {@link #callWithToolLoop},这样所有路径
 * (系统 Key / 私 Key)都自动获得 tool loop 能力。
 *
 * <p>工具循环用 Spring AI 的 {@link DefaultToolCallingManager}:
 * <ol>
 *   <li>首次 {@code chatModel.call(prompt)} 拿到 {@link ChatResponse}</li>
 *   <li>若 response 含 tool calls(检测 {@code AssistantMessage.getToolCalls()})
 *       → 调 {@code toolCallingManager.executeToolCalls(prompt, response)} 拿到
 *       含 tool result 的新 conversation history</li>
 *   <li>用新 history 重构 {@code Prompt},再次 {@code chatModel.call(...)}</li>
 *   <li>循环直到响应不再有 tool calls,或达到
 *       {@link AiProperties#getMaxToolIterations()} 安全上限</li>
 * </ol>
 *
 * <p>tool callbacks 通过 {@link ToolCallbackProvider} 接口注入 — 任何
 * {@code @Component} 里的 {@code @Tool} 方法会被 Spring AI 扫到并自动生成
 * {@link ToolCallback}。具体注册在 {@code AiAutoConfiguration} 的
 * {@code toolCallbackProvider} bean。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ChatModelRouter router;
    private final AiProperties props;
    private final List<ToolCallbackProvider> toolCallbackProviders;
    private final ModelCatalogService modelCatalogService;
    private final com.nexusforge.ai.provider.SystemKeyChatModelFactory systemKeyFactory;

    /** 启动时把所有 provider 的 callbacks 平铺成 1 个 list;为空 = 关闭 tool loop。 */
    private final List<ToolCallback> toolCallbacks = initToolCallbacks();

    private final ToolCallingManager toolCallingManager = initToolCallingManager();

    private List<ToolCallback> initToolCallbacks() {
        if (toolCallbackProviders == null) return List.of();
        return toolCallbackProviders.stream()
                .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                .toList();
    }

    private ToolCallingManager initToolCallingManager() {
        return DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(toolCallbacks))
                .build();
    }

    // ─────────────────────── sync call ───────────────────────

    /**
     * 系统 Key 路径(无显式 vendor/model):由 router 解析首选 vendor,
     * 失败按 {@code spring.ai.fallback-chain} 顺序跳下一跳。
     */
    public ChatResponse call(Prompt prompt) {
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(null, null);
        // Phase 1 — catalog 校验:admin 可在 ai_model_catalog 紧急关停 model,
        // check 解析后的 primary(vendor + modelName)即可,
        // 失败直接抛(不走 fallback,用户明确知道请求的是这个 model)。
        assertModelAllowed(chain.primaryVendor(),
                chain.iterator().hasNext() ? chain.iterator().next().modelName() : null);
        return runChain(chain, prompt, "LLM", /* modelArg */ null, chain.primaryVendor());
    }

    /**
     * 系统 Key 路径(显式 vendor/model):PreferenceResolver 解析后透传。
     * model 优先于 yaml default-model。
     */
    public ChatResponse call(Prompt prompt, String vendor, String model) {
        // router 显式接 (vendor, model) 两个参数,不再把 vendor 拼到
        // prompt.getOptions().getModel() 里 —— Spring AI 透传该字段给上游
        // API,DeepSeek / OpenAI / Anthropic 不认 "vendor:model" 格式
        // (报 400 Bad Request)。
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(vendor, model);
        // Phase 1 — catalog 校验:用户显式请求的 (vendor, model) 必须存在于
        // catalog 且 enabled=true;不通过直接抛,不进 fallback chain。
        assertModelAllowed(vendor, model);
        return runChain(chain, prompt, "LLM pref", model, vendor);
    }

    /**
     * 私 Key 路径:用调用方传入的 {@link ChatModel}(由 {@code VendorChatModelFactory}
     * 按 sha256(apiKey) 缓存构造,典型实现是 OpenAiChatModel 子类实例)。
     * 不走降级链、不参与熔断计数。
     *
     * <p>Phase 1 — catalog 校验 <b>也走</b>:admin disable 是硬门禁,私 Key
     * 用户也不例外。原因:admin 关停通常是合规/事故场景(模型下架 / 数据泄露
     * 风险),放私 Key 等于"管理员关了一扇门,用户从后门进"。私 Key 用户的
     * 兜底是切别的 model(不是切私 Key 绕过 disable)。
     *
     * <p>Phase 3 — catalog 校验用 <b>实际 vendor</b>(从 {@code Resolved} 拿),
     * 旧实现硬写 {@code "openai"} 是 bug(用户用 deepseek 代理时,实际 vendor 是
     * deepseek 但 catalog 校验 openai)。新调用方请用
     * {@link #call(Prompt, ChatModel, String)}。
     */
    public ChatResponse call(Prompt prompt, ChatModel privateKeyModel) {
        // 保留旧行为:catalog 校验仍写 "openai"(同 Phase 1)。Phase 3 新调用方
        // 改用 call(Prompt, ChatModel, String actualVendor) 拿到正确 vendor。
        String model = privateKeyModel.getClass().getSimpleName();
        String vendor = "openai";
        String modelName = extractModelName(prompt);
        assertModelAllowed(vendor, modelName);
        long t0 = System.currentTimeMillis();
        try {
            ChatResponse resp = callWithToolLoop(prompt, privateKeyModel);
            log.info("[LLM private] model={} latency={}ms tokens={}/{}",
                    resp.getMetadata() != null ? resp.getMetadata().getModel() : "?",
                    System.currentTimeMillis() - t0,
                    extractPromptTokens(resp), extractCompletionTokens(resp));
            return resp;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(ResultCode.LLM_PROVIDER_ERROR,
                    "私 Key 调用失败: " + e.getMessage());
        }
    }

    /**
     * Phase 3 — 私 Key 路径(显式实际 vendor)。
     *
     * <p>跟 {@link #call(Prompt, ChatModel)} 的唯一差异:catalog 校验用
     * {@code actualVendor}(从 {@link com.nexusforge.ai.service.PreferenceResolver.Resolved}
     * 拿),而不是硬写 {@code "openai"}。新调用方必须用本重载 — 用户走
     * {@code user_ai_proxy}(deepseek / dashscope / glm 等任意 OpenAI 兼容 vendor)
     * 时,catalog 校验要按实际 vendor 查,否则 admin 禁用 deepseek 模型时
     * 私 Key 用户仍能调通,绕过了合规门禁。
     *
     * <p>调用前请确保 {@code prompt.getOptions().getModel()} 已由调用方设置
     * (用 {@link #withModelInOptions} 或 upstream controller)— 私 Key 路径
     * 不像系统路径那样从 yaml default-model 兜底。
     */
    public ChatResponse call(Prompt prompt, ChatModel privateKeyModel, String actualVendor) {
        String modelName = extractModelName(prompt);
        assertModelAllowed(actualVendor, modelName);
        long t0 = System.currentTimeMillis();
        try {
            ChatResponse resp = callWithToolLoop(prompt, privateKeyModel);
            log.info("[LLM private] vendor={} model={} latency={}ms tokens={}/{}",
                    actualVendor,
                    resp.getMetadata() != null ? resp.getMetadata().getModel() : "?",
                    System.currentTimeMillis() - t0,
                    extractPromptTokens(resp), extractCompletionTokens(resp));
            return resp;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(ResultCode.LLM_PROVIDER_ERROR,
                    "私 Key 调用失败: " + e.getMessage());
        }
    }

    // ─────────────────────── stream ───────────────────────

    /**
     * 系统 Key 流式:走降级链(只对"首 chunk 之前"发生的错误降级)。
     */
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(null, null);
        // Phase 1 — catalog 校验 primary
        assertModelAllowed(chain.primaryVendor(),
                chain.iterator().hasNext() ? chain.iterator().next().modelName() : null);
        ChatModelRouter.Resolved resolved = pickFirstUsableHop(chain);
        if (resolved == null) {
            return Flux.error(new LlmException(ResultCode.LLM_ALL_VENDORS_FAILED,
                    "降级链全部熔断或未配置"));
        }
        return wrapStream(systemKeyFactory.resolveOrCreate(resolved.vendor()).stream(prompt), "LLM stream", resolved);
    }

    /**
     * 系统 Key 流式(显式 vendor/model)。
     */
    public Flux<ChatResponse> stream(Prompt prompt, String vendor, String model) {
        ChatModelRouter.FallbackChain chain = router.resolveWithFallback(vendor, model);
        // Phase 1 — catalog 校验 explicit
        assertModelAllowed(vendor, model);
        ChatModelRouter.Resolved r = pickFirstUsableHop(chain);
        if (r == null) {
            return Flux.error(new LlmException(ResultCode.LLM_ALL_VENDORS_FAILED,
                    "降级链全部熔断或未配置"));
        }
        // 降级跳到非请求 vendor 时,rebuild options 适配新 ChatModel 期望的 ChatOptions 类型
        // —— Spring AI 2.0 各 vendor 的 ChatModel 对自己的 ChatOptions 子类硬转,
        // 跨 vendor 喂就 ClassCastException(见 withModelInOptions 的注释)。
        // model 字段用纯模型名(r.modelName() 或用户传的 model),不拼 vendor 前缀。
        if (!r.vendor().equalsIgnoreCase(vendor)) {
            log.info("[LLM stream pref] hop vendor={} 不同于 requested vendor={},rebuild options for new vendor",
                    r.vendor(), vendor);
        }
        String effectiveModel = r.vendor().equalsIgnoreCase(vendor) && model != null && !model.isBlank()
                ? model
                : r.modelName();
        Prompt callPrompt = withModelInOptions(prompt, r.vendor(), effectiveModel);
        return wrapStream(systemKeyFactory.resolveOrCreate(r.vendor()).stream(callPrompt), "LLM stream pref", r);
    }

    /**
     * 私 Key 流式:不走降级链、不参与熔断。
     *
     * <p>Phase 1 — catalog 校验也走(跟 sync 私 Key 路径一致)。
     */
    public Flux<ChatResponse> stream(Prompt prompt, ChatModel privateKeyModel) {
        String vendor = "openai";
        String modelName = extractModelName(prompt);
        assertModelAllowed(vendor, modelName);
        return wrapStream(privateKeyModel.stream(prompt), "LLM stream private",
                /* resolved */ null);
    }

    /**
     * Phase 3 — 私 Key 流式(显式实际 vendor)。
     *
     * <p>跟 {@link #stream(Prompt, ChatModel)} 的差异:catalog 校验用
     * {@code actualVendor}(从 {@code Resolved} 拿),而不是硬写 {@code "openai"}。
     * 新调用方(走 {@code user_ai_proxy} 多代理)必须用本重载。
     */
    public Flux<ChatResponse> stream(Prompt prompt, ChatModel privateKeyModel, String actualVendor) {
        String modelName = extractModelName(prompt);
        assertModelAllowed(actualVendor, modelName);
        return wrapStream(privateKeyModel.stream(prompt), "LLM stream private",
                /* resolved */ null);
    }

    // ─────────────────────── catalog 校验 ───────────────────────

    /**
     * Phase 1 — 检查 (vendor, model) 是否在 model catalog 中且 enabled。
     * 不通过抛 {@link LlmException}:
     * <ul>
     *   <li>不存在 → {@code LLM_MODEL_NOT_FOUND(3002)}</li>
     *   <li>存在但 enabled=false → {@code LLM_MODEL_DISABLED(3011)}</li>
     * </ul>
     *
     * <p>{@code vendor} / {@code model} 任一为 null/blank 时跳过校验(由调用方
     * 决定是否需要校验;router.resolveWithFallback 内部会做兜底)。
     */
    private void assertModelAllowed(String vendor, String model) {
        if (vendor == null || vendor.isBlank() || model == null || model.isBlank()) {
            return;
        }
        AiModelCatalog catalog = modelCatalogService.findByVendorModel(vendor, model);
        if (catalog == null) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND,
                    "vendor=" + vendor + " model=" + model + " 不在 model catalog 中");
        }
        if (Boolean.FALSE.equals(catalog.getEnabled())) {
            throw new LlmException(ResultCode.LLM_MODEL_DISABLED,
                    "vendor=" + vendor + " model=" + model + " 已被管理员禁用");
        }
    }

    /**
     * 从 Prompt 的 ChatOptions 拿 model 名(私 Key 路径用)。
     * OpenAI 协议:options.getModel() 存的是上游 API 期望的 model 名。
     */
    private static String extractModelName(Prompt prompt) {
        ChatOptions opts = prompt.getOptions();
        if (opts == null) return null;
        try {
            return opts.getModel();
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────── 内部 helper ───────────────────────

    /**
     * 跑降级链:链上每个 hop 依次尝试,命中熔断或抛触发降级的错误就跳下一跳,
     * 链耗尽抛 {@link ResultCode#LLM_ALL_VENDORS_FAILED}。
     *
     * <p>每个 hop 内部走 {@link #callWithToolLoop},自动获得 tool 调用回路。
     *
     * <p>per-hop rebuild:每个 hop 用自己 vendor 对应的 {@link ChatOptions} 子类
     * 重新包 prompt —— 跨 vendor 喂会 ClassCastException(见 {@link #withModelInOptions}
     * 注释)。对首选 hop 用用户传入的 {@code modelArg},对降级 hop 用 yaml 的
     * {@code providers[vendor].default-model}。
     */
    private ChatResponse runChain(ChatModelRouter.FallbackChain chain,
                                   Prompt originalPrompt,
                                   String logTag, String modelArg,
                                   String requestedVendor) {
        LlmException lastError = null;
        for (ChatModelRouter.Resolved r : chain) {
            if (router.isPrimaryVendorOpen(r)) {
                log.warn("[{} fallback] 首选 vendor={} 已熔断,跳下一跳", logTag, r.vendor());
                continue;
            }
            // Per-hop rebuild: 适配每个 hop 的 ChatModel 期望的 ChatOptions 类型
            String effectiveModel = (r.vendor().equalsIgnoreCase(requestedVendor) && modelArg != null)
                    ? modelArg : r.modelName();
            Prompt hopPrompt = withModelInOptions(originalPrompt, r.vendor(), effectiveModel);
            try {
                long t0 = System.currentTimeMillis();
                ChatResponse resp = callWithToolLoop(hopPrompt, systemKeyFactory.resolveOrCreate(r.vendor()));
                log.info("[{}] vendor={} model={} latency={}ms tokens={}/{}",
                        logTag, r.vendor(), r.modelName(),
                        System.currentTimeMillis() - t0,
                        extractPromptTokens(resp), extractCompletionTokens(resp));
                if (!r.vendor().equals(chain.primaryVendor())) {
                    log.info("[{} fallback] 已降级到 vendor={} model={}", logTag, r.vendor(), r.modelName());
                }
                return resp;
            } catch (LlmException ex) {
                lastError = ex;
                if (!ChatModelRouter.isFallbackTriggering(ex, r.vendor())) {
                    throw ex;
                }
                log.warn("[{} fallback] vendor={} 失败 code={} detail={},尝试下一跳",
                        logTag, r.vendor(), ex.getCode(), ex.getMessage());
            }
        }
        LlmException ex = new LlmException(ResultCode.LLM_ALL_VENDORS_FAILED,
                "降级链已耗尽,primary=" + chain.primaryVendor());
        if (lastError != null) ex.initCause(lastError);
        throw ex;
    }

    /**
     * 链上挑第一个未熔断的 hop。
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
     * 把流式输出包一层:超时、用量累加日志、错误归一。
     * resolved=null 时(私 Key 路径)日志显示 "(private)"。
     */
    private Flux<ChatResponse> wrapStream(Flux<ChatResponse> upstream,
                                          String logTag,
                                          ChatModelRouter.Resolved resolved) {
        Duration timeout = props.getRequestTimeout();
        AtomicLong promptTokens = new AtomicLong();
        AtomicLong completionTokens = new AtomicLong();
        String vendorLabel = resolved != null ? resolved.vendor() : "(private)";
        String modelLabel = resolved != null ? resolved.modelName() : "(private)";
        return upstream
                .timeout(timeout)
                .doOnNext(c -> {
                    Integer pt = extractPromptTokensObj(c);
                    Integer ct = extractCompletionTokensObj(c);
                    if (pt != null) promptTokens.addAndGet(pt.longValue());
                    if (ct != null) completionTokens.addAndGet(ct.longValue());
                })
                .doFinally(sig -> log.info("[{}] vendor={} model={} signal={} tokens={}/{}",
                        logTag, vendorLabel, modelLabel,
                        sig, promptTokens.get(), completionTokens.get()))
                .onErrorMap(err -> mapStreamError(err, timeout));
    }

    private static Throwable mapStreamError(Throwable err, Duration timeout) {
        if (err instanceof LlmException) return err;
        if (err instanceof TimeoutException) {
            return new StreamTimeoutException("流式调用超过 " + timeout.toMillis() + "ms");
        }
        return err;
    }

    /**
     * 把 model 名写进 prompt 的 ChatOptions(typed interface),并按 vendor 选
     * 对应 {@link ChatOptions} 子类。
     *
     * <p>关键约束:写入 {@code prompt.getOptions().getModel()} 的字段必须是
     * <b>纯模型名</b>(e.g. {@code "deepseek-v4-flash"}),不能带 {@code vendor:}
     * 前缀 —— Spring AI 透传该字段给上游 API,DeepSeek / OpenAI / Anthropic
     * 都不认带冒号的 model 名称,会直接 400 Bad Request。
     *
     * <p>Spring AI 2.0 用 {@code ChatOptions#mutate()} 替代了 1.x 的
     * {@code OpenAiChatOptions.fromOptions(...)} — 通用 builder 路径,
     * 各厂商 ChatOptions 实现都支持。{@code ChatOptions.mutate()} 返回
     * {@code ChatOptions.Builder<?>},可以直接 {@code .model(name).build()}。
     *
     * <p><b>Vendor 分发</b>:Spring AI 2.0 的 3 个 vendor ChatOptions 互不继承 —
     * {@code OpenAiChatOptions} / {@code AnthropicChatOptions} /
     * {@code OllamaChatOptions} 都是 {@code ToolCallingChatOptions +
     * StructuredOutputChatOptions} 的直接实现。Spring AI 官方 starter 提供
     * 的 {@code OpenAiChatModel.call(Prompt)} 内部会把
     * {@code prompt.getOptions()} 硬转 {@code OpenAiChatOptions} —
     * 之前一律用 {@code OpenAiChatOptions} 在 anthropic 路径下会 ClassCast;
     * deepseek 之前是 {@code DeepSeekChatModel} 硬转 {@code DeepSeekChatOptions},
     * 现在 deepseek 已统一走 OpenAI starter(见 build.gradle),所以也是
     * {@code OpenAiChatOptions}。本方法按 vendor 分发到正确的子类构造器,
     * 见 {@link #builderForVendor}。
     *
     * <p><b>Phase 3 起改为 {@code public}</b> — 私 Key 路径(走 {@code user_ai_proxy}
     * 时)由 controller / ConversationService 在调 {@code call(Prompt, ChatModel, vendor)}
     * 之前显式把 model 写入 prompt options;模型不再由 factory 内部硬写,需要外部调用
     * 本方法。
     */
    public static Prompt withModelInOptions(Prompt src, String vendor, String model) {
        if (model == null || model.isBlank()) return src;
        ChatOptions opts = src.getOptions();
        if (opts == null) {
            opts = builderForVendor(vendor).model(model).build();
        } else {
            opts = opts.mutate().model(model).build();
        }
        return new Prompt(src.getInstructions(), opts);
    }

    /**
     * 按 vendor 名路由到对应的 {@link ChatOptions} 构造器。
     *
     * <p>覆盖:
     * <ul>
     *   <li>{@code anthropic} → {@link AnthropicChatOptions}(独立 starter,
     *       走 Anthropic Messages 协议)</li>
     *   <li>其他(openai / deepseek / ollama / 中转站 / 国内 OpenAI 兼容)→
     *       {@link OpenAiChatOptions}(都走 OpenAI Chat Completions 协议家族,
     *       共用 OpenAiChatModel — DeepSeek 之前是 {@code DeepSeekChatOptions},
     *       现在已统一到 OPENAI 协议,见 build.gradle 注释)</li>
     * </ul>
     * 未识别的 vendor 名 / {@code null} / 空串都默认走 OpenAI 协议家族。
     *
     * <p>qwen / DashScope 不在本列表(走的是 Spring AI Alibaba 社区,不在 Spring AI 官方
     * starter 生态,暂不接入),如启用需先确认有对应 ChatModel bean 注入。
     */
    static ChatOptions.Builder<?> builderForVendor(String vendor) {
        String v = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "anthropic" -> AnthropicChatOptions.builder();
            default -> OpenAiChatOptions.builder();
        };
    }

    // ─────────────────────── tool loop ───────────────────────

    /**
     * 工具循环:调 {@link ChatModel#call(Prompt)},如果 response 含 tool calls
     * 就用 {@link ToolCallingManager#executeToolCalls(Prompt, ChatResponse)}
     * 执行工具并把结果塞回 prompt,再 call,直到响应不再含 tool calls 或达到
     * {@link AiProperties#getMaxToolIterations()} 安全上限。
     *
     * <p>调用前先把 {@link #toolCallbacks} 注入到 prompt 的 ChatOptions,这样
     * LLM 才知道有哪些工具可用(否则 LLM 永远不会主动调工具)。
     *
     * <p>{@link #toolCallbacks} 为空时,等价于单次 {@code chatModel.call(prompt)},
     * 零开销。
     */
    public ChatResponse callWithToolLoop(Prompt prompt, ChatModel chatModel) {
        if (toolCallbacks.isEmpty()) {
            return chatModel.call(prompt);
        }
        Prompt enriched = enrichPromptWithToolCallbacks(prompt);
        ChatResponse response = chatModel.call(enriched);
        int maxIter = Math.max(1, props.getMaxToolTurns());
        int iter = 0;
        while (hasToolCalls(response) && iter < maxIter) {
            iter++;
            log.info("[LLM tool loop] iter={}/{} vendor=({}) 含 {} 个 tool_call",
                    iter, maxIter, chatModel.getClass().getSimpleName(),
                    countToolCalls(response));
            try {
                ToolExecutionResult result = toolCallingManager.executeToolCalls(enriched, response);
                enriched = new Prompt(result.conversationHistory(), enriched.getOptions());
                response = chatModel.call(enriched);
            } catch (Exception e) {
                // 工具执行异常 — 不应该把 LLM 卡死,记日志后返回当前 response。
                // 异常内容已通过 ToolExecutionExceptionProcessor 写进 tool result 里
                // 喂给 LLM(如果 LLM 仍想继续循环),这里额外兜底一层,避免循环死。
                log.warn("[LLM tool loop] 工具执行异常,中断循环: {}", e.toString());
                break;
            }
        }
        if (iter >= maxIter && hasToolCalls(response)) {
            log.warn("[LLM tool loop] 达到 maxIter={} 上限,强制返回最后响应(可能含未执行的 tool_call)", maxIter);
        }
        return response;
    }

    /** 把 toolCallbacks 注入 prompt 的 ChatOptions,让 LLM 知道有哪些工具可用。 */
    private Prompt enrichPromptWithToolCallbacks(Prompt prompt) {
        ChatOptions opts = prompt.getOptions();
        if (opts == null) {
            opts = org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .build();
            return new Prompt(prompt.getInstructions(), opts);
        }
        if (opts instanceof ToolCallingChatOptions tco) {
            // ToolCallingChatOptions 没有 setter,只能通过 mutate() 重建
            // 一次,得到含 toolCallbacks 的新 options(底层实现是
            // TypedBeanPropertyMapper 复制字段,不会丢原有 model / temperature 等)。
            ChatOptions enriched = tco.mutate()
                    .toolCallbacks(toolCallbacks)
                    .build();
            return new Prompt(prompt.getInstructions(), enriched);
        }
        // 非 ToolCallingChatOptions 的 options(罕见)直接返回原 prompt,
        // 这种情况 LLM 看不到工具但也不报错,跟改前行为一致。
        return prompt;
    }

    private static boolean hasToolCalls(ChatResponse response) {
        if (response == null || response.getResults() == null) return false;
        for (Generation g : response.getResults()) {
            if (g.getOutput() instanceof AssistantMessage am && !am.getToolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int countToolCalls(ChatResponse response) {
        if (response == null || response.getResults() == null) return 0;
        int n = 0;
        for (Generation g : response.getResults()) {
            if (g.getOutput() instanceof AssistantMessage am) {
                n += am.getToolCalls().size();
            }
        }
        return n;
    }

    // ─────────────────────── 数字提取 ───────────────────────
    // Spring AI 2.0 的 Usage#getPromptTokens/getCompletionTokens/getTotalTokens
    // 返回 Integer(不是 1.x 时的 Long)。此处用 Integer 中转,转 long 时 .longValue()。

    private static Integer extractPromptTokensObj(ChatResponse resp) {
        if (resp == null || resp.getMetadata() == null) return null;
        Usage u = resp.getMetadata().getUsage();
        return u == null ? null : u.getPromptTokens();
    }

    private static Integer extractCompletionTokensObj(ChatResponse resp) {
        if (resp == null || resp.getMetadata() == null) return null;
        Usage u = resp.getMetadata().getUsage();
        return u == null ? null : u.getCompletionTokens();
    }

    private static long extractPromptTokens(ChatResponse resp) {
        Integer v = extractPromptTokensObj(resp);
        return v == null ? 0L : v.longValue();
    }

    private static long extractCompletionTokens(ChatResponse resp) {
        Integer v = extractCompletionTokensObj(resp);
        return v == null ? 0L : v.longValue();
    }
}

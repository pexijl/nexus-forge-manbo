package com.nexusforge.provider.openai;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.error.LlmErrorMapper;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import com.nexusforge.stream.OpenAiStreamParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI-compatible 协议 ChatModel 基类。
 *
 * <p>子类传入 {@code (vendor, defaultBaseUrl, defaultModel)} 即可注册一个新 vendor。
 * OpenAI / DeepSeek / Qwen / Ollama 全部走 OpenAI Chat Completions 协议,
 * 协议差异只在 base-url / 默认模型 / Authorization 头(都已参数化)。
 *
 * <p>不同协议(Anthropic Messages API)走 {@code AnthropicChatModel} 单独实现。
 *
 * <p>P4 行为变化:
 * <ul>
 *   <li>基类不依赖 {@code OpenAiChatModel} 单例字段,任何子类构造时按 vendor 解析
 *       {@link AiProperties.Provider},base-url 缺失时回退到子类的 {@code defaultBaseUrl}</li>
 *   <li>流式协议复用 {@link OpenAiJsonMapper} + {@link OpenAiStreamParser},
 *       Step 3 已扩展 tool_calls 增量解析,子类无感</li>
 *   <li>{@link ChatCapabilities} 暴露为可被子类覆写的 {@code capabilities()}</li>
 * </ul>
 *
 * <p>注意:本类在 Spring 容器里**不**是 {@code @Component}(没有 vendor 信息无法注册);
 * 每个具体 vendor 子类才是 @Component,由 {@code @ConditionalOnProperty(...enabled)} 控制激活。
 */
@Slf4j
public abstract class OpenAiCompatibleChatModel implements ChatModel {

    /** 子类传入的 vendor 名,也是 {@link #name()} 返回值 */
    protected final String vendor;

    /** 子类传入的兜底 base-url(用户未在 application.yaml 显式配置时使用) */
    protected final String defaultBaseUrl;

    /** 子类传入的兜底 default-model(同上) */
    protected final String defaultModel;

    /** 实际生效的配置(从 providers[vendor] 解析,base-url / default-model 已回填) */
    protected final AiProperties.Provider cfg;

    protected final AiProperties props;
    protected final HttpClient http;
    protected final ObjectMapper json;
    protected final OpenAiJsonMapper mapper;
    protected final OpenAiStreamParser streamParser;
    /**
     * P2 流式用 WebClient + Reactor Netty 传输层。
     *
     * <p>之前用 {@link JdkClientHttpConnector} 包装 JDK HttpClient,但 JDK HttpClient
     * 是阻塞型,在 {@code bodyToFlux} 路径上对 SSE 流响应有缓冲/吞帧问题——实测
     * (MockWebServer 与生产 qwen DashScope 均如此):上游 HTTP 200 + 完整 SSE body
     * 抵达后 {@code bodyToFlux(String.class)} 仍 emit 0 个元素,parseLines 一帧都拿不到,
     * 客户端拿到 200 + 空 SSE 流。
     *
     * <p>换成 ReactorClientHttpConnector(HttpClient.create() 默认配置)后,Netty 的
     * 背压式字节流正确把每个 chunk 推到下游,SSE 帧按到达顺序到达 parseLines。
     * 同步 {@link #call(ChatRequest)} 路径仍用 JDK HttpClient(单次 send + 阻塞读
     * 完整 body,与 SSE 无关),不需要换。
     */
    protected final WebClient webClient;

    /**
     * 构造基类。
     *
     * @param vendor         vendor 名,必须与 {@code spring.ai.providers.<vendor>} 的 key 一致
     * @param defaultBaseUrl 用户未配置时回退 base-url
     * @param defaultModel   用户未配置时回退 default-model
     * @param props          全局配置,用于读取 providers[vendor] / requestTimeout
     * @param json           Jackson ObjectMapper
     * @param mapper         协议转换器(OpenAI JSON ⇄ ChatRequest/Response)
     * @param streamParser   OpenAI SSE 解析器
     */
    protected OpenAiCompatibleChatModel(String vendor,
                                        String defaultBaseUrl,
                                        String defaultModel,
                                        AiProperties props,
                                        ObjectMapper json,
                                        OpenAiJsonMapper mapper,
                                        OpenAiStreamParser streamParser) {
        this.vendor = vendor;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.props = props;

        AiProperties.Provider p = props.getProviders().get(vendor);
        if (p == null || !p.isEnabled()) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "providers." + vendor + " 未配置或禁用");
        }
        // 把用户配置的 api-key + (可选 base-url / default-model)合并到 cfg
        // 注:复用 Provider 字段作为 cfg,避免新加 OpenAiCompatible 类型破坏既有 yaml 兼容性
        this.cfg = new AiProperties.Provider();
        this.cfg.setEnabled(p.isEnabled());
        this.cfg.setApiKey(p.getApiKey());
        this.cfg.setBaseUrl(p.getBaseUrl() == null ? defaultBaseUrl : p.getBaseUrl());
        // default-model 三层优先级:yaml > 子类兜底 > null(允许)
        // 但若 yaml 和子类兜底都拿不到 default-model,启动直接 fail-fast,
        // 强制 "管理员必须先设置全局 model" 的语义;否则系统模式请求会发 model=null
        // 到上游被 400 拒绝,运行时排错更费时。
        String resolvedDefaultModel = p.getDefaultModel() != null ? p.getDefaultModel() : defaultModel;
        if (resolvedDefaultModel == null || resolvedDefaultModel.isBlank()) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                    "vendor=" + vendor + " 缺少 default-model:请在 application.yaml 设 "
                    + "spring.ai.providers." + vendor + ".default-model,"
                    + "或调用 PUT /api/admin/ai/global-default 设置 ai_global_default 表(vendor="
                    + vendor + " 仍需 yaml 注册兜底才能完成构造)");
        }
        this.cfg.setDefaultModel(resolvedDefaultModel);
        this.cfg.setSupportsStream(p.getSupportsStream());
        this.cfg.setSupportsTools(p.getSupportsTools());

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.json = json;
        this.mapper = mapper;
        this.streamParser = streamParser;

        // P2 流式:用 Reactor Netty HTTP 客户端作传输层。ReactorClientHttpConnector 是
        // WebClient 的原生搭档,字节流按背压推到下游,SSE 帧不丢;JdkClientHttpConnector
        // 包装阻塞型 JDK HttpClient,对流式响应有吞帧问题(详见类注释)。
        // responseTimeout 等价于 props.requestTimeout:超过该时长没收到任何数据触发
        // TimeoutException,与上游 .timeout() 配合形成"全链路超时"。
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(
                reactor.netty.http.client.HttpClient.create().responseTimeout(props.getRequestTimeout()));
        this.webClient = WebClient.builder()
                .clientConnector(connector)
                .build();
    }

    @Override
    public final String name() {
        return vendor;
    }

    /**
     * 默认能力: stream / tools / jsonMode 全开,vision 关闭(子类可覆写)。
     * Ollama 因本地 vision 模型少见,默认 false;OpenAI / DeepSeek / Qwen 都 true。
     */
    @Override
    public ChatCapabilities capabilities() {
        return ChatCapabilities.builder()
                .stream(true)
                .tools(true)
                .vision(false)
                .jsonMode(true)
                .build();
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        Duration t = props.getRequestTimeout();
        long start = System.nanoTime();
        try {
            String url = cfg.getBaseUrl() + "/chat/completions";
            // mapper.toOpenAi 内部会优先读 req.options["model"],fallback 到 providerDefaultModel;
            // 这里传 cfg.getDefaultModel() 作 yaml 兜底,PreferenceResolver 的 model 经由
            // LlmClient.call(req, vendor, model) 写入 req.options["model"]。
            OpenAiJsonMapper.OpenAiRequestBody body = mapper.toOpenAi(request, cfg.getDefaultModel());
            String payload = json.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(t)
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (resp.statusCode() / 100 != 2) {
                throw LlmErrorMapper.fromHttp(resp.statusCode(), resp.body(), Duration.ofMillis(elapsed));
            }
            return mapper.fromOpenAi(json.readTree(resp.body()), elapsed);
        } catch (HttpTimeoutException e) {
            throw LlmErrorMapper.fromTimeout(t);
        } catch (java.net.ConnectException e) {
            throw LlmErrorMapper.fromConnect(e);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(ResultCode.LLM_PROVIDER_ERROR, e.getMessage());
        }
    }

    /**
     * 流式入口。用 {@link WebClient} 调 {@code /chat/completions} with {@code stream=true},
     * 通过 {@link OpenAiStreamParser#parseEvents(Flux)} 把 Spring {@code SseEventDecoder}
     * 解码后的事件 payload 转成 {@link ChatChunk} 流。
     *
     * <p>Spring 默认 codec 链在 {@code accept=text/event-stream} 时选中
     * {@code SseEventDecoder},自动按 {@code data:} 行 + {@code \n\n} 分隔切事件;
     * 下游拿到的每个元素就是单条 {@code data:} 的 JSON 字符串(已剥前缀/分隔符),
     * 直接走 {@link OpenAiStreamParser#parseEvents(Flux)}。原 {@code parseLines}
     * 用于 {@code text/plain} 路径(原始 SSE wire 格式),保留作为 fallback。
     *
     * <p>取消语义:下游订阅 dispose → Reactor 关闭 Netty 连接。
     * 错误语义:HTTP 4xx/5xx 抛 {@link LlmException};超时(TimeoutException)映射到
     * {@code LLM_UPSTREAM_TIMEOUT};网络断开映射到 {@code LLM_PROVIDER_ERROR}。
     */
    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        String url = cfg.getBaseUrl() + "/chat/completions";
        // 同 call():model 由 OpenAiJsonMapper 决定(优先 req.options["model"],fallback yaml default)
        OpenAiJsonMapper.OpenAiRequestBody body = mapper.toOpenAi(request, cfg.getDefaultModel());
        body.stream = true;

        return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchangeToFlux(resp -> {
                    int code = resp.statusCode().value();
                    if (code / 100 != 2) {
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMapMany(errBody -> Mono.error(
                                        LlmErrorMapper.fromHttp(code, errBody, props.getRequestTimeout())));
                    }
                    return resp.bodyToFlux(String.class)
                            .transform(streamParser::parseEvents);
                })
                .timeout(props.getRequestTimeout())
                .onErrorMap(err -> mapStreamError(err, props.getRequestTimeout()));
    }

    /**
     * 上游错误统一映射:
     * <ul>
     *   <li>{@link TimeoutException} → {@code LLM_UPSTREAM_TIMEOUT}</li>
     *   <li>已有 {@link LlmException} 直接抛</li>
     *   <li>其它 → {@code LLM_PROVIDER_ERROR}(含底层网络异常、解析异常等)</li>
     * </ul>
     */
    protected static Throwable mapStreamError(Throwable err, Duration timeout) {
        if (err instanceof LlmException) {
            return err;
        }
        if (err instanceof TimeoutException) {
            return LlmErrorMapper.fromTimeout(timeout);
        }
        return new LlmException(ResultCode.LLM_PROVIDER_ERROR, "流式调用失败: " + err.getMessage());
    }
}

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
import org.springframework.http.client.reactive.JdkClientHttpConnector;
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
        this.cfg.setDefaultModel(p.getDefaultModel() == null ? defaultModel : p.getDefaultModel());
        this.cfg.setSupportsStream(p.getSupportsStream());
        this.cfg.setSupportsTools(p.getSupportsTools());

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.json = json;
        this.mapper = mapper;
        this.streamParser = streamParser;

        // JdkClientHttpConnector 把 JDK HttpClient 包成 ClientHttpConnector,
        // 走 reactor 但底层仍是 java.net.http.HttpClient,readTimeout 是单响应时长
        JdkClientHttpConnector connector = new JdkClientHttpConnector(this.http);
        connector.setReadTimeout(props.getRequestTimeout());
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
     * 通过 {@link OpenAiStreamParser#parseLines(Flux)} 把上游 SSE 帧切成 {@link ChatChunk} 流。
     *
     * <p>取消语义:下游订阅 dispose → JDK HttpClient 响应体 close → Reactor 关闭连接。
     * 错误语义:HTTP 4xx/5xx 抛 {@link LlmException};超时(TimeoutException)映射到
     * {@code LLM_UPSTREAM_TIMEOUT};网络断开映射到 {@code LLM_PROVIDER_ERROR}。
     */
    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        String url = cfg.getBaseUrl() + "/chat/completions";
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
                            .transform(streamParser::parseLines);
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

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
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

@Slf4j
@Component
@ConditionalOnProperty(name = "spring.ai.providers.openai.enabled", havingValue = "true", matchIfMissing = true)
public class OpenAiChatModel implements ChatModel {

    private final AiProperties.OpenAi openai;          // 见下 OpenAi 子类
    private final HttpClient http;
    private final ObjectMapper json;
    private final OpenAiJsonMapper mapper;
    private final OpenAiStreamParser streamParser;
    private final AiProperties props;                  // 取 requestTimeout
    private final WebClient webClient;                 // P2 stream 用

    public OpenAiChatModel(AiProperties props, ObjectMapper json, OpenAiJsonMapper mapper,
                           OpenAiStreamParser streamParser) {
        this.props = props;
        AiProperties.Provider p = props.getProviders().get("openai");
        if (p == null || !p.isEnabled()) {
            throw new LlmException(ResultCode.LLM_CONFIG_MISSING, "providers.openai 未配置或禁用");
        }
        this.openai = new AiProperties.OpenAi();
        this.openai.setApiKey(p.getApiKey());
        this.openai.setBaseUrl(p.getBaseUrl() == null ? "https://api.openai.com/v1" : p.getBaseUrl());
        this.openai.setDefaultModel(p.getDefaultModel() == null ? "gpt-4o-mini" : p.getDefaultModel());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.json = json;
        this.mapper = mapper;
        this.streamParser = streamParser;
        // Spring 7 的 JdkClientHttpConnector:把 JDK HttpClient 包成 ClientHttpConnector,
        // 走 reactor 但底层仍是 java.net.http.HttpClient。和同步 call() 复用同一个 Client 实例。
        // readTimeout 是单响应时长,Flux.timeout 是总流时长,二者并存。
        JdkClientHttpConnector connector = new JdkClientHttpConnector(this.http);
        connector.setReadTimeout(props.getRequestTimeout());
        this.webClient = WebClient.builder()
                .clientConnector(connector)
                .build();
    }

    @Override public String name() { return "openai"; }

    @Override public ChatCapabilities capabilities() {
        return ChatCapabilities.builder().stream(true).tools(true).vision(true).jsonMode(true).build();
    }

    @Override public ChatResponse call(ChatRequest request) {
        Duration t = props.getRequestTimeout();
        long start = System.nanoTime();
        try {
            String url = openai.getBaseUrl() + "/chat/completions";
            OpenAiJsonMapper.OpenAiRequestBody body = mapper.toOpenAi(request, openai.getDefaultModel());
            String payload = json.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(t)
                    .header("Authorization", "Bearer " + openai.getApiKey())
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
     * P2 流式入口:用 {@link WebClient} 调 OpenAI {@code /chat/completions} with {@code stream=true},
     * 通过 {@link OpenAiStreamParser#parseLines(Flux)} 把上游 SSE 帧切成 {@link ChatChunk} 流。
     *
     * <p>取消语义:下游订阅 dispose → JDK HttpClient 响应体 close → Reactor 关闭连接。
     * 错误语义:HTTP 4xx/5xx 抛 {@link LlmException};超时(TimeoutException)映射到
     * {@code LLM_UPSTREAM_TIMEOUT};网络断开映射到 {@code LLM_PROVIDER_ERROR}。
     */
    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        String url = openai.getBaseUrl() + "/chat/completions";
        OpenAiJsonMapper.OpenAiRequestBody body = mapper.toOpenAi(request, openai.getDefaultModel());
        body.stream = true;        // 流式模式

        return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + openai.getApiKey())
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
                    // 成功:把响应按行片段喂给 OpenAiStreamParser
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
    private static Throwable mapStreamError(Throwable err, Duration timeout) {
        if (err instanceof LlmException) {
            return err;
        }
        if (err instanceof TimeoutException) {
            return LlmErrorMapper.fromTimeout(timeout);
        }
        // Reactor 包装的 WebClient 异常也是 RuntimeException;归入 PROVIDER_ERROR
        return new LlmException(ResultCode.LLM_PROVIDER_ERROR, "流式调用失败: " + err.getMessage());
    }
}
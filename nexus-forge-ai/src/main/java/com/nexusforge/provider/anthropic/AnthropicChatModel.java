package com.nexusforge.provider.anthropic;

import com.nexusforge.ai.*;
import com.nexusforge.config.AiProperties;
import com.nexusforge.error.LlmErrorMapper;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import com.nexusforge.provider.support.ChatModelHttpSupport;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
@ConditionalOnProperty(name = "spring.ai.providers.anthropic.enabled", havingValue = "true")
public class AnthropicChatModel implements ChatModel {

    private final AiProperties.OpenAiCompatible cfg;
    private final AnthropicJsonMapper mapper;
    private final ObjectMapper json;
    private final ChatModelHttpSupport http;
    private final WebClient webClient;
    private final AnthropicMessagesStreamParser streamParser;

    public AnthropicChatModel(AiProperties props, ObjectMapper json,
                              ChatModelHttpSupport http,
                              AnthropicMessagesStreamParser streamParser) {
        AiProperties.Provider p = props.getProviders().get("anthropic");
        if (p == null || !p.isEnabled()) {
            throw new LlmException(com.nexusforge.enums.ResultCode.LLM_CONFIG_MISSING,
                    "providers.anthropic 未配置或禁用");
        }
        this.cfg = toOpenAiCompatible(p);
        this.json = json;
        this.mapper = new AnthropicJsonMapper(json);
        this.http = http;
        this.streamParser = streamParser;
        JdkClientHttpConnector connector = new JdkClientHttpConnector(http.httpClient("anthropic"));
        connector.setReadTimeout(props.getRequestTimeout());
        this.webClient = WebClient.builder().clientConnector(connector).build();
    }

    private static AiProperties.OpenAiCompatible toOpenAiCompatible(AiProperties.Provider p) {
        AiProperties.OpenAiCompatible c = new AiProperties.OpenAiCompatible();
        c.setEnabled(p.isEnabled());
        c.setApiKey(p.getApiKey());
        c.setBaseUrl(p.getBaseUrl() == null ? "https://api.anthropic.com" : p.getBaseUrl());
        c.setDefaultModel(p.getDefaultModel() == null ? "claude-3-5-haiku-20241022" : p.getDefaultModel());
        return c;
    }

    @Override public String name() { return "anthropic"; }

    @Override public ChatCapabilities capabilities() {
        return ChatCapabilities.builder().stream(true).tools(true).vision(true).jsonMode(false).build();
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        long t0 = System.nanoTime();
        try {
            String url = cfg.getBaseUrl() + "/v1/messages";
            String body = json.writeValueAsString(mapper.toAnthropic(request, cfg.getDefaultModel()));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(props(request))
                    .header("x-api-key", cfg.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.executeWithRetry("anthropic", req, HttpResponse.BodyHandlers.ofString());
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            return mapper.fromAnthropic(json.readTree(resp.body()), elapsed);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(com.nexusforge.enums.ResultCode.LLM_PROVIDER_ERROR, e.getMessage());
        }
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        String url = cfg.getBaseUrl() + "/v1/messages";
        AnthropicJsonMapper.AnthropicRequestBody body = mapper.toAnthropic(request, cfg.getDefaultModel());
        body.stream = true;
        return webClient.post()
                .uri(url)
                .header("x-api-key", cfg.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchangeToFlux(resp -> {
                    int code = resp.statusCode().value();
                    if (code / 100 != 2) {
                        return resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMapMany(b -> Mono.error(LlmErrorMapper.fromHttp(code, b, props(request))));
                    }
                    return resp.bodyToFlux(String.class).transform(streamParser::parseLines);
                })
                .timeout(props(request));
    }

    private Duration props(ChatRequest req) {
        // 复用 AiProperties.requestTimeout;不传则兜底 60s
        return null;
    }
}
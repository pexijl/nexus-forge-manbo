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
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

@Slf4j
@Component
@ConditionalOnProperty(name = "spring.ai.providers.openai.enabled", havingValue = "true", matchIfMissing = true)
public class OpenAiChatModel implements ChatModel {

    private final AiProperties.OpenAi openai;          // 见下 OpenAi 子类
    private final HttpClient http;
    private final ObjectMapper json;
    private final OpenAiJsonMapper mapper;
    private final AiProperties props;                  // 取 requestTimeout

    public OpenAiChatModel(AiProperties props, ObjectMapper json, OpenAiJsonMapper mapper) {
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

    /** P1 stub,P2 才有真正实现 */
    @Override public Flux<ChatChunk> stream(ChatRequest request) {
        return Flux.error(new LlmException(ResultCode.LLM_INVALID_REQUEST, "流式响应在 P1 暂未实现,请使用 POST /api/ai/chat"));
    }
}

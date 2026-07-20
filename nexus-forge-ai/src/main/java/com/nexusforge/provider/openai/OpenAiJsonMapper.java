package com.nexusforge.provider.openai;

import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.ChatUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * OpenAI ⇄ ChatRequest/ChatResponse 之间的 JSON 转换。
 * 兼容所有 OpenAI-compatible 端点(Ollama/DeepSeek/Qwen 等,P4 复用)。
 */
@Component
@RequiredArgsConstructor
public class OpenAiJsonMapper {

    private final ObjectMapper json;

    public OpenAiRequestBody toOpenAi(ChatRequest req, String providerDefaultModel) {
        OpenAiRequestBody body = new OpenAiRequestBody();
        body.model = providerDefaultModel;
        body.stream = false;       // P1 固定
        body.temperature = req.getTemperature();
        body.max_tokens = req.getMaxTokens();
        body.messages = req.getMessages().stream().map(this::toOpenAiMessage).toList();
        return body;
    }

    public ChatResponse fromOpenAi(JsonNode body, long latencyMillis) {
        var choice0 = body.path("choices").path(0);
        ChatResponse.ChatResponseBuilder b = ChatResponse.builder()
                .id(body.path("id").asString())
                .model(body.path("model").asString())
                .content(choice0.path("message").path("content").asString(""))
                .finishReason(choice0.path("finish_reason").asString("stop"))
                .latencyMillis(latencyMillis);
        JsonNode usage = body.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            b.usage(ChatUsage.builder()
                    .promptTokens(usage.path("prompt_tokens").asInt())
                    .completionTokens(usage.path("completion_tokens").asInt())
                    .totalTokens(usage.path("total_tokens").asInt())
                    .build());
        }
        return b.build();
    }

    private OpenAiMessage toOpenAiMessage(ChatMessage m) {
        OpenAiMessage om = new OpenAiMessage();
        om.role = m.getRole().name().toLowerCase();
        om.content = m.getContent();
        return om;
    }

    /* --- DTOs --- */
    public static class OpenAiRequestBody {
        public String model;
        public boolean stream;
        public Double temperature;
        public Integer max_tokens;
        public List<OpenAiMessage> messages;
    }
    public static class OpenAiMessage {
        public String role;
        public String content;
    }
}
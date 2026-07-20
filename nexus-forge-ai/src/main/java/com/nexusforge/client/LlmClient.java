package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.router.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * LLM 门面。其他模块(如未来可能的 nexus-forge-visual 摘要生成)只依赖此门面,
 * 不直接依赖 ChatModel SPI。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ChatModelRouter router;

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

    public Flux<ChatChunk> stream(ChatRequest request) {
        ChatModelRouter.Resolved r = router.resolve(request);
        ChatRequest req = withModel(request, r.modelName());
        req.setStream(Boolean.TRUE);
        return r.model().stream(req);
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

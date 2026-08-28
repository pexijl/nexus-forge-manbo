package com.nexusforge.provider.anthropic;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatUsage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Anthropic Messages API SSE 解析。
 *
 * <p>上游事件类型:
 * <pre>
 *   event: message_start
 *   data: {"type":"message_start","message":{...,"usage":{"input_tokens":N,"output_tokens":1}}}
 *
 *   event: content_block_start
 *   data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
 *
 *   event: content_block_delta
 *   data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}
 *
 *   event: content_block_stop
 *   data: {"type":"content_block_stop","index":0}
 *
 *   event: message_delta
 *   data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":N}}
 *
 *   event: message_stop
 *   data: {"type":"message_stop"}
 * </pre>
 *
 * <p>本类按 line 切 SSE event,按 event type 路由,组装 ChatChunk:
 * text_delta → deltaContent;usage(末帧) → usage;stop_reason → finishReason。
 */
@Component
public class AnthropicMessagesStreamParser {

    private final ObjectMapper json = new ObjectMapper();

    public Flux<ChatChunk> parseLines(Flux<String> lines) {
        StringBuilder eventBuf = new StringBuilder();
        return lines.collectList().map(events -> {
            // 简化实现:整个流 join 后逐 event 解析;
            // 生产可换成 stateful buffer,但 Anthropic 单事件 body 完整,
            // join 起来直接 parse 也行。
            return events.stream()
                    .map(this::parseSseEvent)
                    .filter(Objects::nonNull)
                    .toList();
        }).flatMapMany(Flux::fromIterable);
    }

    private ChatChunk parseSseEvent(String event) {
        // 形如:event: message_start\ndata: {...}\n\n
        String data = null;
        for (String line : event.split("\n")) {
            if (line.startsWith("data:")) {
                data = line.substring(5).trim();
            }
        }
        if (data == null || data.isEmpty()) return null;
        try {
            JsonNode root = json.readTree(data);
            String type = root.path("type").asString();
            ChatChunk.ChatChunkBuilder b = ChatChunk.builder();
            switch (type) {
                case "message_start" -> {
                    JsonNode msg = root.path("message");
                    b.id(msg.path("id").asString())
                            .model(msg.path("model").asString());
                    JsonNode usage = msg.path("usage");
                    if (!usage.isMissingNode()) {
                        b.usage(ChatUsage.builder()
                                .promptTokens(usage.path("input_tokens").asInt())
                                .completionTokens(usage.path("output_tokens").asInt())
                                .totalTokens(usage.path("input_tokens").asInt()
                                        + usage.path("output_tokens").asInt())
                                .build());
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = root.path("delta");
                    if ("text_delta".equals(delta.path("type").asString())) {
                        b.deltaContent(delta.path("text").asString());
                    }
                }
                case "message_delta" -> {
                    String fr = root.path("delta").path("stop_reason").asString("");
                    if (!fr.isEmpty()) b.finishReason(fr);
                    JsonNode usage = root.path("usage");
                    if (!usage.isMissingNode()) {
                        b.usage(ChatUsage.builder()
                                .promptTokens(usage.path("input_tokens").asInt())
                                .completionTokens(usage.path("output_tokens").asInt())
                                .totalTokens(usage.path("input_tokens").asInt()
                                        + usage.path("output_tokens").asInt())
                                .build());
                    }
                }
                default -> { return null; }
            }
            return b.build();
        } catch (Exception e) {
            return null;     // 单条失败吞掉,不断流
        }
    }
}
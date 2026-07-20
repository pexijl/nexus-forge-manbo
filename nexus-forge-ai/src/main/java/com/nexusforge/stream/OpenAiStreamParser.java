package com.nexusforge.stream;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.function.Consumer;

/**
 * OpenAI 流式响应解析器。上游 {@code /chat/completions} with {@code stream: true}
 * 返回 {@code text/event-stream},每帧形如:
 *
 * <pre>
 *   data: {"id":"cmpl-…","choices":[{"delta":{"content":"hi"}}]}
 *
 *   data: [DONE]
 *
 * </pre>
 *
 * <p>本类负责把上游 byte stream 切成事件,把每个事件转为 {@link ChatChunk} 或
 * 终止信号。基于 WebFlux 的 {@code Flux<ChatChunk>},上游断开时下游订阅被自动取消。
 */
@Component
public class OpenAiStreamParser {

    /**
     * 把上游返回的整段 SSE 文本(可能是上游一次性返回的、来自 Flux<ByteBuffer> 的)
     * 切成 ChatChunk 流。
     *
     * <p>P2 实现选择:**逐行解析**,每行以 {@code data: } 起头的视为有效载荷;
     * 单独一行的 {@code data: [DONE]} 视为流结束。
     *
     * <p>真实接线中本方法接收的并非字符串,而是 Flux<DataBuffer> 或
     * WebClient 的 exchangeToFlux(...)。这里给字符串版本以方便单测。
     */
    public Flux<ChatChunk> parse(String fullSseText) {
        return Flux.create(sink -> {
            try (BufferedReader r = new BufferedReader(new StringReader(fullSseText))) {
                String line;
                StringBuilder dataBuf = new StringBuilder();
                boolean inData = false;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        // 空行 = SSE 帧终结;处理累积的 data
                        if (!dataBuf.isEmpty()) {
                            String data = dataBuf.toString();
                            dataBuf.setLength(0);
                            inData = false;
                            if (data.equals("[DONE]")) {
                                sink.complete();
                                return;
                            }
                            ChatChunk chunk = parseDataLine(data);
                            if (chunk != null) sink.next(chunk);
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        String rest = line.substring("data:".length()).trim();
                        if (!rest.isEmpty()) {
                            if (!dataBuf.isEmpty()) dataBuf.append('\n');
                            dataBuf.append(rest);
                            inData = true;
                        }
                        continue;
                    }
                    if (line.startsWith(":")) {
                        // 注释/心跳,SSE 客户端忽略
                        continue;
                    }
                    // 其他字段(event: id: ...)忽略
                }
                // 末尾没看到 [DONE] 时也正常结束,但要保证上游要么结束要么报错,不应有数据悬挂
                sink.complete();
            } catch (Exception e) {
                sink.error(new LlmException(ResultCode.LLM_PROVIDER_ERROR, "解析 SSE 失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 流式入口:接收上游 {@link WebClient#bodyToFlux(Class) bodyToFlux(String.class)}
     * 给出的行片段(每个片段可能包含 0 个、1 个或多个 SSE 事件),按 {@code \n\n} 分隔
     * 解析为 {@link ChatChunk} 序列。订阅断开时,Reactor 自动 dispose 上游,
     * {@link WebClient} 的 HTTP 连接随之关闭。
     *
     * <p>每个订阅拥有独立的 {@code StringBuilder buffer}(通过 {@link Flux#create} 闭包捕获),
     * 不会跨订阅共享状态。
     */
    public Flux<ChatChunk> parseLines(Flux<String> chunks) {
        return Flux.create(sink -> {
            StringBuilder buf = new StringBuilder();
            Consumer<String> onChunk = chunk -> {
                buf.append(chunk);
                // 反复抽取完整事件,直到缓冲里没有完整的 \n\n
                int boundary;
                while ((boundary = indexOfEventBoundary(buf)) >= 0) {
                    String event = buf.substring(0, boundary);
                    buf.delete(0, boundary + 2);   // 去掉事件 + \n\n
                    ChatChunk c = processEventBlock(event);
                    if (c == null) {
                        // [DONE] 终止符;后续可能还有 trailing 注释/心跳,直接吃掉
                        sink.complete();
                        return;
                    }
                    sink.next(c);
                }
            };
            chunks.subscribe(onChunk, sink::error, sink::complete);
        });
    }

    /**
     * 在缓冲中找下一个完整的 SSE 事件边界("\n\n")的位置。
     * <p>事件可能横跨多个 chunk;找不到时返回 -1,继续累积。
     */
    private static int indexOfEventBoundary(StringBuilder buf) {
        for (int i = 0; i <= buf.length() - 2; i++) {
            if (buf.charAt(i) == '\n' && buf.charAt(i + 1) == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 单条事件文本(已剥掉尾部 \n\n)按行解析:
     *   - 以 {@code data:} 开头的行 → 载荷
     *   - 多行 data 拼接(OpenAI 不常见但 SSE 规范允许)
     *   - 注释行(以 {@code :} 开头)忽略
     *   - 其他字段(event: id: ...)忽略
     * <p>返回 {@code null} 表示遇到 {@code [DONE]} 终止符(调用方应完成流);
     * 解析失败时也返回 {@code null},与 {@link #parse(String)} 行为一致(单条吞掉)。
     */
    private ChatChunk processEventBlock(String event) {
        StringBuilder dataBuf = new StringBuilder();
        for (String line : event.split("\n")) {
            if (line.startsWith("data:")) {
                String rest = line.substring("data:".length()).trim();
                if (!rest.isEmpty()) {
                    if (dataBuf.length() > 0) dataBuf.append('\n');
                    dataBuf.append(rest);
                }
            }
            // event: / id: / :ping 一律忽略
        }
        if (dataBuf.isEmpty()) {
            return null;     // 心跳或空事件
        }
        if ("[DONE]".equals(dataBuf.toString())) {
            return null;     // 终止信号
        }
        return parseDataLine(dataBuf.toString());
    }

    /**
     * 单条 data 载荷解码为 ChatChunk。OpenAI 流式 chunk 结构:
     *   { "id": "...", "model": "...", "choices": [ { "delta": { "content": "..." }, "finish_reason": null|"stop"|"length" } ],
     *     "usage": {prompt_tokens, completion_tokens, total_tokens}        ← 只在最后一帧出现
     *   }
     */
    ChatChunk parseDataLine(String data) {
        try {
            JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(data);
            ChatChunk.ChatChunkBuilder b = ChatChunk.builder()
                    .id(strOrNull(root, "id"))
                    .model(strOrNull(root, "model"));
            JsonNode choice0 = root.path("choices").path(0);
            if (!choice0.isMissingNode()) {
                String content = choice0.path("delta").path("content").isNull()
                        ? null
                        : choice0.path("delta").path("content").asString();
                b.deltaContent(content == null ? "" : content);
                String fr = choice0.path("finish_reason").asText("");
                if (!fr.isEmpty() && !"null".equals(fr)) b.finishReason(fr);
            }
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull() && usage.isObject()) {
                b.usage(com.nexusforge.ai.ChatUsage.builder()
                        .promptTokens(usage.path("prompt_tokens").asInt(0))
                        .completionTokens(usage.path("completion_tokens").asInt(0))
                        .totalTokens(usage.path("total_tokens").asInt(0))
                        .build());
            }
            return b.build();
        } catch (Exception e) {
            return null;     // 解析失败单条吞掉,不要把整条流炸掉
        }
    }

    /** Jackson 3.x:asString(null) 在 null 节点上抛 NPE;这里把 NullNode 当作 missing 处理。 */
    private static String strOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() ? null : v.asString();
    }
}
package com.nexusforge.stream;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import com.nexusforge.ai.DeltaToolCall;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;
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
@Slf4j
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
     * 给出的 SSE 帧片段(每个片段可能包含 0 个、1 个或多个 SSE 事件),按 {@code \n\n} 分隔
     * 解析为 {@link ChatChunk} 序列。订阅断开时,Reactor 自动 dispose 上游,
     * {@link WebClient} 的 HTTP 连接随之关闭。
     *
     * <p>每个订阅拥有独立的 {@code StringBuilder buffer}(通过 {@link Flux#create} 闭包捕获),
     * 不会跨订阅共享状态。
     *
     * <p><b>契约前提</b>:此方法假设上游 {@code bodyToFlux(String.class)} 返回的是
     * <em>原始 SSE wire 格式</em>(即 {@code data: <json>\n\n} 序列)。在 Spring MVC
     * 容器里用 {@code .accept(MediaType.TEXT_PLAIN)} 而不是 TEXT_EVENT_STREAM 才能
     * 走 {@code StringDecoder} 而不是 {@code SseEventDecoder}(后者会剥掉 {@code data:}
     * 前缀与 {@code \n\n} 分隔,导致本方法永远拿不到事件边界)。
     */
    public Flux<ChatChunk> parseLines(Flux<String> chunks) {
        return Flux.create(sink -> {
            StringBuilder buf = new StringBuilder();
            AtomicInteger emittedCount = new AtomicInteger();
            Consumer<String> onChunk = chunk -> {
                buf.append(chunk);
                // 反复抽取完整事件(\n\n),直到缓冲里没有完整的 \n\n
                int boundary;
                while ((boundary = indexOfEventBoundary(buf)) >= 0) {
                    String event = buf.substring(0, boundary);
                    buf.delete(0, boundary + 2);   // 去迴事件 + \n\n
                    ChatChunk c = processEventBlock(event);
                    if (c == null) {
                        // [DONE] 终止符;后续可能还有 trailing 注释/心跳,直接吃掉
                        sink.complete();
                        return;
                    }
                    sink.next(c);
                    emittedCount.incrementAndGet();
                }
            };
            // 防御性 onComplete:上游可能在最后一段 chunk 没有 \n\n 终止符就关闭
            // (qwen DashScope / 部分代理服务器实测会这样),残留 buffer 视为最后一个事件。
            Runnable onComplete = () -> {
                if (buf.length() > 0) {
                    ChatChunk c = processEventBlock(buf.toString());
                    if (c != null) {
                        sink.next(c);
                        emittedCount.incrementAndGet();
                    }
                }
                if (emittedCount.get() == 0) {
                    // 上游 HTTP 200 但 0 帧:body 极可能是非 SSE 错误体或空。
                    // 现状不强制 sink.error(避免改动既有契约),但记 warn 把现象暴露出来,
                    // 排障时一眼能看到"上游没发任何 SSE 帧"而非被默认 onComplete 吞掉。
                    log.warn("[OpenAiStreamParser] 上游 SSE 流完成时 0 帧已发射:body 可能非 SSE、"
                            + "为空或被代理截断。检查上游响应 content-type 与 body 字节。");
                }
                sink.complete();
            };
            chunks.subscribe(onChunk, sink::error, onComplete);
        });
    }

    /**
     * 流式入口(已解码事件):接收 Spring {@code SseEventDecoder} 解码后的事件
     * payload(每个元素是单条 {@code data:} 的 JSON 字符串,已剥离 {@code data:} 前缀
     * 与 {@code \n\n} 分隔),直接转为 {@link ChatChunk} 序列。
     *
     * <p>与 {@link #parseLines(Flux)} 的差异:
     * <ul>
     *   <li>上游已按事件切分,本方法不做缓冲与 {@code \n\n} 边界查找</li>
     *   <li>每个元素必是单条 {@code data:} 内容,不包含 `event:` / 多行 / 注释</li>
     *   <li>{@code [DONE]} 以 payload 形式作为终止信号</li>
     * </ul>
     *
     * <p>何时用哪个:
     * <ul>
     *   <li>上游 accept = {@code text/event-stream} → Spring 选 {@code SseEventDecoder} → 走本方法</li>
     *   <li>上游 accept = {@code text/plain}        → Spring 选 {@code StringDecoder}(原始 SSE wire) → 走 {@link #parseLines}</li>
     * </ul>
     */
    public Flux<ChatChunk> parseEvents(Flux<String> eventPayloads) {
        return Flux.create(sink -> {
            AtomicInteger emittedCount = new AtomicInteger();
            Consumer<String> onNext = payload -> {
                if (payload == null || payload.isEmpty()) {
                    return;     // 心跳
                }
                if ("[DONE]".contentEquals(payload)) {
                    sink.complete();
                    return;
                }
                ChatChunk c = parseDataLine(payload);
                if (c == null) {
                    return;     // 单条解析失败:与 parseLines 行为一致,吞掉不炸整条流
                }
                sink.next(c);
                emittedCount.incrementAndGet();
            };
            Runnable onComplete = () -> {
                if (emittedCount.get() == 0) {
                    log.warn("[OpenAiStreamParser] 上游 SSE 流完成时 0 帧已发射:body 可能非 SSE、"
                            + "为空或被代理截断。检查上游响应 content-type 与 body 字节。");
                }
                sink.complete();
            };
            eventPayloads.subscribe(onNext, sink::error, onComplete);
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
                    if (!dataBuf.isEmpty()) dataBuf.append('\n');
                    dataBuf.append(rest);
                }
            }
            // event: / id: / :ping 一律忽略
        }
        if (dataBuf.isEmpty()) {
            return null;     // 心跳或空事件
        }
        if ("[DONE]".contentEquals(dataBuf)) {
            return null;     // 终止信号
        }
        return parseDataLine(dataBuf.toString());
    }

    /**
     * 单条 data 载荷解码为 ChatChunk。OpenAI 流式 chunk 结构:
     *   { "id": "...", "model": "...", "choices": [ { "delta": { "content": "...",
     *                                                        "tool_calls": [{index, id, function:{name, arguments}}] },
     *                                                  "finish_reason": null|"stop"|"length"|"tool_calls" } ],
     *     "usage": {prompt_tokens, completion_tokens, total_tokens}        ← 只在最后一帧出现
     *   }
     *
     * <p>P4 扩展:同时提取 {@code delta.tool_calls[]} 为 {@link DeltaToolCall} 列表,
     * 后续由 {@code FunctionCallAggregator} 按 index 聚合为完整 ToolCall。
     * {@code arguments} 字段是 JSON 字符串分片,逐帧拼接后由 aggregator 整体 parse 成 JsonNode。
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
                String fr = choice0.path("finish_reason").asString("");
                if (!fr.isEmpty() && !"null".equals(fr)) b.finishReason(fr);
            }
            // P4:delta.tool_calls[] 流式增量解析(OpenAI 协议特有)
            JsonNode deltaTcs = choice0.path("delta").path("tool_calls");
            if (deltaTcs.isArray() && !deltaTcs.isEmpty()) {
                java.util.List<com.nexusforge.ai.DeltaToolCall> deltas = new java.util.ArrayList<>();
                for (JsonNode one : deltaTcs) {
                    Integer idx = one.has("index") && !one.path("index").isNull()
                            ? one.path("index").asInt() : null;
                    String id = one.has("id") && !one.path("id").isNull()
                            ? one.path("id").asString() : null;
                    JsonNode fn = one.path("function");
                    String name = fn.has("name") && !fn.path("name").isNull()
                            ? fn.path("name").asString() : null;
                    String argsChunk = fn.has("arguments") && !fn.path("arguments").isNull()
                            ? fn.path("arguments").asString() : null;
                    deltas.add(com.nexusforge.ai.DeltaToolCall.builder()
                            .index(idx)
                            .id(id)
                            .name(name)
                            .argumentsChunk(argsChunk)
                            .build());
                }
                b.deltaToolCalls(deltas);
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
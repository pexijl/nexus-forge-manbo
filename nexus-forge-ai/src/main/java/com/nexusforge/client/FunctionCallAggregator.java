package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.DeltaToolCall;
import com.nexusforge.ai.ToolCall;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式 ChatChunk 中 {@code delta.tool_calls} 聚合为完整 {@link ToolCall}。
 *
 * <p>OpenAI 流式协议(SSE data 帧)按以下规则拆 tool call:
 * <ul>
 *   <li>同一帧内的多个并行 tool call 用 {@code index} 字段标识顺序</li>
 *   <li>同一 index 的多帧之间,首帧携带完整 {@code id} / {@code name},
 *       后续帧仅 {@code function.arguments} 字段非空(JSON 字符串分片,逐帧拼接)</li>
 *   <li>终止帧({@code finishReason == "tool_calls"})不带 {@code delta.tool_calls} 字段</li>
 * </ul>
 *
 * <p>本聚合器消费流式 {@link ChatChunk},按 index 分桶合并:
 * <ul>
 *   <li>中间帧({@code deltaContent} 或 {@code deltaToolCalls} 非空):原样透传,
 *       同时把 {@code deltaToolCalls[]} 累积到内部 builder 桶</li>
 *   <li>终止帧({@code finishReason == "tool_calls"}):把累积结果物化为 {@link ToolCall}
 *       列表,emit 一个新的 {@link ChatChunk},{@code finishReason} 字段不变,
 *       但额外带上 {@code toolCalls=[...]} 字段,流消费方据此触发工具执行</li>
 *   <li>其它终止帧(stop / length):原样透传,即使之前累积了未完成的 tool_calls,
 *       也丢弃 —— 模型主动选择非 tool_calls 结束,前面的累积语义作废</li>
 *   <li>流 complete 但没收到终止帧(网络中断 / 客户端取消):丢弃累积,不下发
 *       —— 与 {@code Flux.error} 透传语义一致</li>
 * </ul>
 *
 * <p>状态机:每个上游订阅独立持有一份 {@code Map<Integer, Builder>},由
 * {@code Flux.create} 的 sink scope 保证并发安全;{@code ObjectMapper} 用于
 * 把拼好的 arguments 字符串 parse 成 {@link JsonNode}。
 */
@Component
public class FunctionCallAggregator {

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 包裹一个上游 {@link Flux<ChatChunk>},聚合 {@code delta.tool_calls} 后输出。
     * 输出帧数 ≥ 输入帧数(终止帧可能被替换为聚合后的版本,但仍算一帧)。
     */
    public Flux<ChatChunk> aggregate(Flux<ChatChunk> upstream) {
        return Flux.create(sink -> {
            // 用 LinkedHashMap 保 index → Builder 顺序 = OpenAI 顺序
            Map<Integer, Builder> builders = new LinkedHashMap<>();
            // 用 AtomicReference 让 lambda 能 mutate(Flux.create 的 subscribe lambda 不是 effectively-final 友好的)
            java.util.concurrent.atomic.AtomicReference<String> currentId = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> currentModel = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean toolCallStream = new java.util.concurrent.atomic.AtomicBoolean(false);

            upstream.subscribe(
                    chunk -> {
                        if (chunk == null) {
                            return;
                        }
                        // 1. 抓取 id/model(优先用本次 chunk 的,否则沿用上一个)
                        if (chunk.getId() != null) currentId.set(chunk.getId());
                        if (chunk.getModel() != null) currentModel.set(chunk.getModel());

                        // 2. 累积 delta.tool_calls
                        List<DeltaToolCall> deltas = chunk.getDeltaToolCalls();
                        if (deltas != null && !deltas.isEmpty()) {
                            toolCallStream.set(true);
                            for (DeltaToolCall d : deltas) {
                                accumulateDelta(d, builders);
                            }
                        }

                        // 3. 终止帧处理
                        String fr = chunk.getFinishReason();
                        if ("tool_calls".equals(fr)) {
                            // 即使本帧没带 deltaToolCalls(常见情况),也用累积结果物化
                            List<ToolCall> completed = buildAll(builders);
                            ChatChunk out = ChatChunk.builder()
                                    .id(currentId.get())
                                    .model(currentModel.get())
                                    .finishReason("tool_calls")
                                    .toolCalls(completed)
                                    .usage(chunk.getUsage())
                                    .build();
                            sink.next(out);
                        } else if (fr != null) {
                            // 其它终止原因(stop / length / content_filter),丢弃累积,透传
                            toolCallStream.set(false);
                            builders.clear();
                            sink.next(chunk);
                        } else {
                            // 中间帧:deltaContent / deltaToolCalls 都可能存在
                            sink.next(chunk);
                        }
                    },
                    sink::error,
                    () -> {
                        // 流正常 complete —— 不主动 emit 累积;调用方需要 finishReason 才知道该终止了。
                        // 如果是上游断开导致缺失终止帧,这里仅记录标志,不强制补帧(语义对齐 Flux.error)。
                        if (toolCallStream.get() && !builders.isEmpty()) {
                            // 上游模型可能在 finish_reason 之后又发了空帧 complete —— 物化累积
                            // 不常见,防御性写:不下发,避免误触发
                        }
                        sink.complete();
                    });
        });
    }

    private void accumulateDelta(DeltaToolCall d, Map<Integer, Builder> builders) {
        // OpenAI 协议规定 index 必填,但 Anthropic / 其他兼容实现可能省略,
        // 这里把 null index 当作 0 处理,避免漏帧。
        Integer idx = d.getIndex() == null ? 0 : d.getIndex();
        Builder b = builders.computeIfAbsent(idx, k -> new Builder());
        if (d.getId() != null) b.id = d.getId();
        if (d.getName() != null) b.name = d.getName();
        if (d.getArgumentsChunk() != null) b.argsBuf.append(d.getArgumentsChunk());
    }

    private List<ToolCall> buildAll(Map<Integer, Builder> builders) {
        if (builders.isEmpty()) return List.of();
        List<ToolCall> out = new ArrayList<>(builders.size());
        for (Builder b : builders.values()) {
            out.add(b.build(json));
        }
        return out;
    }

    /**
     * 单个 tool call 的增量桶。{@code argsBuf} 逐帧拼接完整 JSON 字符串,
     * build 时一次性 readTree 还原为 {@link JsonNode};parse 失败兜底为
     * {@link NullNode}(不抛错,避免一个 tool call 格式异常炸掉整条流)。
     */
    private static final class Builder {
        String id;
        String name = "";
        StringBuilder argsBuf = new StringBuilder();

        ToolCall build(ObjectMapper json) {
            JsonNode args;
            String a = argsBuf.toString();
            if (a.isEmpty()) {
                args = NullNode.getInstance();
            } else {
                try {
                    args = json.readTree(a);
                } catch (Exception e) {
                    args = NullNode.getInstance();
                }
            }
            return ToolCall.builder().id(id).name(name).arguments(args).build();
        }
    }
}
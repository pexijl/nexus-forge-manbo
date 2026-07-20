package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.DeltaToolCall;
import com.nexusforge.ai.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FunctionCallAggregator 状态机单元测试。
 *
 * <p>P4 Step 11 覆盖范围:
 * <ul>
 *   <li>纯文本流(无 deltaToolCalls):中间帧 + 终止帧原样透传</li>
 *   <li>单 tool call 流:id/name/多帧 arguments 拼接,终止帧携带 toolCalls</li>
 *   <li>并行 tool calls:按 index 排序输出完整列表</li>
 *   <li>arguments 非 JSON:兜底 NullNode,不抛错</li>
 *   <li>其它终止帧(stop):累积被丢弃,只透传终止帧</li>
 *   <li>空 deltaToolCalls 终止帧:仍 emit toolCalls(累积结果)</li>
 *   <li>Flux.error:错误透传,不下发 toolCalls</li>
 *   <li>null chunk / null index:防御性跳过 / 兜底 0</li>
 * </ul>
 */
@DisplayName("FunctionCallAggregator")
class FunctionCallAggregatorTest {

    private final FunctionCallAggregator aggregator = new FunctionCallAggregator();

    // ──────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────

    private static ChatChunk text(String id, String content) {
        return ChatChunk.builder().id(id).model("m").deltaContent(content).build();
    }

    private static ChatChunk finish(String id, String finishReason) {
        return ChatChunk.builder().id(id).model("m").finishReason(finishReason).build();
    }

    private static ChatChunk delta(String id, DeltaToolCall... deltas) {
        return ChatChunk.builder()
                .id(id).model("m")
                .deltaToolCalls(List.of(deltas))
                .build();
    }

    private static DeltaToolCall d(Integer index, String id, String name, String args) {
        return DeltaToolCall.builder()
                .index(index).id(id).name(name).argumentsChunk(args)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Passthrough
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("纯文本流透传")
    class TextPassthrough {

        @Test
        @DisplayName("text-only 流:中间帧 + 终止帧原样透传,不动 finishReason")
        void text_only_stream_passes_through() {
            Flux<ChatChunk> upstream = Flux.just(
                    text("c1", "你好,"),
                    text("c1", "世界"),
                    finish("c1", "stop"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaContent()).isEqualTo("你好,"))
                    .assertNext(c -> assertThat(c.getDeltaContent()).isEqualTo("世界"))
                    .assertNext(c -> {
                        assertThat(c.getFinishReason()).isEqualTo("stop");
                        assertThat(c.getToolCalls()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("length 终止帧:累积被丢弃(没累积但逻辑仍要正确)")
        void length_finish_drops_accumulation() {
            Flux<ChatChunk> upstream = Flux.just(
                    delta("c1", d(0, "x", "fn", "{\"a\":")),
                    finish("c1", "length"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(1))
                    .assertNext(c -> {
                        assertThat(c.getFinishReason()).isEqualTo("length");
                        // 关键断言:length 终止帧不应触发 toolCalls 列表 emit
                        assertThat(c.getToolCalls()).isNull();
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Single tool call
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("单 tool call 聚合")
    class SingleToolCall {

        @Test
        @DisplayName("id/name 首帧 + arguments 跨帧拼接 → 终止帧 toolCalls=[1]")
        void single_tool_call_aggregated_across_frames() {
            Flux<ChatChunk> upstream = Flux.just(
                    delta("c1", d(0, "call_1", "get_weather", "{\"city\":")),
                    delta("c1", d(0, null, null, "\"Beijing\"}")),
                    finish("c1", "tool_calls"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(1))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(1))
                    .assertNext(c -> {
                        assertThat(c.getFinishReason()).isEqualTo("tool_calls");
                        assertThat(c.getToolCalls()).hasSize(1);
                        ToolCall tc = c.getToolCalls().get(0);
                        assertThat(tc.getId()).isEqualTo("call_1");
                        assertThat(tc.getName()).isEqualTo("get_weather");
                        assertThat(tc.getArguments().get("city").asString()).isEqualTo("Beijing");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("id/model 在终止帧才出现也被吸收到 toolCalls 帧")
        void id_propagates_to_final_frame() {
            Flux<ChatChunk> upstream = Flux.just(
                    // deltaToolCalls 帧没有 id/model
                    delta(null, d(0, "abc", "fn", "{}")),
                    // 终止帧带 id/model
                    ChatChunk.builder().id("c1").model("gpt-4o").finishReason("tool_calls").build());

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(1))
                    .assertNext(c -> {
                        assertThat(c.getId()).isEqualTo("c1");
                        assertThat(c.getModel()).isEqualTo("gpt-4o");
                        assertThat(c.getToolCalls()).hasSize(1);
                        assertThat(c.getToolCalls().get(0).getId()).isEqualTo("abc");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("空 arguments:终止帧 toolCall.arguments = NullNode")
        void empty_arguments_yields_null_node() {
            Flux<ChatChunk> upstream = Flux.just(
                    delta("c1", d(0, "abc", "no_args", null)),
                    finish("c1", "tool_calls"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(1))
                    .assertNext(c -> {
                        ToolCall tc = c.getToolCalls().get(0);
                        assertThat(tc.getId()).isEqualTo("abc");
                        assertThat(tc.getName()).isEqualTo("no_args");
                        // arguments 是 NullNode(用 isNull() 判断)
                        assertThat(tc.getArguments()).isNotNull();
                        assertThat(tc.getArguments().isNull()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Parallel tool calls
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("并行 tool calls 按 index 排序")
    class ParallelToolCalls {

        @Test
        @DisplayName("两个并行 tool call(不同 index):按 index 升序输出")
        void two_parallel_tool_calls_ordered_by_index() {
            Flux<ChatChunk> upstream = Flux.just(
                    // 同一帧里两个 index
                    delta("c1",
                            d(0, "call_A", "get_weather", "{\"city\":\"BJ\"}"),
                            d(1, "call_B", "get_time", "{}")),
                    finish("c1", "tool_calls"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(2))
                    .assertNext(c -> {
                        assertThat(c.getToolCalls()).hasSize(2);
                        assertThat(c.getToolCalls().get(0).getId()).isEqualTo("call_A");
                        assertThat(c.getToolCalls().get(0).getName()).isEqualTo("get_weather");
                        assertThat(c.getToolCalls().get(1).getId()).isEqualTo("call_B");
                        assertThat(c.getToolCalls().get(1).getName()).isEqualTo("get_time");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("跨帧 index=0 / index=1 分别累积,终止帧输出顺序 0,1")
        void cross_frame_index_split() {
            Flux<ChatChunk> upstream = Flux.just(
                    delta("c1", d(0, "call_A", "fnA", "{\"a\":")),
                    delta("c1", d(1, "call_B", "fnB", "{\"b\":")),
                    delta("c1", d(0, null, null, "1}")),
                    delta("c1", d(1, null, null, "2}")),
                    finish("c1", "tool_calls"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .expectNextCount(4)   // 4 个 delta 帧原样透传
                    .assertNext(c -> {
                        List<ToolCall> calls = c.getToolCalls();
                        assertThat(calls).hasSize(2);
                        assertThat(calls.get(0).getId()).isEqualTo("call_A");
                        assertThat(calls.get(0).getArguments().get("a").asInt()).isEqualTo(1);
                        assertThat(calls.get(1).getId()).isEqualTo("call_B");
                        assertThat(calls.get(1).getArguments().get("b").asInt()).isEqualTo(2);
                    })
                    .verifyComplete();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Edge cases
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("arguments 不是合法 JSON:兜底 NullNode,toolCall 仍发出")
        void malformed_arguments_falls_back_to_null_node() {
            Flux<ChatChunk> upstream = Flux.just(
                    delta("c1", d(0, "abc", "fn", "{not valid json")),
                    finish("c1", "tool_calls"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(1))
                    .assertNext(c -> {
                        ToolCall tc = c.getToolCalls().get(0);
                        assertThat(tc.getId()).isEqualTo("abc");
                        assertThat(tc.getName()).isEqualTo("fn");
                        // parse 失败 → NullNode
                        JsonNode args = tc.getArguments();
                        assertThat(args).isNotNull();
                        assertThat(args.isNull()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("终止帧没带 deltaToolCalls,但累积存在:仍 emit 累积结果")
        void finish_frame_without_deltas_still_emits_aggregated() {
            // OpenAI 实际就是这种:终止帧 delta.content=""、delta.tool_calls 不存在,
            // 仅靠 finishReason=tool_calls 触发。
            Flux<ChatChunk> upstream = Flux.just(
                    delta("c1", d(0, "abc", "fn", "{\"x\":1}")),
                    ChatChunk.builder().id("c1").model("m").finishReason("tool_calls").build());

            StepVerifier.create(aggregator.aggregate(upstream))
                    .expectNextCount(1)
                    .assertNext(c -> {
                        assertThat(c.getFinishReason()).isEqualTo("tool_calls");
                        assertThat(c.getToolCalls()).hasSize(1);
                        assertThat(c.getToolCalls().get(0).getArguments().get("x").asInt()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("上游 error:error 透传,不下发 toolCalls 帧")
        void upstream_error_propagates() {
            Flux<ChatChunk> upstream = Flux.concat(
                    Flux.just(delta("c1", d(0, "abc", "fn", "{}"))),
                    Flux.error(new RuntimeException("upstream died")));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .expectNextCount(1)
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("空流:complete 直接透传")
        void empty_stream_completes_immediately() {
            Flux<ChatChunk> upstream = Flux.empty();

            StepVerifier.create(aggregator.aggregate(upstream))
                    .verifyComplete();
        }

        @Test
        @DisplayName("null chunk 防御:跳过不崩(Flux.just 不收 null,用 Mono.justOrEmpty 包装)")
        void null_chunk_is_skipped() {
            Flux<ChatChunk> upstream = Flux.concat(
                    Mono.justOrEmpty((ChatChunk) null),
                    Flux.just(text("c1", "ok")),
                    Flux.just(finish("c1", "stop")));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaContent()).isEqualTo("ok"))
                    .assertNext(c -> assertThat(c.getFinishReason()).isEqualTo("stop"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("null index 兜底为 0:避免丢帧")
        void null_index_falls_back_to_zero() {
            Flux<ChatChunk> upstream = Flux.just(
                    delta("c1", d(null, "abc", "fn", "{}")),
                    finish("c1", "tool_calls"));

            StepVerifier.create(aggregator.aggregate(upstream))
                    .assertNext(c -> assertThat(c.getDeltaToolCalls()).hasSize(1))
                    .assertNext(c -> {
                        assertThat(c.getToolCalls()).hasSize(1);
                        assertThat(c.getToolCalls().get(0).getId()).isEqualTo("abc");
                    })
                    .verifyComplete();
        }
    }
}
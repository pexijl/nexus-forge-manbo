package com.nexusforge.client;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolCall;
import com.nexusforge.ai.tools.EchoTool;
import com.nexusforge.config.AiProperties;
import com.nexusforge.model.ChatCapabilities;
import com.nexusforge.model.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmClient#callWithToolLoop} 闭环行为。
 *
 * <p>锁住契约:
 * <ul>
 *   <li>首调 {@code finishReason="tool_calls"} + toolCalls 非空 → 执行工具、回灌 TOOL 消息、再调一次</li>
 *   <li>第二轮返回 {@code finishReason="stop"} → 直接返回最终响应,不无限循环</li>
 *   <li>首调即 {@code finishReason="stop"} → 不进入循环,只调 1 次</li>
 *   <li>回灌消息形态:{@code role=ASSISTANT, toolCalls=[...]} + {@code role=TOOL, name=<id>, content=<result>}</li>
 * </ul>
 *
 * <p>用私 Key 路径调 {@code callWithToolLoop(req, privateModel, registry, maxTurns)},
 * 不依赖 {@code ChatModelRouter} —— router 不出现在循环体内,所以测试里 {@code router=null}。
 * 模型用仿 {@code LlmClientFallbackTest.ScriptedChatModel} 的手写 stub。
 */
class LlmClientToolLoopTest {

    /**
     * 可编程 ChatModel stub:每次 call 消费 script 队列里的下一个 ChatResponse,
     * 并把每次收到的 request 追加到 {@link #requestHistory}。
     * 不支持 stream(本测试只覆盖同步路径)。
     */
    static final class ScriptedChatModel implements ChatModel {
        final String vendorName;
        final Deque<ChatResponse> script = new ArrayDeque<>();
        final List<ChatRequest> requestHistory = new ArrayList<>();
        int callCount = 0;

        ScriptedChatModel(String vendorName) { this.vendorName = vendorName; }

        ScriptedChatModel enqueue(ChatResponse step) { script.add(step); return this; }

        @Override public String name() { return vendorName; }

        @Override public ChatCapabilities capabilities() {
            return ChatCapabilities.builder().stream(true).tools(true).build();
        }

        @Override public ChatResponse call(ChatRequest request) {
            requestHistory.add(request);
            this.callCount++;
            ChatResponse step = script.poll();
            if (step == null) {
                throw new IllegalStateException("script empty for call #" + callCount);
            }
            return step;
        }

        @Override public Flux<ChatChunk> stream(ChatRequest request) {
            throw new UnsupportedOperationException("sync-only test stub");
        }
    }

    private ScriptedChatModel model;
    private AiProperties props;
    private LlmClient client;

    @BeforeEach
    void setup() {
        model = new ScriptedChatModel("openai");
        props = new AiProperties();
        // router=null —— 私 Key 路径不碰 router
        client = new LlmClient(null, props);
    }

    private ChatRequest req(String content) {
        return ChatRequest.builder()
                .model("openai:gpt-4o")
                .messages(List.of(ChatMessage.builder()
                        .role(Role.USER)
                        .content(content)
                        .build()))
                .build();
    }

    private ToolCall toolCall(String id, String name, JsonNode args) {
        return ToolCall.builder().id(id).name(name).arguments(args).build();
    }

    @Test
    @DisplayName("工具调用 → 回灌 → 再调 → 终止:循环跑一轮,TOOL 消息正确回灌")
    void loop_executes_tool_then_terminates() {
        ObjectMapper json = new ObjectMapper();
        ToolCall tc = toolCall("call_abc", "echo", json.createObjectNode().put("x", 1));

        // 第 1 次:模型选择调用 echo 工具
        model.enqueue(ChatResponse.builder()
                .id("resp-1")
                .model("gpt-4o-mini")
                .content(null)
                .finishReason("tool_calls")
                .toolCalls(List.of(tc))
                .build());
        // 第 2 次:模型返回最终答案
        model.enqueue(ChatResponse.builder()
                .id("resp-2")
                .model("gpt-4o-mini")
                .content("echo said hi")
                .finishReason("stop")
                .build());

        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));
        ChatRequest original = req("hello");

        ChatResponse resp = client.callWithToolLoop(original, model, registry, 3);

        // 返回最终响应
        assertThat(resp.getContent()).isEqualTo("echo said hi");
        assertThat(resp.getFinishReason()).isEqualTo("stop");
        // 模型被调用 2 次
        assertThat(model.callCount).isEqualTo(2);

        // 第 2 次收到的 request 应该多了 2 条消息:ASSISTANT(tool_calls) + TOOL(result)
        ChatRequest secondReq = model.requestHistory.get(1);
        assertThat(secondReq.getMessages()).hasSize(3);
        // [0] USER 原消息
        assertThat(secondReq.getMessages().get(0).getRole()).isEqualTo(Role.USER);
        assertThat(secondReq.getMessages().get(0).getContent()).isEqualTo("hello");
        // [1] ASSISTANT 带 tool_calls
        ChatMessage assistantMsg = secondReq.getMessages().get(1);
        assertThat(assistantMsg.getRole()).isEqualTo(Role.ASSISTANT);
        assertThat(assistantMsg.getToolCalls()).hasSize(1);
        assertThat(assistantMsg.getToolCalls().get(0).getName()).isEqualTo("echo");
        assertThat(assistantMsg.getToolCalls().get(0).getId()).isEqualTo("call_abc");
        // [2] TOOL 回灌 echo 结果
        ChatMessage toolMsg = secondReq.getMessages().get(2);
        assertThat(toolMsg.getRole()).isEqualTo(Role.TOOL);
        assertThat(toolMsg.getName()).isEqualTo("call_abc");
        assertThat(toolMsg.getContent()).isEqualTo("echo: {\"x\":1}");

        // 原始 request 不被 mutate(循环体里用的是副本)
        assertThat(original.getMessages()).hasSize(1);
    }

    @Test
    @DisplayName("首调即 stop → 不进入循环,只调 1 次,request 原样透传")
    void no_loop_when_first_response_is_stop() {
        model.enqueue(ChatResponse.builder()
                .id("resp-1")
                .model("gpt-4o-mini")
                .content("direct answer")
                .finishReason("stop")
                .build());

        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));
        ChatRequest original = req("hi");

        ChatResponse resp = client.callWithToolLoop(original, model, registry, 3);

        assertThat(resp.getContent()).isEqualTo("direct answer");
        assertThat(resp.getFinishReason()).isEqualTo("stop");
        assertThat(model.callCount).isEqualTo(1);
        assertThat(model.requestHistory).hasSize(1);
        ChatRequest seen = model.requestHistory.get(0);
        assertThat(seen.getMessages()).hasSize(1);
        assertThat(seen.getMessages().get(0).getRole()).isEqualTo(Role.USER);
        assertThat(seen.getMessages().get(0).getContent()).isEqualTo("hi");
    }
}

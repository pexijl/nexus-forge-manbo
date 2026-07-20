package com.nexusforge.provider.openai;

import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.ChatUsage;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolCall;
import com.nexusforge.ai.ToolDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI ⇄ ChatRequest/ChatResponse 之间的 JSON 转换。
 *
 * <p>兼容所有 OpenAI-compatible 端点(Ollama/DeepSeek/Qwen 等,P4 复用)。
 *
 * <p>P4 扩展点:
 * <ul>
 *   <li>{@code toOpenAi} 序列化 {@code tools} 数组、{@code tool_choice} 与
 *       {@code parallel_tool_calls}</li>
 *   <li>{@code toOpenAiMessage} 在 assistant 消息携带 tool_calls 时一并序列化,
 *       tool 角色消息透传 {@code name} 字段(tool_call_id)</li>
 *   <li>{@code fromOpenAi} 从同步响应 message.tool_calls 提取完整 ToolCall 列表,
 *       并把 finish_reason=tool_calls 透传</li>
 * </ul>
 *
 * <p>流式 tool_calls 的 delta 解析在 {@link com.nexusforge.stream.OpenAiStreamParser},
 * 聚合逻辑由 FunctionCallAggregator(P4 Step 11)负责。
 */
@Component
@RequiredArgsConstructor
public class OpenAiJsonMapper {

    private final ObjectMapper json;

    public OpenAiRequestBody toOpenAi(ChatRequest req, String providerDefaultModel) {
        OpenAiRequestBody body = new OpenAiRequestBody();
        body.model = providerDefaultModel;
        body.stream = Boolean.TRUE.equals(req.getStream());
        body.temperature = req.getTemperature();
        body.max_tokens = req.getMaxTokens();
        body.messages = req.getMessages().stream().map(this::toOpenAiMessage).toList();
        // P4:tools / tool_choice / parallel_tool_calls
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            body.tools = req.getTools().stream().map(this::toOpenAiTool).toList();
            body.tool_choice = pickToolChoice(req);
            body.parallel_tool_calls = pickParallelToolCalls(req);
        }
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
        // P4:同步响应里的 tool_calls
        JsonNode tc = choice0.path("message").path("tool_calls");
        if (tc.isArray() && !tc.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode one : tc) {
                calls.add(ToolCall.builder()
                        .id(one.path("id").asString())
                        .name(one.path("function").path("name").asString())
                        .arguments(parseArgs(one.path("function").path("arguments")))
                        .build());
            }
            b.toolCalls(calls);
        }
        if ("tool_calls".equals(choice0.path("finish_reason").asString())) {
            b.finishReason("tool_calls");
        }
        return b.build();
    }

    private OpenAiMessage toOpenAiMessage(ChatMessage m) {
        OpenAiMessage om = new OpenAiMessage();
        om.role = m.getRole().name().toLowerCase();
        om.content = m.getContent();
        // P4:assistant 消息携带 tool_calls 时一并序列化
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            om.tool_calls = new ArrayList<>();
            for (ToolCall tc : m.getToolCalls()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tc.getName());
                // arguments 是 JsonNode;OpenAI 协议此处接受字符串,
                // history 序列化为字符串更稳(增量传输特性)
                String argsStr = tc.getArguments() == null
                        ? "{}"
                        : tc.getArguments().toString();
                fn.put("arguments", argsStr);
                Map<String, Object> tcMap = new LinkedHashMap<>();
                tcMap.put("id", tc.getId());
                tcMap.put("type", "function");
                tcMap.put("function", fn);
                om.tool_calls.add(tcMap);
            }
        }
        // P4:tool 角色消息携带 tool_call_id(以 name 字段承载)
        if (m.getRole() == Role.TOOL && m.getName() != null) {
            om.name = m.getName();
        }
        return om;
    }

    /** OpenAI tools 项: {type: "function", function: {name, description, parameters}} */
    private Map<String, Object> toOpenAiTool(ToolDefinition t) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", t.getName());
        fn.put("description", t.getDescription());
        // parameters 是 JsonNode,序列化为原生 JSON 对象
        fn.put("parameters", t.getParameters());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    /** {@code tool_choice}:默认 "auto",可由 options.tool_choice 覆盖。 */
    private Object pickToolChoice(ChatRequest req) {
        Object v = req.getOptions() == null ? null : req.getOptions().get("tool_choice");
        return v != null ? v : "auto";
    }

    /** {@code parallel_tool_calls}:默认 true,可在 options 里关。 */
    private boolean pickParallelToolCalls(ChatRequest req) {
        Object v = req.getOptions() == null ? null : req.getOptions().get("parallel_tool_calls");
        if (v instanceof Boolean b) return b;
        return true;
    }

    /**
     * OpenAI 在 tool_calls[].function.arguments 段是 JSON 字符串,
     * 我方统一用 JsonNode,这里 readTree 转成节点;空 / 非 JSON 字符串兜底为 NullNode。
     */
    private JsonNode parseArgs(JsonNode raw) {
        if (raw.isNull()) return null;
        if (raw.isString()) {
            String s = raw.asString();
            if (s == null || s.isEmpty()) return null;
            try {
                return json.readTree(s);
            } catch (Exception e) {
                // 历史上下文里 arguments 字符串可能损坏,保底返回对象节点避免业务 NPE
                ObjectNode fallback = json.createObjectNode();
                fallback.put("__raw__", s);
                return fallback;
            }
        }
        return raw;
    }

    /* --- DTOs --- */
    public static class OpenAiRequestBody {
        public String model;
        public boolean stream;
        public Double temperature;
        public Integer max_tokens;
        public List<OpenAiMessage> messages;
        // P4 fields:
        public List<Map<String, Object>> tools;
        public Object tool_choice;          // "auto" | "none" | "required" | {type, function}
        public Boolean parallel_tool_calls;
    }

    public static class OpenAiMessage {
        public String role;
        public String content;
        // P4:assistant 携带的 tool_calls
        public List<Map<String, Object>> tool_calls;
        // P4:tool 角色消息的 tool_call_id
        public String name;
    }
}

package com.nexusforge.provider.anthropic;

import com.nexusforge.ai.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Anthropic Messages API ⇄ ChatRequest/ChatResponse 之间的 JSON 转换。
 *
 * <p>差异点(对 OpenAI):
 * <ul>
 *   <li>system 消息从 messages 中抽出,放到 body.system 顶层字段</li>
 *   <li>每条消息的 content 强制为数组 [{type: "text", text: "..."}]</li>
 *   <li>tools[].input_schema 替代 tools[].function.parameters</li>
 *   <li>tool 响应角色是 user + content=[{type: "tool_result", ...}],
 *       不是我方的 role=tool</li>
 * </ul>
 */
public class AnthropicJsonMapper {

    private final ObjectMapper json;

    public AnthropicJsonMapper(ObjectMapper json) {
        this.json = json;
    }

    public AnthropicRequestBody toAnthropic(ChatRequest req, String defaultModel) {
        AnthropicRequestBody b = new AnthropicRequestBody();
        b.model = defaultModel;
        b.max_tokens = req.getMaxTokens() == null ? 4096 : req.getMaxTokens();
        b.temperature = req.getTemperature();
        b.stream = Boolean.TRUE.equals(req.getStream());
        // 拆分 system / 其余消息
        String systemText = null;
        List<ChatMessage> nonSystem = new ArrayList<>();
        for (ChatMessage m : req.getMessages()) {
            if (m.getRole() == Role.SYSTEM) {
                systemText = (systemText == null ? "" : systemText + "\n") + m.getContent();
            } else {
                nonSystem.add(m);
            }
        }
        b.system = systemText;
        b.messages = nonSystem.stream().map(this::toAnthropicMessage).toList();
        if (req.getTools() != null && !req.getTools().isEmpty()) {
            b.tools = req.getTools().stream().map(this::toAnthropicTool).toList();
        }
        return b;
    }

    public ChatResponse fromAnthropic(JsonNode body, long latencyMillis) {
        ChatResponse.ChatResponseBuilder b = ChatResponse.builder()
                .id(body.path("id").asString())
                .model(body.path("model").asString())
                .latencyMillis(latencyMillis)
                .finishReason(mapStopReason(body.path("stop_reason").asString("end_turn")));
        // 收集 content 数组里所有 text 块
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : body.path("content")) {
            String type = block.path("type").asString();
            if ("text".equals(type)) {
                if (!text.isEmpty()) text.append('\n');
                text.append(block.path("text").asString());
            } else if ("tool_use".equals(type)) {
                calls.add(ToolCall.builder()
                        .id(block.path("id").asString())
                        .name(block.path("name").asString())
                        .arguments(block.path("input"))
                        .build());
            }
        }
        b.content(text.toString());
        if (!calls.isEmpty()) b.toolCalls(calls);
        JsonNode usage = body.path("usage");
        if (!usage.isMissingNode()) {
            b.usage(ChatUsage.builder()
                    .promptTokens(usage.path("input_tokens").asInt())
                    .completionTokens(usage.path("output_tokens").asInt())
                    .totalTokens(usage.path("input_tokens").asInt() + usage.path("output_tokens").asInt())
                    .build());
        }
        return b.build();
    }

    private Map<String, Object> toAnthropicMessage(ChatMessage m) {
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("role", m.getRole() == Role.ASSISTANT ? "assistant" : "user");
        List<Map<String, Object>> content = new ArrayList<>();
        if (m.getRole() == Role.TOOL) {
            // tool 响应:我方 role=tool,Anthropic 期望 user + tool_result
            Map<String, Object> tr = new LinkedHashMap<>();
            tr.put("type", "tool_result");
            tr.put("tool_use_id", m.getName() == null ? "" : m.getName());
            tr.put("content", m.getContent() == null ? "" : m.getContent());
            content.add(tr);
        } else if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            // assistant 触发 tool_use
            for (ToolCall tc : m.getToolCalls()) {
                Map<String, Object> tu = new LinkedHashMap<>();
                tu.put("type", "tool_use");
                tu.put("id", tc.getId());
                tu.put("name", tc.getName());
                tu.put("input", tc.getArguments() == null
                        ? json.createObjectNode() : tc.getArguments());
                content.add(tu);
            }
        } else {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", "text");
            t.put("text", m.getContent() == null ? "" : m.getContent());
            content.add(t);
        }
        am.put("content", content);
        return am;
    }

    private Map<String, Object> toAnthropicTool(ToolDefinition t) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", t.getName());
        tool.put("description", t.getDescription());
        tool.put("input_schema", t.getParameters() == null
                ? json.createObjectNode() : t.getParameters());
        return tool;
    }

    private static String mapStopReason(String reason) {
        return switch (reason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "stop_sequence" -> "stop";
            default -> reason.isEmpty() ? "stop" : reason;
        };
    }

    /* --- DTOs --- */
    public static class AnthropicRequestBody {
        public String model;
        public String system;
        public Integer max_tokens;
        public Double temperature;
        public boolean stream;
        public List<Map<String, Object>> messages;
        public List<Map<String, Object>> tools;
    }

    static JsonNode parseArgs(JsonNode raw) {
        if (raw == null || raw.isNull()) return null;
        if (raw.isString()) {
            try { return new ObjectMapper().readTree(raw.asString()); }
            catch (Exception e) { return raw; }
        }
        return raw;
    }
}
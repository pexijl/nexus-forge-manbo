package com.nexusforge.ai.jackson;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.jackson.JacksonComponent;
import org.springframework.boot.jackson.ObjectValueDeserializer;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;

/**
 * /api/ai/chat 请求体 {@code List<Message>} 的多态反序列化器。
 *
 * <p>Spring AI 2.0 的 {@link Message} 是抽象接口,Jackson 3
 * (Spring Boot 4.x 默认 JSON 栈,见
 * {@code spring-boot-jackson:4.1.0} → {@code tools.jackson.core:jackson-databind:3.1.4})
 * 默认无法为接口构造实例 —— Spring AI 2.0 也并未自带
 * {@code MessageTypeDeserializer}(已通过反编译
 * {@code spring-ai-model-2.0.0.jar} 确认,jar 内只有 {@code Message} 接口
 * 及其具体子类,无任何对应反序列化器)。因此本类手动按 JSON 的
 * {@code role} 字段路由到具体子类。
 *
 * <p>挂 {@link JacksonComponent} 后,Spring Boot 4.x 的
 * {@code JacksonComponentModule}(也是
 * {@code tools.jackson.databind.module.SimpleModule} 子类)会在
 * {@code JsonMapper} 装配阶段扫描本 bean 并注册到
 * {@code Message.class} 这一 typeId 下,Spring MVC 的
 * {@code MappingJackson*HttpMessageConverter}(boot 4.x 默认
 * Jackson 3 {@code JsonMapper})即会用到它。
 *
 * <p>wire 格式与 OpenAI Chat Completions 一致:
 * <ul>
 *   <li>{@code {"role":"user","content":"..."}} → {@link UserMessage}</li>
 *   <li>{@code {"role":"system","content":"..."}} → {@link SystemMessage}</li>
 *   <li>{@code {"role":"assistant","content":"...","tool_calls":[...]}}
 *       → {@link AssistantMessage};若含 {@code tool_calls},通过
 *       {@code AssistantMessage.builder()} 注入 ——
 *       wire 中 {@code tool_calls[i].function.{name,arguments}}
 *       嵌套结构,本类负责扁平化为 Spring AI 的
 *       {@link AssistantMessage.ToolCall} record。</li>
 *   <li>{@code {"role":"tool","tool_call_id":"...","name":"...","content":"..."}}
 *       → {@link ToolResponseMessage}(OpenAI:tool 输出回传)。</li>
 * </ul>
 *
 * <p>{@code tool_calls[i].arguments} 在 OpenAI wire 是 JSON 字符串
 * (e.g. {@code "{\"location\":\"SF\"}"})—— 直接当 String 透传,
 * 不在反序列化层解析,留给 {@code DefaultToolCallingManager} 执行时
 * 解析。
 */
@JacksonComponent(type = Message.class)
public class MessageJacksonDeserializer extends ObjectValueDeserializer<Message> {

    @Override
    protected Message deserializeObject(JsonParser parser, DeserializationContext context,
                                        JsonNode node) {
        JsonNode roleNode = node.get("role");
        if (roleNode == null || !roleNode.isString()) {
            throw new IllegalArgumentException(
                    "Message JSON requires string 'role' field, got: " + node);
        }
        String role = roleNode.stringValue().toLowerCase();
        String content = stringOrEmpty(node.get("content"));

        return switch (role) {
            case "user" -> new UserMessage(content);
            case "system" -> new SystemMessage(content);
            case "assistant" -> buildAssistant(content, node);
            case "tool" -> buildToolResponse(node);
            default -> throw new IllegalArgumentException(
                    "Unknown Message role: '" + role
                            + "' (expected one of: user, system, assistant, tool)");
        };
    }

    private static AssistantMessage buildAssistant(String content, JsonNode node) {
        JsonNode toolCallsNode = node.get("tool_calls");
        if (toolCallsNode == null || !toolCallsNode.isArray() || toolCallsNode.size() == 0) {
            return new AssistantMessage(content);
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : toolCallsNode) {
            JsonNode function = tc.get("function");
            String name = function == null ? null : stringOrNull(function.get("name"));
            String arguments = function == null ? null : stringOrNull(function.get("arguments"));
            calls.add(new AssistantMessage.ToolCall(
                    stringOrNull(tc.get("id")),
                    stringOrDefault(tc.get("type"), "function"),
                    name,
                    arguments));
        }
        return AssistantMessage.builder()
                .content(content)
                .toolCalls(calls)
                .build();
    }

    private static ToolResponseMessage buildToolResponse(JsonNode node) {
        String toolCallId = stringOrNull(node.get("tool_call_id"));
        if (toolCallId == null) {
            throw new IllegalArgumentException(
                    "Tool message requires 'tool_call_id' field, got: " + node);
        }
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                toolCallId,
                stringOrNull(node.get("name")),
                stringOrEmpty(node.get("content")));
        return ToolResponseMessage.builder()
                .responses(List.of(response))
                .build();
    }

    private static String stringOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.stringValue();
    }

    private static String stringOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.stringValue();
    }

    private static String stringOrDefault(JsonNode node, String defaultValue) {
        String s = stringOrNull(node);
        return s == null ? defaultValue : s;
    }
}

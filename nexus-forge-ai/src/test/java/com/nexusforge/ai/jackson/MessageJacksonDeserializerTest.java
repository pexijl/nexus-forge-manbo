package com.nexusforge.ai.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * /api/ai/chat 请求体反序列化的多态路由测试。
 *
 * <p>直接构造一个挂了 {@link MessageJacksonDeserializer} 的 Jackson 3
 * {@link JsonMapper},用 {@code readValue} 走完整反序列化链路 —— 比
 * mock {@code DeserializationContext} 更接近 Spring MVC 真实场景。
 *
 * <p>Jackson 3 不再像 Jackson 2 那样直接 {@code builder.addDeserializer},
 * 而是通过 {@link SimpleModule} 挂载,再 {@code mapper.addModule} —— 跟
 * {@code @JacksonComponent} 在 Spring Boot 4.x 中的
 * {@code JacksonComponentModule} 走的是同一条装配路径。
 */
class MessageJacksonDeserializerTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new SimpleModule("ai-message-deserializer")
                    .addDeserializer(Message.class, new MessageJacksonDeserializer()))
            .build();

    @Test
    @DisplayName("user 角色 → UserMessage(content)")
    void userRole() {
        Message m = mapper.readValue(
                "{\"role\":\"user\",\"content\":\"hi\"}", Message.class);
        assertThat(m).isInstanceOf(UserMessage.class);
        assertThat(m.getText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("system 角色 → SystemMessage(content)")
    void systemRole() {
        Message m = mapper.readValue(
                "{\"role\":\"system\",\"content\":\"you are helpful\"}", Message.class);
        assertThat(m).isInstanceOf(SystemMessage.class);
        assertThat(m.getText()).isEqualTo("you are helpful");
    }

    @Test
    @DisplayName("assistant 角色(无 tool_calls)→ AssistantMessage(content)")
    void assistantRoleWithoutToolCalls() {
        Message m = mapper.readValue(
                "{\"role\":\"assistant\",\"content\":\"sure\"}", Message.class);
        assertThat(m).isInstanceOf(AssistantMessage.class);
        assertThat(m.getText()).isEqualTo("sure");
        assertThat(((AssistantMessage) m).getToolCalls()).isEmpty();
        assertThat(((AssistantMessage) m).hasToolCalls()).isFalse();
    }

    @Test
    @DisplayName("assistant 角色(带 tool_calls)→ AssistantMessage.builder 注入,OpenAI 嵌套结构扁平化")
    void assistantRoleWithToolCalls() throws Exception {
        // OpenAI wire: tool_calls[i].function.{name,arguments} 嵌套;
        // 反序列化后展平到 AssistantMessage.ToolCall record。
        String json = """
                {
                  "role":"assistant",
                  "content":"",
                  "tool_calls":[
                    {
                      "id":"call_1",
                      "type":"function",
                      "function":{"name":"get_weather","arguments":"{\\"city\\":\\"SF\\"}"}
                    }
                  ]
                }
                """;
        Message m = mapper.readValue(json, Message.class);
        assertThat(m).isInstanceOf(AssistantMessage.class);
        AssistantMessage am = (AssistantMessage) m;
        assertThat(am.hasToolCalls()).isTrue();
        assertThat(am.getToolCalls()).hasSize(1);
        AssistantMessage.ToolCall call = am.getToolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.type()).isEqualTo("function");
        assertThat(call.name()).isEqualTo("get_weather");
        // arguments 是 JSON 字符串(OpenAI wire),原样透传不解析
        assertThat(call.arguments()).isEqualTo("{\"city\":\"SF\"}");
    }

    @Test
    @DisplayName("tool 角色 → ToolResponseMessage,从 tool_call_id/name/content 拼 ToolResponse record")
    void toolRole() {
        String json = """
                {
                  "role":"tool",
                  "tool_call_id":"call_1",
                  "name":"get_weather",
                  "content":"72F sunny"
                }
                """;
        Message m = mapper.readValue(json, Message.class);
        assertThat(m).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage tm = (ToolResponseMessage) m;
        assertThat(tm.getResponses()).hasSize(1);
        ToolResponseMessage.ToolResponse r = tm.getResponses().get(0);
        assertThat(r.id()).isEqualTo("call_1");
        assertThat(r.name()).isEqualTo("get_weather");
        assertThat(r.responseData()).isEqualTo("72F sunny");
    }

    @Test
    @DisplayName("role 大小写不敏感:'USER' 与 'user' 等价")
    void roleCaseInsensitive() {
        Message m = mapper.readValue(
                "{\"role\":\"USER\",\"content\":\"hi\"}", Message.class);
        assertThat(m).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("content 缺省/为 null → 空字符串,不抛 NPE")
    void contentNullSafe() {
        Message m = mapper.readValue("{\"role\":\"user\"}", Message.class);
        assertThat(m).isInstanceOf(UserMessage.class);
        assertThat(m.getText()).isEqualTo("");

        Message m2 = mapper.readValue(
                "{\"role\":\"user\",\"content\":null}", Message.class);
        assertThat(m2.getText()).isEqualTo("");
    }

    @Test
    @DisplayName("assistant 空 tool_calls 数组 → 无 toolCalls 的 AssistantMessage")
    void emptyToolCallsArray() {
        Message m = mapper.readValue(
                "{\"role\":\"assistant\",\"content\":\"hi\",\"tool_calls\":[]}", Message.class);
        assertThat(m).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) m).hasToolCalls()).isFalse();
    }

    @Nested
    @DisplayName("异常路径")
    class ErrorPaths {

        @Test
        @DisplayName("缺少 role 字段 → IllegalArgumentException")
        void missingRole() {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"content\":\"hi\"}", Message.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires string 'role' field");
        }

        @Test
        @DisplayName("未知 role → IllegalArgumentException,列出合法值")
        void unknownRole() {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"role\":\"function\",\"content\":\"x\"}", Message.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown Message role")
                    .hasMessageContaining("user, system, assistant, tool");
        }

        @Test
        @DisplayName("tool 消息缺 tool_call_id → IllegalArgumentException")
        void toolMissingId() {
            assertThatThrownBy(() -> mapper.readValue(
                    "{\"role\":\"tool\",\"name\":\"f\",\"content\":\"x\"}", Message.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires 'tool_call_id' field");
        }
    }
}

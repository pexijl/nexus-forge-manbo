package com.nexusforge.controller.dto;

import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 Step 12:ChatRequestDto 的 {@code tools} 透传单元测试。
 *
 * <p>覆盖矩阵:
 * <ul>
 *   <li>{@link BackwardCompat}:无 {@code tools} 或无 {@code ObjectMapper} 的向后兼容路径</li>
 *   <li>{@link CanonicalShape}:canonical {@code {name, description, parameters}} 形态</li>
 *   <li>{@link OpenAiWireShape}:OpenAI wire {@code {type:"function", function:{...}}} 形态</li>
 *   <li>{@link EdgeCases}:缺字段、空 parameters、null parameters、parameters 已是 JsonNode</li>
 * </ul>
 */
class ChatRequestDtoTest {

    private final ObjectMapper json = new ObjectMapper();

    private ChatRequestDto baseDto() {
        ChatRequestDto dto = new ChatRequestDto();
        dto.setModel("gpt-4o-mini");
        ChatRequestDto.ChatMessageDto m = new ChatRequestDto.ChatMessageDto();
        m.setRole(Role.USER);
        m.setContent("hi");
        dto.setMessages(List.of(m));
        return dto;
    }

    // ───────────────────────────────────────────────────────────
    // 向后兼容
    // ───────────────────────────────────────────────────────────
    @Nested
    @DisplayName("向后兼容:无 tools / 无 ObjectMapper")
    class BackwardCompat {

        @Test
        @DisplayName("tools==null 时,toDomain() 不需要 ObjectMapper 也返回 null")
        void no_tools_no_mapper() {
            ChatRequest req = baseDto().toDomain();
            assertThat(req.getTools()).isNull();
            assertThat(req.getModel()).isEqualTo("gpt-4o-mini");
            assertThat(req.getMessages()).hasSize(1);
            assertThat(req.getMessages().get(0).getRole()).isEqualTo(Role.USER);
            assertThat(req.getMessages().get(0).getContent()).isEqualTo("hi");
        }

        @Test
        @DisplayName("tools 非空但 ObjectMapper==null → 静默丢弃(测试场景降级)")
        void tools_present_no_mapper_drops_silently() {
            ChatRequestDto dto = baseDto();
            dto.setTools(List.of(Map.of("name", "f")));
            ChatRequest req = dto.toDomain();  // no-arg overload
            assertThat(req.getTools()).isNull();
        }

        @Test
        @DisplayName("tools 空列表 → toolsDomain=null,不抛错")
        void empty_tools_becomes_null() {
            ChatRequestDto dto = baseDto();
            dto.setTools(List.of());
            ChatRequest req = dto.toDomain(json);
            assertThat(req.getTools()).isNull();
        }
    }

    // ───────────────────────────────────────────────────────────
    // canonical 形态
    // ───────────────────────────────────────────────────────────
    @Nested
    @DisplayName("canonical 形态:{name, description, parameters}")
    class CanonicalShape {

        @Test
        @DisplayName("单条工具:parameters 是 Map → 转 ObjectNode")
        void single_tool_canonical() {
            ChatRequestDto dto = baseDto();
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", "weather");
            tool.put("description", "天气查询");
            Map<String, Object> params = Map.of(
                    "type", "object",
                    "properties", Map.of("city", Map.of("type", "string")),
                    "required", List.of("city"));
            tool.put("parameters", params);
            dto.setTools(List.of(tool));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools()).hasSize(1);
            ToolDefinition td = req.getTools().get(0);
            assertThat(td.getName()).isEqualTo("weather");
            assertThat(td.getDescription()).isEqualTo("天气查询");
            assertThat(td.getParameters()).isInstanceOf(ObjectNode.class);
            assertThat(td.getParameters().get("type").asString()).isEqualTo("object");
            assertThat(td.getParameters().get("properties").get("city").get("type").asString()).isEqualTo("string");
            assertThat(td.getParameters().get("required").get(0).asString()).isEqualTo("city");
        }

        @Test
        @DisplayName("多条工具:list 顺序保留")
        void multiple_tools_preserves_order() {
            ChatRequestDto dto = baseDto();
            dto.setTools(List.of(
                    Map.of("name", "a"),
                    Map.of("name", "b"),
                    Map.of("name", "c")));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools()).extracting(ToolDefinition::getName)
                    .containsExactly("a", "b", "c");
        }
    }

    // ───────────────────────────────────────────────────────────
    // OpenAI wire 形态
    // ───────────────────────────────────────────────────────────
    @Nested
    @DisplayName("OpenAI wire:{type:\"function\", function:{...}}")
    class OpenAiWireShape {

        @Test
        @DisplayName("单条工具:从 function 子对象解 name/description/parameters")
        void unwrap_function_subobject() {
            ChatRequestDto dto = baseDto();
            Map<String, Object> fn = Map.of(
                    "name", "search",
                    "description", "网络搜索",
                    "parameters", Map.of("type", "object"));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            dto.setTools(List.of(tool));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools()).hasSize(1);
            ToolDefinition td = req.getTools().get(0);
            assertThat(td.getName()).isEqualTo("search");
            assertThat(td.getDescription()).isEqualTo("网络搜索");
            assertThat(td.getParameters().get("type").asString()).isEqualTo("object");
        }

        @Test
        @DisplayName("混合形态:canonical 与 OpenAI wire 混在同一 list")
        void mixed_shapes_in_one_list() {
            ChatRequestDto dto = baseDto();
            // canonical
            Map<String, Object> canonical = Map.of("name", "canon", "description", "d1");
            // OpenAI wire
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("type", "function");
            wire.put("function", Map.of("name", "wire", "description", "d2"));
            dto.setTools(List.of(canonical, wire));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools()).extracting(ToolDefinition::getName)
                    .containsExactly("canon", "wire");
            assertThat(req.getTools().get(0).getDescription()).isEqualTo("d1");
            assertThat(req.getTools().get(1).getDescription()).isEqualTo("d2");
        }

        @Test
        @DisplayName("type==\"function\" 但 function 不是 Map → 当 canonical 解(容错)")
        void non_map_function_falls_back_to_canonical() {
            ChatRequestDto dto = baseDto();
            Map<String, Object> weird = new LinkedHashMap<>();
            weird.put("type", "function");
            weird.put("function", "this-is-not-a-map");
            weird.put("name", "fallback");
            dto.setTools(List.of(weird));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools()).hasSize(1);
            assertThat(req.getTools().get(0).getName()).isEqualTo("fallback");
        }
    }

    // ───────────────────────────────────────────────────────────
    // 边界场景
    // ───────────────────────────────────────────────────────────
    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("缺 name 字段 → name=null,不抛错(provider 自己判)")
        void missing_name_field_does_not_throw() {
            ChatRequestDto dto = baseDto();
            dto.setTools(List.of(Map.of("description", "no name")));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools()).hasSize(1);
            assertThat(req.getTools().get(0).getName()).isNull();
            assertThat(req.getTools().get(0).getDescription()).isEqualTo("no name");
        }

        @Test
        @DisplayName("缺 description 字段 → description=null,不抛错")
        void missing_description_field_does_not_throw() {
            ChatRequestDto dto = baseDto();
            dto.setTools(List.of(Map.of("name", "f")));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools().get(0).getDescription()).isNull();
        }

        @Test
        @DisplayName("parameters==null → 留空,provider 兜底")
        void null_parameters_stays_null() {
            ChatRequestDto dto = baseDto();
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", "f");
            // no parameters
            dto.setTools(List.of(tool));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getTools().get(0).getParameters()).isNull();
        }

        @Test
        @DisplayName("parameters 已是 JsonNode → 原样保留,不重新 parse")
        void parameters_already_json_node_passes_through() {
            ChatRequestDto dto = baseDto();
            ObjectNode existing = json.createObjectNode().put("type", "object");
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", "f");
            tool.put("parameters", existing);
            dto.setTools(List.of(tool));

            ChatRequest req = dto.toDomain(json);

            JsonNode params = req.getTools().get(0).getParameters();
            assertThat(params).isSameAs(existing);
        }

        @Test
        @DisplayName("options 透传:not-null 时原样保留")
        void options_pass_through() {
            ChatRequestDto dto = baseDto();
            dto.setOptions(Map.of("topP", 0.9, "stop", List.of("END")));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getOptions()).containsEntry("topP", 0.9);
            assertThat(req.getOptions().get("stop")).isEqualTo(List.of("END"));
        }

        @Test
        @DisplayName("messages 缺 role → 兜底 USER")
        void missing_role_defaults_to_user() {
            ChatRequestDto dto = new ChatRequestDto();
            ChatRequestDto.ChatMessageDto m = new ChatRequestDto.ChatMessageDto();
            // role is null
            m.setContent("hi");
            dto.setMessages(List.of(m));

            ChatRequest req = dto.toDomain(json);

            assertThat(req.getMessages().get(0).getRole()).isEqualTo(Role.USER);
        }
    }
}
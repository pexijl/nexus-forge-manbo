package com.nexusforge.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.Role;
import com.nexusforge.ai.ToolDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequestDto {
    @Schema(description = "模型标识;不指定时由网关按 defaultVendor + defaultModel 兜底")
    private String model;

    @NotEmpty
    @Valid
    private List<ChatMessageDto> messages;

    @Schema(description = "温度系数,0~1", example = "0.7")
    private Double temperature;

    @Schema(description = "单次生成最大 token 数", example = "1024")
    private Integer maxTokens;

    @Schema(description = "是否走流式接口(本字段由 controller 直接读取,不影响路由)")
    private Boolean stream;

    @Schema(description = "厂商私有参数(透传给 provider 的 options 字段);例如 {topP: 0.9}")
    private Map<String, Object> options;

    /**
     * P4 Step 12:工具定义列表,允许两种入参形态:
     * <ul>
     *   <li>OpenAI wire:{@code [{type:"function", function:{name, description, parameters}}, ...]}</li>
     *   <li>canonical:{@code [{name, description, parameters}, ...]}</li>
     * </ul>
     * DTO 在 {@link #toDomain(ObjectMapper)} 阶段统一归一化为 {@link ToolDefinition},
     * 后续 provider 的 {@code OpenAiJsonMapper} / {@code AnthropicJsonMapper} 不需要
     * 再做 wire 反解 —— 它们的入参语义统一是 canonical {@code ToolDefinition}。
     *
     * <p>本字段为 {@code Map<String,Object>} 而非 {@code ToolDefinition},原因:
     * <ol>
     *   <li>{@code parameters} 是 {@link JsonNode},DTO 在 wire 层持 raw JSON 不会触发
     *       Jackson 对 {@code JsonNode} 的歧义解析。</li>
     *   <li>DTO 不应承担"领域塑形"职责;归一化在 {@code toDomain} 一次性完成。</li>
     * </ol>
     */
    @Schema(description = "工具定义列表;支持 OpenAI wire 或 canonical 形态,DTO 自动归一化")
    private List<Map<String, Object>> tools;

    /**
     * 向后兼容入口:无 {@link ObjectMapper} 时,要求 caller 没传 {@link #tools}。
     *
     * <p>如果 {@link #tools} 非空但 mapper 为 {@code null},降级为 {@code null} —— 不抛错
     * (测试场景下 DTO 可能不持 mapper;运行时由 controller 注入,详见
     * {@link com.nexusforge.controller.AiController} / {@link com.nexusforge.controller.AiStreamController})。
     */
    public ChatRequest toDomain() {
        return toDomain(null);
    }

    /**
     * 完整版:把 {@link #tools} 归一化为 {@link ToolDefinition} 列表。
     *
     * @param mapper 用于把 raw {@code Map<String,Object>} parameters 转 {@link JsonNode};
     *               {@code null} 时若 tools 非空则静默丢弃 tools 字段(保留单测能力)。
     */
    public ChatRequest toDomain(ObjectMapper mapper) {
        List<ChatMessage> ms = messages.stream().map(m -> ChatMessage.builder()
                .role(m.getRole() == null ? Role.USER : m.getRole())
                .content(m.getContent()).build()).toList();
        List<ToolDefinition> toolsDomain = (tools == null || tools.isEmpty() || mapper == null)
                ? null
                : tools.stream().map(t -> toToolDefinition(t, mapper)).toList();
        return ChatRequest.builder()
                .model(model)
                .messages(ms)
                .temperature(temperature).maxTokens(maxTokens)
                .stream(stream != null && stream)
                .options(options)
                .tools(toolsDomain)
                .build();
    }

    /**
     * 把一条 raw 工具定义解为 canonical {@link ToolDefinition}。
     *
     * <p>规则:
     * <ul>
     *   <li>OpenAI wire({@code type=="function" && function instanceof Map})→ 取
     *       {@code function} 子对象作为后续字段来源。</li>
     *   <li>canonical / 其它形态 → 直接当 {@code {name, description, parameters}} 解。</li>
     * </ul>
     *
     * <p>{@code parameters} 转换:
     * <ul>
     *   <li>已是 {@link JsonNode} → 原样保留。</li>
     *   <li>raw {@code Map}/{@code List} → 用 {@code mapper.convertValue(...)} 转。</li>
     *   <li>{@code null} → 留空,由 provider 决定是否兜底。</li>
     * </ul>
     *
     * <p>{@code name} / {@code description} 缺字段不抛错;DTO 层不做强校验(provider
     * 自己决定"缺 name 视为非法"),便于前端做"动态拼装 tools"的快速迭代。
     */
    private static ToolDefinition toToolDefinition(Map<String, Object> raw, ObjectMapper mapper) {
        Map<String, Object> fn;
        Object type = raw.get("type");
        Object function = raw.get("function");
        if ("function".equals(type) && function instanceof Map<?, ?> f) {
            @SuppressWarnings("unchecked")
            Map<String, Object> f2 = (Map<String, Object>) f;
            fn = f2;
        } else {
            fn = raw;
        }
        JsonNode params = null;
        Object paramsObj = fn.get("parameters");
        if (paramsObj instanceof JsonNode jn) {
            params = jn;
        } else if (paramsObj != null && mapper != null) {
            params = mapper.convertValue(paramsObj, JsonNode.class);
        }
        return ToolDefinition.builder()
                .name((String) fn.get("name"))
                .description((String) fn.get("description"))
                .parameters(params)
                .build();
    }

    @Data
    public static class ChatMessageDto {
        @NotNull
        private Role role;
        private String content;
    }
}
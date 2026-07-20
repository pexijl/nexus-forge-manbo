package com.nexusforge.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.ChatMessage;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequestDto {
    private String model;
    @NotEmpty
    @Valid
    private List<ChatMessageDto> messages;
    private Double temperature;
    private Integer maxTokens;
    private Boolean stream;
    private Map<String, Object> options;

    public ChatRequest toDomain() {
        List<ChatMessage> ms = messages.stream().map(m -> ChatMessage.builder()
                .role(m.getRole() == null ? Role.USER : m.getRole())
                .content(m.getContent()).build()).toList();
        return ChatRequest.builder()
                .model(model)
                .messages(ms)
                .temperature(temperature).maxTokens(maxTokens)
                .stream(stream != null && stream)
                .options(options).build();
    }

    @Data
    public static class ChatMessageDto {
        @NotNull
        private Role role;
        private String content;
    }
}
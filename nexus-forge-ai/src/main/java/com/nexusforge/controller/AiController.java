package com.nexusforge.controller;

import com.nexusforge.ai.ChatResponse;
import com.nexusforge.base.Result;
import com.nexusforge.client.LlmClient;
import com.nexusforge.controller.dto.ChatRequestDto;
import com.nexusforge.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Gateway", description = "LLM 网关入口(P1 仅同步)")
@SecurityRequirements   // Swagger 全局已有 bearer,这里不需要重复
public class AiController {

    private final LlmClient client;

    @Operation(summary = "同步调用 LLM(P1 唯一对外接口)")
    @PostMapping("/chat")
    public Result<ChatResponse> chat(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequestDto dto) {
        log.debug("[AI] user={} model={} stream=false",
                principal == null ? "anon" : principal.userId(), dto.getModel());
        ChatResponse resp = client.call(dto.toDomain());
        return Result.success("ok", resp);
    }
}
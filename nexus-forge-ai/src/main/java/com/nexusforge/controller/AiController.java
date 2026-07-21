package com.nexusforge.controller;

import com.nexusforge.ai.ChatResponse;
import com.nexusforge.base.Result;
import com.nexusforge.client.LlmClient;
import com.nexusforge.client.RateLimitGuard;
import com.nexusforge.controller.dto.ChatRequestDto;
import com.nexusforge.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Gateway", description = "LLM 网关入口(P1 同步 / P4 增加 tools 透传)")
@SecurityRequirements   // Swagger 全局已有 bearer,这里不需要重复
public class AiController {

    private final LlmClient client;
    /**
     * P4 Step 12:DTO 的 {@code tools} 字段归一化需要 Jackson {@link ObjectMapper}。
     * Spring Boot 4 默认装配 {@code tools.jackson.databind.ObjectMapper},与
     * Spring MVC 的 message converter 同源,这里直接注入即可。
     */
    private final ObjectMapper objectMapper;
    /** P5 Step 7:动态限流(userId + IP 维度),配置驱动不改代码 */
    private final RateLimitGuard rateLimitGuard;

    @Operation(summary = "同步调用 LLM(P1 同步接口,P4 起支持 tools 透传)")
    @PostMapping("/chat")
    public Result<ChatResponse> chat(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequestDto dto,
            HttpServletRequest request) {
        // P5 Step 7:限流(userId + IP)
        rateLimitGuard.check(
                principal == null ? null : principal.userId(),
                request.getRemoteAddr());
        log.debug("[AI] user={} model={} stream=false",
                principal == null ? "anon" : principal.userId(), dto.getModel());
        ChatResponse resp = client.call(dto.toDomain(objectMapper));
        return Result.success("ok", resp);
    }
}

package com.nexusforge.controller;

import com.nexusforge.ai.ChatResponse;
import com.nexusforge.ai.service.PreferenceResolver;
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
    /** P7:用户偏好解析器(决定走系统 Key 还是私 Key) */
    private final PreferenceResolver preferenceResolver;

    @Operation(summary = "同步调用 LLM(P1 同步接口,P4 起支持 tools 透传)")
    @PostMapping("/chat")
    public Result<ChatResponse> chat(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequestDto dto,
            HttpServletRequest request) {
        Long userId = principal == null ? null : principal.userId();
        // P7:解析偏好(返回 keySource + 私 Key 等)
        PreferenceResolver.Resolved pref = preferenceResolver.resolve(userId, dto.getModel());
        // P5 Step 7:限流(userId + IP 维度,按 keySource 分流)
        rateLimitGuard.check(userId, request.getRemoteAddr(), pref.source());
        log.debug("[AI] user={} vendor={} model={} mode={} stream=false",
                userId == null ? "anon" : userId, pref.vendor(), pref.model(), pref.source());

        // 私 Key 模式:按 pref.vendor/model 替换 ChatRequest 的 model 字段(不带 vendor 前缀,
        // 由 LlmClient 私有 overload 用传入的 ChatModel 路由);然后走 LlmClient(req, model) overload。
        if (pref.source() == PreferenceResolver.KeySource.USER_PRIVATE_KEY) {
            com.nexusforge.ai.ChatRequest req = dto.toDomain(objectMapper);
            req.setModel(pref.vendor() + ":" + pref.model());   // 私有路径在 LlmClient.call(req, model) 内部处理
            ChatResponse resp = client.call(req, preferenceResolver.resolveChatModel(pref));
            return Result.success("ok", resp);
        }
        // 系统 Key 模式:把 pref.vendor/pref.model 透传进 LlmClient.call(req, vendor, model),
        // 让 PreferenceResolver 解析的 model 成为唯一权威(不受 yaml 兜底影响)。
        com.nexusforge.ai.ChatRequest req = dto.toDomain(objectMapper);
        ChatResponse resp = client.call(req, pref.vendor(), pref.model());
        return Result.success("ok", resp);
    }
}

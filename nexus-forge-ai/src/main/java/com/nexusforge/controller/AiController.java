package com.nexusforge.controller;

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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * spring-ai-full-migration Phase 2c — 同步 AI 端点。
 *
 * <p>直接使用 Spring AI 类型:
 * <ul>
 *   <li>{@link Prompt} 装载 {@link org.springframework.ai.chat.messages.Message} 列表</li>
 *   <li>{@link ChatResponse} 返给客户端(breaking change — 客户端要适配新 chunk 格式)</li>
 * </ul>
 *
 * <p>DTO(ChatRequestDto.messages)直接是 {@code List<Message>},无需内
 * 部适配 — Jackson + Spring AI 的 MessageTypeDeserializer 已经把
 * JSON 的 {@code {role, content}} 反序列化为对应 Spring AI 类型。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Gateway", description = "LLM 网关入口(P1 同步 / P4 增加 tools 透传)")
@SecurityRequirements
public class AiController {

    private final LlmClient client;
    private final RateLimitGuard rateLimitGuard;
    private final PreferenceResolver preferenceResolver;

    @Operation(summary = "同步调用 LLM(Phase 2c:DTO 字段即 Spring AI 类型;Phase 3 支持 proxyId 选代理)")
    @PostMapping("/chat")
    public Result<ChatResponse> chat(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequestDto dto,
            HttpServletRequest request) {
        Long userId = principal == null ? null : principal.userId();
        // Phase 3:resolve 第三参 proxyId 决定 BYOK 多代理优先级
        PreferenceResolver.Resolved pref = preferenceResolver.resolve(userId, dto.getModel(), dto.getProxyId());
        rateLimitGuard.check(userId, request.getRemoteAddr(), pref.source());
        log.debug("[AI] user={} vendor={} model={} mode={} proxyId={} stream=false",
                userId == null ? "anon" : userId, pref.vendor(), pref.model(), pref.source(), dto.getProxyId());

        Prompt prompt = new Prompt(dto.getMessages());

        if (pref.source() == PreferenceResolver.KeySource.USER_PRIVATE_KEY) {
            ChatModel privateKeyModel = preferenceResolver.resolveChatModel(pref);
            // Phase 3:把 model 写进 prompt options(代理可能用 proxy.defaultModel 替代 vendor yaml default),
            // 并用 3 参 call 重载做正确的实际 vendor catalog 校验
            Prompt callPrompt = LlmClient.withModelInOptions(prompt, pref.vendor(), pref.model());
            ChatResponse resp = client.call(callPrompt, privateKeyModel, pref.vendor());
            return Result.success("ok", resp);
        }
        ChatResponse resp = client.call(prompt, pref.vendor(), pref.model());
        return Result.success("ok", resp);
    }
}

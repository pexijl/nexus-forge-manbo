package com.nexusforge.controller;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.ai.service.PreferenceResolver;
import com.nexusforge.client.LlmClient;
import com.nexusforge.client.RateLimitGuard;
import com.nexusforge.controller.dto.ChatRequestDto;
import com.nexusforge.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * P2 流式端点:POST /api/ai/chat/stream,以 {@code text/event-stream} 回传 ChatChunk 流。
 *
 * <p>采用 {@link StreamingResponseBody}:Spring 拿到 OutputStream 后在新线程调 writeTo,
 * Tomcat 直接驱动 chunked output —— 绕过 {@code SseEmitter} 在 Spring 7 + Tomcat 11
 * 组合下的 chunked transfer encoding 边界 EOF 问题。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Gateway Stream", description = "LLM 网关流式接口(P2 SSE)")
public class AiStreamController {

    private final LlmClient client;
    private final ObjectMapper objectMapper;
    /** P5 Step 7:动态限流(userId + IP 维度) */
    private final RateLimitGuard rateLimitGuard;
    /** P7:用户偏好解析器 */
    private final PreferenceResolver preferenceResolver;

    @Operation(summary = "流式调用 LLM(P2,SSE)")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequestDto dto,
            HttpServletResponse response,
            HttpServletRequest request) {
        Long userId = principal == null ? null : principal.userId();
        // P7:解析偏好
        PreferenceResolver.Resolved pref = preferenceResolver.resolve(userId, dto.getModel());
        // P5 Step 7:限流(按 keySource 分流)
        rateLimitGuard.check(userId, request.getRemoteAddr(), pref.source());

        // StreamingResponseBody 的 produces 不自动写 Content-Type;需手动设
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");

        ChatRequest req = dto.toDomain(objectMapper);
        log.info("[AI stream] user={} vendor={} model={} mode={}",
                userId == null ? "anon" : userId, pref.vendor(), pref.model(), pref.source());
        final Flux<ChatChunk> chunks;
        if (pref.source() == PreferenceResolver.KeySource.USER_PRIVATE_KEY) {
            req.setModel(pref.vendor() + ":" + pref.model());
            chunks = client.stream(req, preferenceResolver.resolveChatModel(pref));
        } else {
            // 系统 Key 路径:把 pref.vendor/pref.model 透传进 LlmClient.stream(req, vendor, model),
            // 让 PreferenceResolver 解析的 model 成为唯一权威(不受 yaml 兜底影响)。
            chunks = client.stream(req, pref.vendor(), pref.model());
        }
        return output -> {
            log.info("[AI stream] writeTo called, output class={}", output.getClass().getName());
            writeChunks(output, chunks);
            log.info("[AI stream] writeTo done");
        };
    }

    private void writeChunks(OutputStream output, Flux<ChatChunk> chunks) {
        try {
            chunks.subscribe(
                    chunk -> writeFrame(output, chunk),
                    err -> writeErrorFrame(output, err),
                    () -> writeDoneFrame(output));
            // 等待流完成
            chunks.blockLast();
        } catch (Exception e) {
            log.warn("[AI stream] chunk 写入异常: {}", e.toString());
        }
    }

    private void writeFrame(OutputStream output, ChatChunk chunk) {
        try {
            String json = objectMapper.writeValueAsString(chunk);
            output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            log.warn("[AI stream] frame 写入失败: {}", e.toString());
        }
    }

    private static void writeDoneFrame(OutputStream output) {
        try {
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            // 忽略:客户端可能已断开
        }
    }

    private static void writeErrorFrame(OutputStream output, Throwable err) {
        try {
            String msg = "{\"error\":\"" + err.getMessage().replace("\"", "\\\"") + "\"}";
            output.write(("data: " + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            // 忽略
        }
    }
}

package com.nexusforge.controller;

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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
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
import java.util.concurrent.CountDownLatch;

/**
 * spring-ai-full-migration Phase 2c — 流式 AI 端点。
 *
 * <p>SSE wire 格式(Phase 2b 已 breaking change):每帧是 Spring AI 的
 * {@link ChatResponse} JSON 序列化结果。
 *
 * <p>用 {@link StreamingResponseBody} 保持 Spring 7 + Tomcat 11 兼容
 * (绕过 {@code SseEmitter} 的 chunked EOF 问题)。
 *
 * <p>Phase 2c:DTO 字段直接是 Spring AI 类型,不再走 buildPrompt 适配。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "AI Gateway Stream", description = "LLM 网关流式接口(Phase 2c:DTO 字段即 Spring AI 类型)")
public class AiStreamController {

    private final LlmClient client;
    private final ObjectMapper objectMapper;
    private final RateLimitGuard rateLimitGuard;
    private final PreferenceResolver preferenceResolver;

    @Operation(summary = "流式调用 LLM(Phase 2c:DTO 字段即 Spring AI Message;Phase 3 支持 proxyId 选代理)")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequestDto dto,
            HttpServletResponse response,
            HttpServletRequest request) {
        Long userId = principal == null ? null : principal.userId();
        // Phase 3:resolve 第三参 proxyId
        PreferenceResolver.Resolved pref = preferenceResolver.resolve(userId, dto.getModel(), dto.getProxyId());
        rateLimitGuard.check(userId, request.getRemoteAddr(), pref.source());

        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");

        Prompt prompt = new Prompt(dto.getMessages());
        log.info("[AI stream] user={} vendor={} model={} mode={} proxyId={}",
                userId == null ? "anon" : userId, pref.vendor(), pref.model(), pref.source(), dto.getProxyId());

        final Flux<ChatResponse> chunks;
        if (pref.source() == PreferenceResolver.KeySource.USER_PRIVATE_KEY) {
            ChatModel privateKeyModel = preferenceResolver.resolveChatModel(pref);
            // Phase 3:model 写入 prompt options(代理可能用 proxy.defaultModel 替代 vendor yaml default),
            // 并用 3 参 stream 重载做正确的实际 vendor catalog 校验
            Prompt callPrompt = LlmClient.withModelInOptions(prompt, pref.vendor(), pref.model());
            chunks = client.stream(callPrompt, privateKeyModel, pref.vendor());
        } else {
            chunks = client.stream(prompt, pref.vendor(), pref.model());
        }
        return output -> {
            log.info("[AI stream] writeTo called, output class={}", output.getClass().getName());
            writeChunks(output, chunks);
            log.info("[AI stream] writeTo done");
        };
    }

    /**
     * 单次订阅 + latch 阻塞:Reactor subscribe 本身是非阻塞,必须等流完成才返回 writeTo,
     * 否则 Spring 会在异步线程刚拿到 OutputStream 后立刻关掉它,客户端拿不到完整 SSE。
     * (避免对 cold Flux 双重订阅导致两次 LLM HTTP 请求。)
     */
    private void writeChunks(OutputStream output, Flux<ChatResponse> chunks) {
        CountDownLatch done = new CountDownLatch(1);
        chunks.subscribe(
                chunk -> writeFrame(output, chunk),
                err -> {
                    try {
                        writeErrorFrame(output, err);
                    } finally {
                        done.countDown();
                    }
                },
                () -> done.countDown());
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AI stream] chunk 写入被中断");
        }
    }

    /**
     * 把 Spring AI ChatResponse 序列化为 JSON 帧写入 SSE 流。
     * Phase 2b:每帧直接序列化 ChatResponse,客户端按 Spring AI 字段路径解析。
     */
    private void writeFrame(OutputStream output, ChatResponse chunk) {
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
            // 忽略
        }
    }

    private static void writeErrorFrame(OutputStream output, Throwable err) {
        try {
            String msg = "{\"error\":\"" + (err.getMessage() == null ? "" : err.getMessage().replace("\"", "\\\"")) + "\"}";
            output.write(("data: " + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException e) {
            // 忽略
        }
    }
}

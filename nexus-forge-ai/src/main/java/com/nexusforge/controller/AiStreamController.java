package com.nexusforge.controller;

import com.nexusforge.ai.ChatChunk;
import com.nexusforge.ai.ChatRequest;
import com.nexusforge.client.LlmClient;
import com.nexusforge.controller.dto.ChatRequestDto;
import com.nexusforge.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @Operation(summary = "流式调用 LLM(P2,SSE)")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody stream(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChatRequestDto dto,
            HttpServletResponse response) {
        // StreamingResponseBody 的 produces 不自动写 Content-Type;需手动设
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");

        ChatRequest request = dto.toDomain();
        log.info("[AI stream] user={} model={}",
                principal == null ? "anon" : principal.userId(), request.getModel());
        Flux<ChatChunk> chunks = client.stream(request);
        return output -> {
            log.info("[AI stream] writeTo called, output class={}", output.getClass().getName());
            writeChunks(output, chunks);
            log.info("[AI stream] writeTo done");
        };
    }

    private void writeChunks(OutputStream output, Flux<ChatChunk> chunks) {
        try {
            chunks.doOnNext(chunk -> writeFrame(output, chunk))
                    .doOnComplete(() -> writeDoneFrame(output))
                    .doOnError(err -> writeErrorFrame(output, err))
                    .blockLast();
            output.flush();
        } catch (Exception e) {
            log.warn("[AI stream] write error: {}", e.toString());
        }
    }

    private void writeFrame(OutputStream output, ChatChunk chunk) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("event: delta\n");
            if (chunk.getId() != null) sb.append("id: ").append(chunk.getId()).append('\n');
            sb.append("data: ").append(objectMapper.writeValueAsString(chunk)).append("\n\n");
            output.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            log.debug("[AI stream] wrote chunk id={}", chunk.getId());
        } catch (IOException e) {
            throw new RuntimeException("client gone", e);
        } catch (Exception e) {
            log.warn("[AI stream] writeFrame error: {}", e.toString());
        }
    }

    private static void writeDoneFrame(OutputStream output) {
        try {
            output.write("event: done\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {}
    }

    private static void writeErrorFrame(OutputStream output, Throwable err) {
        try {
            String msg = err.getMessage() == null ? "" : err.getMessage().replace("\n", "\\n");
            output.write(("event: error\ndata: " + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ignored) {}
    }
}
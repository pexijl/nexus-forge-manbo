package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.controller.dto.*;
import com.nexusforge.controller.vo.ConversationDetailVo;
import com.nexusforge.controller.vo.ConversationVo;
import com.nexusforge.controller.vo.MessageVo;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
@Tag(name = "AI Conversations", description = "AI 对话管理(P3)")
@SecurityRequirements
public class AiConversationController {

    private final ConversationService conversationService;

    @Operation(summary = "创建对话")
    @PostMapping
    public Result<ConversationVo> create(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateConversationDto dto) {
        ConversationVo vo = conversationService.create(principal.userId(), dto);
        return Result.success("对话已创建", vo);
    }

    @Operation(summary = "列出我的对话")
    @GetMapping
    public Result<List<ConversationVo>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        List<ConversationVo> vos = conversationService.listConversations(principal.userId());
        return Result.success(vos);
    }

    @Operation(summary = "获取对话详情(含消息)")
    @GetMapping("/{id}")
    public Result<ConversationDetailVo> get(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id) {
        ConversationDetailVo vo = conversationService.getConversation(principal.userId(), id);
        return Result.success(vo);
    }

    @Operation(summary = "在对话中发送消息(同步调用 LLM)")
    @PostMapping("/{id}/messages")
    public Result<MessageVo> sendMessage(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id,
            @Valid @RequestBody SendMessageDto dto) {
        MessageVo vo = conversationService.sendMessage(principal.userId(), id, dto);
        return Result.success("消息已发送", vo);
    }

    @Operation(summary = "重命名对话")
    @PatchMapping("/{id}/title")
    public Result<ConversationVo> rename(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateTitleDto dto) {
        ConversationVo vo = conversationService.renameConversation(principal.userId(), id, dto);
        return Result.success("标题已更新", vo);
    }

    @Operation(summary = "置顶/取消置顶")
    @PatchMapping("/{id}/pin")
    public Result<ConversationVo> pin(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id,
            @RequestBody PinConversationDto dto) {
        ConversationVo vo = conversationService.pinConversation(principal.userId(), id, dto.isPinned());
        return Result.success(vo);
    }

    @Operation(summary = "删除对话")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long id) {
        conversationService.deleteConversation(principal.userId(), id);
        return Result.success("对话已删除", null);
    }
}
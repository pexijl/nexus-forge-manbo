package com.nexusforge.controller;

import com.nexusforge.base.Result;
import com.nexusforge.controller.vo.UsageSummaryVo;
import com.nexusforge.security.UserPrincipal;
import com.nexusforge.service.UsageAggregateRow;
import com.nexusforge.service.UsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

/**
 * P5 Step 7 — AI 用量查询接口。
 *
 * <p>提供两个端点:
 * <ul>
 *   <li>{@code GET /api/ai/usage} — 当前用户按时间窗汇总(默认 24h)</li>
 *   <li>{@code GET /api/ai/usage/conversation/{id}} — 单会话累计用量</li>
 * </ul>
 *
 * <p>只读,不修改数据。配额校验在 {@code ConversationService.sendMessage} 中完成。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/usage")
@RequiredArgsConstructor
@Tag(name = "AI Usage", description = "AI 用量查询(P5)")
@SecurityRequirements
public class AiUsageController {

    private final UsageService usageService;

    @Operation(summary = "我的用量汇总(默认 24h)")
    @GetMapping
    public Result<UsageSummaryVo> myUsage(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        UsageSummaryVo vo = usageService.getSummary(principal.userId(), from, to);
        return Result.success(vo);
    }

    @Operation(summary = "单会话累计用量")
    @GetMapping("/conversation/{id}")
    public Result<UsageAggregateRow> conversationUsage(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") Long conversationId) {
        // 会话归属校验在 ConversationService 层,这里只做用量查询
        UsageAggregateRow row = usageService.getConversationUsage(conversationId);
        return Result.success(row);
    }
}

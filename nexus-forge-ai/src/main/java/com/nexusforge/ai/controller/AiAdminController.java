package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.UpdateGlobalDefaultDto;
import com.nexusforge.ai.entity.AiGlobalDefault;
import com.nexusforge.ai.service.AiPreferenceService;
import com.nexusforge.base.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/ai/global-default")
@RequiredArgsConstructor
@Tag(name = "AI Admin", description = "AI 全局默认配置(管理员)")
@PreAuthorize("hasRole('ADMIN')")
public class AiAdminController {

    private final AiPreferenceService preferenceService;

    @Operation(summary = "查询 AI 全局默认")
    @GetMapping
    public Result<AiGlobalDefault> get() {
        return Result.success(preferenceService.getGlobalDefault());
    }

    @Operation(summary = "修改 AI 全局默认(vendor / model / enabled)")
    @PutMapping
    public Result<AiGlobalDefault> update(@Valid @RequestBody UpdateGlobalDefaultDto dto) {
        return Result.success("全局默认已更新", preferenceService.updateGlobalDefault(dto));
    }
}
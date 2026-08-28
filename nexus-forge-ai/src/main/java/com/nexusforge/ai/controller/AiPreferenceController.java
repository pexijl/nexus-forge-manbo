package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.PreferenceVo;
import com.nexusforge.ai.controller.dto.UpdatePreferenceDto;
import com.nexusforge.ai.service.AiPreferenceService;
import com.nexusforge.base.Result;
import com.nexusforge.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/preference")
@RequiredArgsConstructor
@Tag(name = "AI User Preference", description = "用户级 AI 个性化配置(vendor/model/私 Key)")
@SecurityRequirements
public class AiPreferenceController {

    private final AiPreferenceService preferenceService;

    @Operation(summary = "查询当前用户的 AI 偏好(实际生效值)")
    @GetMapping
    public Result<PreferenceVo> get(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        return Result.success(preferenceService.getPreference(principal.userId()));
    }

    @Operation(summary = "新建 / 更新用户偏好(upsert);若 apiKey 非空则覆盖私 Key")
    @PutMapping
    public Result<PreferenceVo> update(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdatePreferenceDto dto) {
        return Result.success("偏好已保存", preferenceService.updatePreference(principal.userId(), dto));
    }

    @Operation(summary = "删除用户偏好,回退到全局默认")
    @DeleteMapping
    public Result<Void> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        preferenceService.deletePreference(principal.userId());
        return Result.success("已回退到全局默认", null);
    }
}
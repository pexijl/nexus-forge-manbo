package com.nexusforge.controller;

import com.nexusforge.ai.controller.vo.PublicModelVo;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.base.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公共可用模型查询 — 给前端 UI 在"选择 model"下拉框用。
 *
 * <p>路径 {@code /api/ai/models/available} 跟 {@code /api/ai/...} 同前缀
 * (在 {@code com.nexusforge.controller} 包,跟 {@code AiController} 等
 * 同级,统一对外 AI 用户面 API)。无需鉴权(列表本身没敏感信息,
 * 任何登录用户都应该能选 model);后续若加按 user tier 过滤,放 service 层即可。
 */
@RestController
@RequestMapping("/api/ai/models")
@RequiredArgsConstructor
@Tag(name = "AI Models", description = "公共可用模型列表")
public class AiPublicModelController {

    private final ModelCatalogService service;

    @Operation(summary = "列出当前可用的 model 列表(enabled=true 的全集)")
    @GetMapping("/available")
    public Result<List<PublicModelVo>> listAvailable() {
        List<PublicModelVo> vos = service.listEnabled().stream()
                .map(PublicModelVo::from)
                .toList();
        return Result.success(vos);
    }
}

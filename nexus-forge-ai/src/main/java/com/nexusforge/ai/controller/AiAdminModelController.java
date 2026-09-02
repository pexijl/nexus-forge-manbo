package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.ModelCatalogDto;
import com.nexusforge.ai.controller.vo.ModelCatalogVo;
import com.nexusforge.ai.service.ModelCatalogService;
import com.nexusforge.base.PageResult;
import com.nexusforge.base.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AI 模型目录 Admin CRUD。
 *
 * <p>所有端点要求 {@code ADMIN} 角色;Phase 1 范围内只暴露 model 元数据管理,
 * vendor base URL 持久化(Phase 2) / user private endpoint(Phase 3) 后续独立。
 *
 * <p>路径前缀 {@code /api/admin/ai/models};跟 {@code /api/admin/ai/global-default}
 * 同源(同一 Admin tag)。
 */
@RestController
@RequestMapping("/api/admin/ai/models")
@RequiredArgsConstructor
@Tag(name = "AI Admin - 模型目录", description = "管理员管理 AI 模型目录(Phase 1)")
@PreAuthorize("hasRole('ADMIN')")
public class AiAdminModelController {

    private final ModelCatalogService service;

    @Operation(summary = "分页查询模型列表(支持 vendor / enabled 过滤)")
    @GetMapping
    public Result<PageResult<ModelCatalogVo>> list(
            @Parameter(description = "页码(1-based)", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "按 vendor 过滤(不传 = 全部)")
            @RequestParam(required = false) String vendor,
            @Parameter(description = "按 enabled 过滤(不传 = 全部)")
            @RequestParam(required = false) Boolean enabled) {

        Pageable pageable = PageRequest.of(Math.max(0, page - 1), clampSize(size));
        Page<com.nexusforge.ai.entity.AiModelCatalog> p = service.findPage(vendor, enabled, pageable);
        Page<ModelCatalogVo> mapped = p.map(ModelCatalogVo::from);
        return Result.success(PageResult.of(mapped));
    }

    @Operation(summary = "查询单个 model 详情")
    @GetMapping("/{id}")
    public Result<ModelCatalogVo> get(@PathVariable Long id) {
        return Result.success(ModelCatalogVo.from(service.findById(id)));
    }

    @Operation(summary = "新建 model(vendor + modelName 必填,其余可选)")
    @PostMapping
    public Result<ModelCatalogVo> create(@Valid @RequestBody ModelCatalogDto dto) {
        com.nexusforge.ai.entity.AiModelCatalog saved = service.create(dto);
        return Result.success("model 已创建", ModelCatalogVo.from(saved));
    }

    @Operation(summary = "修改 model(partial update,vendor/modelName 不允许改)")
    @PutMapping("/{id}")
    public Result<ModelCatalogVo> update(@PathVariable Long id,
                                         @Valid @RequestBody ModelCatalogDto dto) {
        com.nexusforge.ai.entity.AiModelCatalog saved = service.update(id, dto);
        return Result.success("model 已更新", ModelCatalogVo.from(saved));
    }

    @Operation(summary = "单独切 enabled(独立端点,触发独立事件类型便于审计)")
    @PatchMapping("/{id}/enabled")
    public Result<ModelCatalogVo> setEnabled(@PathVariable Long id,
                                             @Parameter(description = "目标状态", example = "true")
                                             @RequestParam boolean enabled) {
        return Result.success("enabled 已更新", ModelCatalogVo.from(service.setEnabled(id, enabled)));
    }

    @Operation(summary = "硬删除 model(model catalog 是配置数据,不走软删)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success("model 已删除", null);
    }

    private static int clampSize(int size) {
        if (size <= 0) return 20;
        if (size > 200) return 200;     // 防御性 cap,避免 admin 误传超大 size 拖 DB
        return size;
    }
}

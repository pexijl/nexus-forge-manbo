package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.FallbackChainDto;
import com.nexusforge.ai.controller.vo.FallbackChainVo;
import com.nexusforge.ai.service.FallbackChainService;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainView;
import com.nexusforge.base.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AI 降级链 Admin API(Phase 7 — fallback-chain 策略 DB 化)。
 *
 * <p>全局唯一降级链,DB 单行表(id=1);admin 可改、可重置。
 *
 * <p>端点:
 * <ul>
 *   <li>{@code GET} — 查当前生效降级链(DB / YAML_FALLBACK / EMPTY 三段语义,
 *       见 {@link FallbackChainVo})</li>
 *   <li>{@code PUT} — 全量替换(空 list = 显式禁用降级,DB 仍有行),
 *       校验每个 vendor 名存在于 yaml {@code spring.ai.providers.*}</li>
 *   <li>{@code DELETE} — 物理删除 DB 行,回退 yaml 兜底
 *       (跟 PUT 空 list 是不同语义,后者仍保留 DB 行)</li>
 * </ul>
 *
 * <p>改完秒级生效(走 {@code FallbackChainChangedEvent} 失效
 * {@code FallbackChainService} 内部 Caffeine 缓存;下一次
 * {@code ChatModelRouter.resolveWithFallback} 调 {@code service.findEffective()}
 * 即拿新链)。
 *
 * <p>路径前缀 {@code /api/admin/ai/fallback-chain};跟 vendor / model catalog
 * 同源(同一 Admin tag)。
 */
@RestController
@RequestMapping("/api/admin/ai/fallback-chain")
@RequiredArgsConstructor
@Tag(name = "AI Admin - 降级链", description = "管理员管理 ChatModelRouter 降级链(DB > yaml 兜底,热改秒级生效)")
@PreAuthorize("hasRole('ADMIN')")
public class AiAdminFallbackChainController {

    private final FallbackChainService service;

    /**
     * 查当前生效降级链。
     * <p>返回的 {@code source} 字段让 admin 一眼看出当前值来自 DB / YAML_FALLBACK / EMPTY。
     */
    @Operation(summary = "查当前生效降级链(DB > yaml 兜底)")
    @GetMapping
    public Result<FallbackChainVo> get() {
        FallbackChainView view = service.findEffective();
        return Result.success(FallbackChainVo.from(view));
    }

    /**
     * 全量替换降级链。
     * <p>空 list 合法(显式禁用降级);每项 vendor 名必须存在于 yaml,
     * 否则 400;重复 vendor 在 service 内去重(LinkedHashSet 保序)。
     */
    @Operation(summary = "全量替换降级链(vendors 全量,空 list = 显式禁用降级;改完秒级生效)")
    @PutMapping
    public Result<FallbackChainVo> replace(@Valid @RequestBody FallbackChainDto dto) {
        FallbackChainView view = service.replace(dto.getVendors());
        return Result.success("降级链已更新,下次 LLM 调用立即生效", FallbackChainVo.from(view));
    }

    /**
     * 物理删除 DB 行,回退 yaml 兜底。
     * <p>跟 PUT 空 list 的差异:DELETE 让 DB 无行 → 完全交给 yaml;PUT 空 list 仍
     * 保留 DB 行(显式"我不要降级"语义)。
     * <p>DB 本来就没行时幂等返 200(不抛错)。
     */
    @Operation(summary = "物理删除 DB 降级链,回退 yaml 兜底(幂等)")
    @DeleteMapping
    public Result<FallbackChainVo> reset() {
        FallbackChainView view = service.reset();
        return Result.success("降级链已重置为 yaml 兜底", FallbackChainVo.from(view));
    }
}

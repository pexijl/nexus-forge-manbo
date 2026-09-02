package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.UserAiProxyDto;
import com.nexusforge.ai.controller.vo.UserAiProxyVo;
import com.nexusforge.ai.entity.UserAiProxy;
import com.nexusforge.ai.service.UserAiProxyService;
import com.nexusforge.base.Result;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户 AI 代理 API(Phase 3 用户级 BYOK 多端点)。
 *
 * <p>路径前缀 {@code /api/ai/proxies};本组端点都要求登录态,通过
 * {@code @AuthenticationPrincipal UserPrincipal} 拿 userId 做所有权校验。
 *
 * <h3>端点清单</h3>
 * <ul>
 *   <li>{@code GET    /api/ai/proxies}            — 列出我的代理(is_default 优先)</li>
 *   <li>{@code GET    /api/ai/proxies/default}    — 查询我的当前活跃代理(可能 404)</li>
 *   <li>{@code POST   /api/ai/proxies}            — 新建代理</li>
 *   <li>{@code GET    /api/ai/proxies/{id}}       — 详情(所有权校验)</li>
 *   <li>{@code PUT    /api/ai/proxies/{id}}       — partial update(vendor 禁止改)</li>
 *   <li>{@code POST   /api/ai/proxies/{id}/default} — 标记为当前活跃代理</li>
 *   <li>{@code DELETE /api/ai/proxies/{id}}       — 硬删除</li>
 * </ul>
 *
 * <h3>跟 preference 的关系</h3>
 * 本组是 Phase 3 新"多代理"端点;旧的 {@code /api/ai/preference}(单行 BYOK)
 * 仍保留 — 用户没设默认代理时回退到旧 preference → global default。
 */
@RestController
@RequestMapping("/api/ai/proxies")
@RequiredArgsConstructor
@Tag(name = "AI User Proxies", description = "用户级 AI 代理(BYOK 多端点,Phase 3)")
@SecurityRequirements
public class AiUserProxyController {

    private final UserAiProxyService proxyService;

    @Operation(summary = "列出我的代理(is_default 优先,alias 字典序)")
    @GetMapping
    public Result<List<UserAiProxyVo>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        List<UserAiProxyVo> vos = proxyService.listByUserId(principal.userId()).stream()
                .map(UserAiProxyVo::from)
                .toList();
        return Result.success(vos);
    }

    @Operation(summary = "查询我的当前活跃代理(可能 404 — 用户没设默认)")
    @GetMapping("/default")
    public Result<UserAiProxyVo> getDefault(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        UserAiProxy p = proxyService.findDefaultByUserId(principal.userId())
                .orElseThrow(() -> new BusinessException(
                        ResultCode.LLM_PROXY_NOT_FOUND,
                        "用户没有标记的默认代理"));
        return Result.success(UserAiProxyVo.from(p));
    }

    @Operation(summary = "新建代理(BYOK — apiKey 必填,vendor 必须在 OpenAI 兼容集合)")
    @PostMapping
    public Result<UserAiProxyVo> create(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UserAiProxyDto dto) {
        UserAiProxy saved = proxyService.create(principal.userId(), dto);
        return Result.success("代理已创建", UserAiProxyVo.from(saved));
    }

    @Operation(summary = "查询单个代理详情(所有权校验)")
    @GetMapping("/{id}")
    public Result<UserAiProxyVo> get(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return Result.success(UserAiProxyVo.from(proxyService.findById(principal.userId(), id)));
    }

    @Operation(summary = "修改代理(partial update;vendor 禁止改,name 允许改但需保持同 user 内唯一)")
    @PutMapping("/{id}")
    public Result<UserAiProxyVo> update(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UserAiProxyDto dto) {
        UserAiProxy saved = proxyService.update(principal.userId(), id, dto);
        return Result.success("代理已更新", UserAiProxyVo.from(saved));
    }

    @Operation(summary = "标记代理为当前活跃(同 user 其他 default 会被自动 unmark;幂等)")
    @PostMapping("/{id}/default")
    public Result<UserAiProxyVo> setDefault(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        UserAiProxy saved = proxyService.setDefault(principal.userId(), id);
        return Result.success("默认代理已切换", UserAiProxyVo.from(saved));
    }

    @Operation(summary = "硬删除代理(若被删的是当前 default,后续解析回退到旧 preference / global default)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        proxyService.delete(principal.userId(), id);
        return Result.success("代理已删除", null);
    }
}

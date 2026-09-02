package com.nexusforge.ai.controller;

import com.nexusforge.ai.controller.dto.UserAiModelAliasDto;
import com.nexusforge.ai.controller.vo.UserAiModelAliasVo;
import com.nexusforge.ai.entity.UserAiModelAlias;
import com.nexusforge.ai.service.UserAiModelAliasService;
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

import java.util.List;

/**
 * 用户 model alias API(Phase 4 模型别名)。
 *
 * <p>路径前缀 {@code /api/ai/aliases};本组端点都要求登录态,通过
 * {@code @AuthenticationPrincipal UserPrincipal} 拿 userId 做所有权校验。
 *
 * <h3>端点清单</h3>
 * <ul>
 *   <li>{@code GET    /api/ai/aliases}        — 列出我的 alias(按 alias 字典序)</li>
 *   <li>{@code POST   /api/ai/aliases}        — 新建 alias(同 user 内大小写不敏感唯一)</li>
 *   <li>{@code GET    /api/ai/aliases/{id}}   — 详情(所有权校验)</li>
 *   <li>{@code PUT    /api/ai/aliases/{id}}   — partial update(alias 改名会触发 cache key 迁移)</li>
 *   <li>{@code DELETE /api/ai/aliases/{id}}   — 硬删除</li>
 * </ul>
 *
 * <h3>跟 chat 的关系</h3>
 * 用户在 chat 请求的 {@code model} 字段填 alias 名(不带冒号),
 * {@code PreferenceResolver} 自动改写为 {@code targetVendor:targetModel} 走原解析链。
 * alias 命中失败 / enabled=false → 静默 fall through 到原优先级(行为对用户透明)。
 */
@RestController
@RequestMapping("/api/ai/aliases")
@RequiredArgsConstructor
@Tag(name = "AI User Model Aliases", description = "用户级 model alias(模型别名,Phase 4)")
@SecurityRequirements
public class AiUserAliasController {

    private final UserAiModelAliasService aliasService;

    @Operation(summary = "列出我的 alias(按 alias 名字典序)")
    @GetMapping
    public Result<List<UserAiModelAliasVo>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal) {
        List<UserAiModelAliasVo> vos = aliasService.listByUserId(principal.userId()).stream()
                .map(UserAiModelAliasVo::from)
                .toList();
        return Result.success(vos);
    }

    @Operation(summary = "新建 alias(alias 名不含冒号;同 user 内大小写不敏感唯一)")
    @PostMapping
    public Result<UserAiModelAliasVo> create(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UserAiModelAliasDto dto) {
        UserAiModelAlias saved = aliasService.create(principal.userId(), dto);
        return Result.success("alias 已创建", UserAiModelAliasVo.from(saved));
    }

    @Operation(summary = "查询 alias 详情(所有权校验)")
    @GetMapping("/{id}")
    public Result<UserAiModelAliasVo> get(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return Result.success(UserAiModelAliasVo.from(aliasService.findById(principal.userId(), id)));
    }

    @Operation(summary = "修改 alias(partial update;alias 改名会失效旧 + 新 cache key)")
    @PutMapping("/{id}")
    public Result<UserAiModelAliasVo> update(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UserAiModelAliasDto dto) {
        UserAiModelAlias saved = aliasService.update(principal.userId(), id, dto);
        return Result.success("alias 已更新", UserAiModelAliasVo.from(saved));
    }

    @Operation(summary = "硬删除 alias(命中该 alias 的 chat 调用 fall through 到原优先级)")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        aliasService.delete(principal.userId(), id);
        return Result.success("alias 已删除", null);
    }
}

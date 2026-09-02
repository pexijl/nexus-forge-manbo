package com.nexusforge.ai.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户 model alias 创建 / 修改请求体(Phase 4 模型别名)。
 *
 * <p>设计原则 — <b>partial update 友好</b>:所有可选字段都是包装类型(null = 不传),
 * {@code UserAiModelAliasService.applyDto} 只在非 null 时覆盖 entity。
 *
 * <p>create 必填:
 * <ul>
 *   <li>{@code alias} — 用户友好名(同 user 内唯一,大小写不敏感,<b>不含冒号</b>)</li>
 *   <li>{@code targetVendor} — 解析目标 vendor</li>
 *   <li>{@code targetModel} — 解析目标 model</li>
 * </ul>
 *
 * <p>update 允许改所有字段;{@code alias} 改了会触发 alias 缓存键迁移
 * (旧 key 失效,新 key 通过事件触发下次查询回填)。
 *
 * <h3>target 校验策略</h3>
 * <ul>
 *   <li>target_vendor / target_model 只校验非空 + 长度,不做 catalog 存在性校验 —
 *       alias 可以在 admin 还没把 model 加到 catalog 之前先建好,后续 admin 加进 catalog
 *       后 alias 自动生效(更灵活的 UX)</li>
 *   <li>运行时 resolver 改写 alias 为 "vendor:model" 后,会走 catalog 校验 —
 *       target 不存在或 disabled 抛 {@code LLM_MODEL_NOT_FOUND} / {@code LLM_MODEL_DISABLED}</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "用户 model alias 创建/修改请求体(Phase 4 模型别名)")
public class UserAiModelAliasDto {

    /**
     * 用户友好别名(create 必填,update 允许改)。
     * <p>不含冒号(避免与 "vendor:model" 格式冲突)。
     * <p>同 user 内大小写不敏感唯一(已有"我的 GPT"再建"我的 gpt"会被拒)。
     */
    @Schema(description = "别名(create 必填,update 允许改;不含冒号;同 user 内大小写不敏感唯一)",
            example = "我的 GPT")
    @NotBlank(message = "别名不能为空")
    @Size(max = 64)
    @Pattern(regexp = "^[^:]+$", message = "别名不能含冒号(避免与 vendor:model 格式冲突)")
    private String alias;

    /** 解析目标 vendor(create / update 必填) */
    @Schema(description = "解析目标 vendor(命中后改写为 targetVendor:targetModel)",
            example = "openai")
    @NotBlank(message = "targetVendor 不能为空")
    @Size(max = 32)
    private String targetVendor;

    /** 解析目标 model(create / update 必填) */
    @Schema(description = "解析目标 model(命中后改写为 targetVendor:targetModel)",
            example = "gpt-4o-mini")
    @NotBlank(message = "targetModel 不能为空")
    @Size(max = 128)
    private String targetModel;

    @Schema(description = "alias 描述(给用户 UI 看)")
    @Size(max = 2000)
    private String description;

    @Schema(description = "是否启用(false 时 alias 跳过,fall through 到原优先级)")
    private Boolean enabled;
}

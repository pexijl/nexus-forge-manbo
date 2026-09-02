package com.nexusforge.ai.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户 AI 代理创建 / 修改请求体(Phase 3 BYOK 多端点)。
 *
 * <p>设计原则 — <b>partial update 友好</b>:所有可选字段都是包装类型
 * (null = 不传),{@code UserAiProxyService.applyDto} 只在非 null 时覆盖 entity。
 *
 * <p>create 必填:
 * <ul>
 *   <li>{@code name} — 用户自定义别名(同 user 内唯一)</li>
 *   <li>{@code vendor} — OpenAI 协议家族 vendor(AiVendorRegistry 校验)</li>
 *   <li>{@code baseUrl} — 独立 base URL(覆盖 vendor 默认)</li>
 *   <li>{@code apiKey} — 明文 API Key,服务端 AES-256-GCM 加密后存 BYTEA</li>
 * </ul>
 *
 * <p>update 禁止改:vendor(改了会跟 cache key 失配,走 delete + create 更安全)。
 * {@code name} 允许改(不影响协议层 — 改完缓存键就旧了,5 min TTL 兜底)。
 *
 * <p>{@link #apiKey} 三态(跟 {@code UpdatePreferenceDto} 保持一致):
 * <ul>
 *   <li>留空 + {@code clearApiKey=false} → 沿用现有 Key</li>
 *   <li>非空 → 覆盖现有 Key</li>
 *   <li>{@code clearApiKey=true} → 清除现有 Key</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "用户 AI 代理创建/修改请求体(Phase 3 BYOK)")
public class UserAiProxyDto {

    /** create 必填,update 允许改(同 user 内仍需唯一) */
    @Schema(description = "代理别名(create 必填,update 可改;同 user 内唯一)", example = "我的 DeepSeek 中转")
    @NotBlank(message = "代理别名不能为空")
    @Size(max = 64)
    private String name;

    /** create 必填,update 禁止改(协议层标识,改了就走 delete + create) */
    @Schema(description = "vendor(create 必填,update 不允许改)", example = "deepseek",
            allowableValues = {"openai", "deepseek", "dashscope", "glm", "kimi",
                    "doubao", "hunyuan", "siliconflow", "oneapi", "openrouter",
                    "minimax", "ollama"})
    @NotBlank(message = "vendor 不能为空")
    @Size(max = 32)
    private String vendor;

    /** create 必填,update 允许改 */
    @Schema(description = "独立 base URL(覆盖 vendor 默认)",
            example = "https://api.deepseek.com/v1")
    @NotBlank(message = "baseUrl 不能为空(BYOK 场景必填)")
    @Size(max = 512)
    private String baseUrl;

    /**
     * API Key 明文。
     * <ul>
     *   <li>create 时:必填(留空会被 service 拒绝)</li>
     *   <li>update 时:留空 + {@code clearApiKey=false} → 沿用;非空 → 覆盖;{@code clearApiKey=true} → 清除</li>
     * </ul>
     */
    @Schema(description = "API Key 明文(create 必填;update 时留空=沿用,非空=覆盖)",
            example = "sk-...")
    @Size(max = 512)
    private String apiKey;

    /** 显式清除 Key(update 时与 apiKey 同时存在时优先) */
    @Schema(description = "显式清除 API Key(update 时用,回退到无 Key 状态 — 当前等价于禁用)")
    private Boolean clearApiKey;

    /** 可选:该 proxy 默认 model(留空 = 走 vendor yaml 默认) */
    @Schema(description = "该 proxy 默认 model(留空走 vendor yaml 默认)",
            example = "deepseek-chat")
    @Size(max = 128)
    private String defaultModel;

    @Schema(description = "是否启用(false 时该 proxy 整体被网关拒绝)")
    private Boolean enabled;

    @Schema(description = "是否标记为用户当前活跃代理(true 时同 user 下其他代理的 is_default 会被自动 unmark)")
    private Boolean isDefault;

    @Schema(description = "代理描述(给用户 UI 看)")
    @Size(max = 2000)
    private String description;
}

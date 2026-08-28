package com.nexusforge.ai.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PUT /api/ai/preference 请求体。
 *
 * <p>语义:用户提交自己的偏好。"模式"通过 {@code apiKey} 是否填写决定:
 * <ul>
 *   <li>apiKey 留空 → 仅覆盖 vendor/model,Key 仍用系统共享(USER_OVERRIDE_SYSTEM_KEY)</li>
 *   <li>apiKey 非空 → 用户提供私 Key(USER_PRIVATE_KEY),明文入库前 AES-256-GCM 加密</li>
 * </ul>
 *
 * <p>{@code clearApiKey=true} 时,清除已有私 Key,回退到 USER_OVERRIDE_SYSTEM_KEY。
 */
@Data
public class UpdatePreferenceDto {

    @NotBlank(message = "vendor 不能为空")
    @Size(max = 32)
    private String vendor;

    @NotBlank(message = "model 不能为空")
    @Size(max = 128)
    private String model;

    /** 可选:用户私 Key(明文,服务端加密后存 BYTEA)。留空表示沿用现有 Key(若有) */
    @Size(max = 512)
    private String apiKey;

    /** 显式清除私 Key(回退到 USER_OVERRIDE_SYSTEM_KEY);与 apiKey 同时存在时优先 */
    private Boolean clearApiKey;
}
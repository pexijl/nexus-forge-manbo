package com.nexusforge.ai.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * vendor 系统 API Key 设置/轮换请求体(Phase 6)。
 *
 * <p>只接 {@code apiKey} 明文 — 后端用 {@code ApiKeyCipher} 加密后存入
 * {@code ai_vendor_config.encrypted_api_key},并同时算 {@code api_key_fingerprint}
 * 存展示用列。
 *
 * <p>为什么独立 DTO(不合并进 {@link VendorConfigDto}):
 * <ul>
 *   <li>Key 轮换是安全敏感操作,独立端点便于审计 / 鉴权 / 2FA 等扩展</li>
 *   <li>避免 {@code VendorConfigDto} 的 partial update 语义被误用:
 *       "PUT /{vendor} 不传 apiKey" 不应被理解成"清空 key"</li>
 *   <li>清空 Key 用 {@code DELETE /api/admin/ai/vendors/{vendor}/api-key},
 *       不通过 {@code clearApiKey=true} 这种"三态"语义(避免歧义)</li>
 * </ul>
 */
@Data
@Schema(description = "vendor 系统 API Key 设置/轮换请求体")
public class VendorApiKeyDto {

    @Schema(description = "API Key 明文;后端用 ApiKeyCipher AES-256-GCM 加密后入库",
            example = "sk-prod-...", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "apiKey 不能为空")
    @Size(max = 512, message = "apiKey 长度不超过 512 字符")
    private String apiKey;
}

package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * vendor API Key 状态响应 VO(Phase 6)。
 *
 * <p>只透出"是否已设置 + 指纹 + 更新时间",绝不暴露密文或明文;
 * 跟 {@link VendorConfigVo} 一起拼出 vendor 完整配置视图。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "vendor 系统 API Key 状态(不暴露密文)")
public class VendorApiKeyVo {

    @Schema(description = "vendor", example = "openai")
    private String vendor;

    @Schema(description = "是否已设置系统 Key(true=DB 有密文 / false=走 yaml 兜底)")
    private Boolean hasApiKey;

    @Schema(description = "Key 指纹(已设置时返回);形如 sk-1••••a3b4c5d6")
    private String apiKeyFingerprint;

    @Schema(description = "最近一次 Key 更新时间(已设置时返回);清空或从未设置时为 null")
    private OffsetDateTime updatedAt;

    public static VendorApiKeyVo from(String vendor, boolean hasApiKey,
                                      String fingerprint, OffsetDateTime updatedAt) {
        return VendorApiKeyVo.builder()
                .vendor(vendor)
                .hasApiKey(hasApiKey)
                .apiKeyFingerprint(fingerprint)
                .updatedAt(updatedAt)
                .build();
    }
}

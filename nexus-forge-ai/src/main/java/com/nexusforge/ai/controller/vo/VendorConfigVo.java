package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.entity.AiVendorConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * vendor 配置响应 VO(Phase 2,Phase 6 增 apiKey 状态字段)。
 *
 * <p>Phase 6 起透出 {@code hasApiKey} + {@code apiKeyFingerprint},admin 列表页
 * 一眼能看出"哪些 vendor 的 system key 是 DB 覆盖,哪些走 yaml 兜底";密文本身
 * 由 {@code @JsonIgnore} 守住,本 VO 不透密文。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "vendor 配置条目")
public class VendorConfigVo {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "vendor", example = "openai")
    private String vendor;

    @Schema(description = "base URL", example = "https://api.openai.com/v1")
    private String baseUrl;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "是否已设置系统 Key(true=DB 覆盖 / false=走 yaml 兜底);Phase 6 新增")
    private Boolean hasApiKey;

    @Schema(description = "Key 指纹(已设置时返回);Phase 6 新增")
    private String apiKeyFingerprint;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;

    public static VendorConfigVo from(AiVendorConfig m) {
        if (m == null) return null;
        boolean hasApiKey = m.getEncryptedApiKey() != null;
        return VendorConfigVo.builder()
                .id(m.getId())
                .vendor(m.getVendor())
                .baseUrl(m.getBaseUrl())
                .enabled(m.getEnabled())
                .description(m.getDescription())
                .hasApiKey(hasApiKey)
                .apiKeyFingerprint(hasApiKey ? m.getApiKeyFingerprint() : null)
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}

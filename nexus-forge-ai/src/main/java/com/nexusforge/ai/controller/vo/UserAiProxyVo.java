package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.entity.UserAiProxy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户 AI 代理响应 VO。
 *
 * <p>永远不返回密文;只返回 {@code apiKeyFingerprint} 给 UI 展示。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "用户 AI 代理响应")
public class UserAiProxyVo {

    @Schema(description = "代理 ID")
    private Long id;

    @Schema(description = "所属 user ID")
    private Long userId;

    @Schema(description = "代理别名")
    private String name;

    @Schema(description = "vendor")
    private String vendor;

    @Schema(description = "独立 base URL")
    private String baseUrl;

    @Schema(description = "是否配置了 API Key(永远 true,创建时必填)")
    private Boolean hasApiKey;

    @Schema(description = "Key 指纹(展示用,如 'sk-1a••••a3b4c2d1')")
    private String apiKeyFingerprint;

    @Schema(description = "该 proxy 默认 model(null = 走 vendor yaml 默认)")
    private String defaultModel;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "是否为用户当前活跃代理")
    private Boolean isDefault;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;

    public static UserAiProxyVo from(UserAiProxy p) {
        if (p == null) return null;
        return UserAiProxyVo.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .name(p.getName())
                .vendor(p.getVendor())
                .baseUrl(p.getBaseUrl())
                .hasApiKey(p.getEncryptedApiKey() != null && p.getEncryptedApiKey().length > 0)
                .apiKeyFingerprint(p.getApiKeyFingerprint())
                .defaultModel(p.getDefaultModel())
                .enabled(p.getEnabled())
                .isDefault(Boolean.TRUE.equals(p.getIsDefault()))
                .description(p.getDescription())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}

package com.nexusforge.ai.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * GET /api/ai/preference 响应。
 *
 * <p>永远只返回"展示用"信息;不会回传密文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PreferenceVo {

    /** 是否存在用户偏好行 */
    private boolean customized;

    /** vendor 实际生效值 */
    private String vendor;

    /** model 实际生效值 */
    private String model;

    /** 当前模式:GLOBAL / OVERRIDE_SYSTEM / PRIVATE */
    private String mode;

    /** 用户是否配了私 Key(展示用,不暴露真值) */
    private boolean hasApiKey;

    /** Key 指纹(展示用,如 "sk-1a••••a3b4c2d1") */
    private String apiKeyFingerprint;

    private OffsetDateTime updatedAt;
}
package com.nexusforge.ai.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * vendor 配置修改请求体 — partial update 友好(null = 不传)。
 *
 * <p>Phase 2 范围:admin 改已存在的 vendor(base_url / enabled / description)。
 * 新建 vendor 走 yaml + seed runner(避免"DB 有 vendor 但 yaml 没 api-key"
 * 的半残状态),不在 Phase 2 范围。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "vendor 配置修改请求体(partial update)")
public class VendorConfigDto {

    @Schema(description = "OpenAI 兼容协议 base URL;改完私 Key 路径立即生效,系统 Key 路径需重启或 Phase 4 hot reload",
            example = "https://api.openai.com/v1")
    @Size(max = 512)
    private String baseUrl;

    @Schema(description = "是否启用;false 时该 vendor 整体被网关拒绝",
            example = "true")
    private Boolean enabled;

    @Schema(description = "vendor 描述(给 admin UI 看)")
    @Size(max = 2000)
    private String description;
}

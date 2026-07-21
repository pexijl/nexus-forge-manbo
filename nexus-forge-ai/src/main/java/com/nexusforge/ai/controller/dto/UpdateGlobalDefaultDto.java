package com.nexusforge.ai.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PUT /api/admin/ai/global-default 管理员请求体。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateGlobalDefaultDto {

    @NotBlank
    @Size(max = 32)
    private String vendor;

    @NotBlank
    @Size(max = 128)
    private String model;

    private Boolean enabled;
}
package com.nexusforge.controller.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "token 用量")
public class UsageVo {

    @Schema(description = "输入 token 数")
    private Integer promptTokens;

    @Schema(description = "输出 token 数")
    private Integer completionTokens;

    @Schema(description = "总 token 数")
    private Integer totalTokens;
}
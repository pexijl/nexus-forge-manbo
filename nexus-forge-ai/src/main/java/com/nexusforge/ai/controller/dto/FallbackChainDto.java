package com.nexusforge.ai.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 降级链替换请求体(Phase 7 — fallback-chain 策略 DB 化)。
 *
 * <p>全量替换语义:PUT 进来的 {@code vendors} 列表会成为新的降级链
 * (DB 唯一一行 id=1,upsert)。空 list 表示"运营显式禁用降级",
 * 跟"DB 无行 → 走 yaml"由 service 区分(语义不同)。
 *
 * <p>校验:
 * <ul>
 *   <li>{@code vendors} 本身不能 null,但允许空 list</li>
 *   <li>每项非空,长度不超过 64(vendor 名长度上限,跟 {@code AiVendorConfig.vendor} 对齐)</li>
 *   <li>每项 vendor 名必须存在于 {@code spring.ai.providers.*}(yaml 视角)— 这层校验
 *       在 service 内做(不放在 Bean Validation,因为需要查 yaml props)</li>
 *   <li>列表最多 16 项(防误操作,降级链通常 2-4 个 vendor)</li>
 * </ul>
 *
 * <p>为什么不接 PATCH(部分更新):降级链的语义就是有序列表,
 * 插入/删除某项的语义模糊(PUT 整条更明确,跟 {@code VendorConfigDto} 一致)。
 */
@Data
@Schema(description = "降级链替换请求体(全量)")
public class FallbackChainDto {

    @Schema(description = "降级链 vendor 顺序列表(从次选到末选);首选由请求的 (vendor, model) 决定,不在本链中",
            example = "[\"ollama\", \"openai\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "vendors 不能为 null(空 list 表示显式禁用降级)")
    @Size(max = 16, message = "降级链最多 16 vendor")
    private List<@NotBlank(message = "vendor 名不能为空") @Size(max = 64, message = "vendor 名长度不超过 64") String> vendors;
}

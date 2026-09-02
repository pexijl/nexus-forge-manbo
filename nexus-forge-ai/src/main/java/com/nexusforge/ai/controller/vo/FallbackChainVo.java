package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainSource;
import com.nexusforge.ai.service.FallbackChainService.FallbackChainView;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 降级链状态响应 VO(Phase 7 — fallback-chain 策略 DB 化)。
 *
 * <p>GET /api/admin/ai/fallback-chain 响应,PUT 成功也返同样结构(回显更新后)。
 *
 * <p>{@code source} 字段让 admin 一眼看出当前生效值的来源:
 * <ul>
 *   <li>{@code DB} — DB 有行(可能 vendors 为空),完全用 DB 覆盖 yaml</li>
 *   <li>{@code YAML_FALLBACK} — DB 无行,走 yaml 兜底</li>
 *   <li>{@code EMPTY} — DB 无行,yaml 也空,空降级链(无降级)</li>
 * </ul>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "降级链状态(生效来源 + 当前 vendor 列表)")
public class FallbackChainVo {

    @Schema(description = "当前生效的降级链(有序 vendor 名);空 = 无降级")
    private List<String> vendors;

    @Schema(description = "生效来源(DB / YAML_FALLBACK / EMPTY);让 admin 一眼看出当前值是从哪来的",
            example = "DB")
    private FallbackChainSource source;

    @Schema(description = "最近一次更新时间(DB 命中时返回);yaml 兜底或空时为 null")
    private OffsetDateTime updatedAt;

    public static FallbackChainVo from(FallbackChainView view) {
        return FallbackChainVo.builder()
                .vendors(view.vendors())
                .source(view.source())
                .updatedAt(view.updatedAt())
                .build();
    }
}

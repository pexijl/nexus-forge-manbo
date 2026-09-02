package com.nexusforge.ai.controller.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusforge.ai.entity.AiApiKeyAuditLog;
import com.nexusforge.ai.enums.VendorApiKeyAuditAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * AI vendor 系统 API Key 轮换审计日志 VO(Phase 8)。
 *
 * <p>给 {@code AiAdminApiKeyAuditController} 用,把 {@link AiApiKeyAuditLog} entity
 * 转成对外展示结构。注意:
 * <ul>
 *   <li>不暴露密文 / 明文(只有 fingerprint_before / fingerprint_after 是密文摘要,本身不泄露)</li>
 *   <li>actorId / actorRole / reason / metadata 是审计上下文,全部透出(运营查就是要看这些)</li>
 *   <li>{@code @JsonInclude(NON_NULL)} 隐藏 null 字段(actorId / reason 可空)</li>
 * </ul>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "API Key 轮换审计日志 VO(不含密文,仅指纹摘要)")
public class VendorApiKeyAuditLogVo {

    @Schema(description = "审计行 id")
    private Long id;

    @Schema(description = "动作:SET(设置/轮换) / CLEAR(清空)")
    private VendorApiKeyAuditAction action;

    @Schema(description = "被操作的 vendor 名(metadata.vendor 提取出来便于前端展示)")
    private String vendor;

    @Schema(description = "操作人 id(来自 SecurityContext;null 表示 SYSTEM 内部事件)")
    private Long actorId;

    @Schema(description = "操作人角色:ADMIN / SYSTEM")
    private String actorRole;

    @Schema(description = "人类可读原因(Phase 8 暂不接 controller 传入,留 null)")
    private String reason;

    @Schema(description = "改前 fingerprint(密文摘要;null 表示首次装 key;SET 第一次 / 不变 SET 都有值)")
    private String fingerprintBefore;

    @Schema(description = "改后 fingerprint(SET 总是有;CLEAR 总是 null)")
    private String fingerprintAfter;

    @Schema(description = "HTTP 客户端 IP(从 HttpServletRequest.getRemoteAddr() 拿;代理场景待 Phase 9 增强)")
    private String requestIp;

    @Schema(description = "发生时间")
    private OffsetDateTime createdAt;

    /**
     * 从 entity + metadata 抽提字段构造 VO。{@code fingerprint_before} / {@code fingerprint_after}
     * / {@code request_ip} / {@code vendor} 从 metadata 拿(冗余存,便于过滤;VO 提出来扁平化展示)。
     */
    public static VendorApiKeyAuditLogVo from(AiApiKeyAuditLog row) {
        Map<String, Object> meta = row.getMetadata();
        String vendor = meta == null ? null : (String) meta.get("vendor");
        String fpBefore = meta == null ? null : (String) meta.get("fingerprint_before");
        String fpAfter = meta == null ? null : (String) meta.get("fingerprint_after");
        String reqIp = meta == null ? null : (String) meta.get("request_ip");

        return VendorApiKeyAuditLogVo.builder()
                .id(row.getId())
                .action(row.getAction())
                .vendor(vendor)
                .actorId(row.getActorId())
                .actorRole(row.getActorRole())
                .reason(row.getReason())
                .fingerprintBefore(fpBefore)
                .fingerprintAfter(fpAfter)
                .requestIp(reqIp)
                .createdAt(row.getCreatedAt())
                .build();
    }
}

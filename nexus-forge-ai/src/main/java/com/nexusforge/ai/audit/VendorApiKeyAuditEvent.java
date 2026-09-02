package com.nexusforge.ai.audit;

import com.nexusforge.ai.enums.VendorApiKeyAuditAction;

/**
 * AI vendor 系统 API Key 轮换审计事件(Phase 8)。
 *
 * <p>由 {@code VendorConfigService.setApiKey / clearApiKey} 构造并传给
 * {@code VendorApiKeyAuditLogger.log}。
 *
 * <h3>为什么不复用 {@code com.nexusforge.audit.AuditEvent}</h3>
 * 通用 {@code AuditEvent} 的 {@code userId: Long} 字段语义绑死"被操作的用户",
 * 但 apiKey 轮换的主体是 vendor({@code String}),语义错位;
 * 且 nexus-forge-ai 不依赖 nexus-forge-user,跨模块引用要新增模块依赖。
 * 故 Phase 8 在 ai 模块下自建一个领域事件 record,跟通用接口同风格但不复用。
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>{@code action} — SET / CLEAR,必填</li>
 *   <li>{@code vendor} — 被操作的 vendor 名(冗余存 metadata,便于按 vendor 过滤),必填</li>
 *   <li>{@code actorId} — 操作人 id;NULL 时 logger 记 actor_role=SYSTEM(内部事件)</li>
 *   <li>{@code actorRole} — "ADMIN" / "SYSTEM";NULL 时 logger 兜底 "SYSTEM"</li>
 *   <li>{@code reason} — 人类可读原因(可空;Phase 8 暂不接 controller 传入)</li>
 *   <li>{@code fingerprintBefore} — 改前 fingerprint(SET 第一次为 null;CLEAR 总是有)</li>
 *   <li>{@code fingerprintAfter} — 改后 fingerprint(SET 总是有;CLEAR 总是 null)</li>
 *   <li>{@code requestIp} — HTTP 客户端 IP(从 {@code HttpServletRequest} 拿;可空)</li>
 * </ul>
 *
 * <p>通过对比 fingerprintBefore vs fingerprintAfter 可推断
 * "新装(SET, before=null) / 轮换(SET, before!=null) / 清空(CLEAR)"。
 */
public record VendorApiKeyAuditEvent(
        VendorApiKeyAuditAction action,
        String vendor,
        Long actorId,
        String actorRole,
        String reason,
        String fingerprintBefore,
        String fingerprintAfter,
        String requestIp
) {
}

package com.nexusforge.ai.audit;

import com.nexusforge.ai.entity.AiApiKeyAuditLog;
import com.nexusforge.ai.repository.AiApiKeyAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI vendor 系统 API Key 轮换审计 logger(Phase 8) ——
 * 写 {@code ai_api_key_audit_log} 表。
 *
 * <h3>跟 {@code AccountLifecycleAuditLogger} 的对齐</h3>
 * <ul>
 *   <li>不抛异常 — 审计失败 log warn,不影响主业务(setApiKey / clearApiKey 不该因为审计表不可用而失败)</li>
 *   <li>线程安全 — 单例 Bean</li>
 *   <li>同步 — 在调用方事务内完成,审计与主操作原子性;但 logger 内部 try/catch
 *       不会让事务回滚,只是 log warn</li>
 * </ul>
 *
 * <h3>为什么不实现通用 {@code com.nexusforge.audit.AuditLogger}</h3>
 * 通用接口的 {@code userId: Long} 字段语义错位(见 {@code VendorApiKeyAuditEvent} Javadoc);
 * Phase 8 在 ai 模块下自建,保留风格但不复用接口,避免给通用接口加 {@code vendorKey} 这种
 * 领域耦合字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorApiKeyAuditLogger {

    private final AiApiKeyAuditLogRepository repo;

    /**
     * 写一条审计事件。失败 log warn 不抛错。
     */
    public void log(VendorApiKeyAuditEvent event) {
        // 基本参数校验 — 缺关键字段视为"调用方错误",log error 即可
        if (event == null) {
            log.error("[audit] VendorApiKeyAuditLogger.log called with null event");
            return;
        }
        if (event.action() == null) {
            log.error("[audit] VendorApiKeyAuditLogger.log called with null action, vendor={}",
                    event.vendor());
            return;
        }
        if (event.vendor() == null || event.vendor().isBlank()) {
            log.error("[audit] VendorApiKeyAuditLogger.log called with null/blank vendor, action={}",
                    event.action());
            return;
        }

        String role = (event.actorRole() == null || event.actorRole().isBlank()) ? "SYSTEM" : event.actorRole();

        try {
            AiApiKeyAuditLog row = new AiApiKeyAuditLog();
            row.setAction(event.action());
            row.setActorId(event.actorId());
            row.setActorRole(role);
            row.setReason(event.reason());
            row.setMetadata(buildMetadata(event));
            repo.save(row);
        } catch (Exception e) {
            // 不抛 — 审计失败不影响主业务
            log.warn("[audit] failed to log apiKey audit event vendor={} action={} actor={}: {}",
                    event.vendor(), event.action(), event.actorId(), e.getMessage());
        }
    }

    private static Map<String, Object> buildMetadata(VendorApiKeyAuditEvent e) {
        // LinkedHashMap 保 JSONB key 顺序(调试 / 视觉对齐更友好)
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendor", e.vendor());
        m.put("fingerprint_before", e.fingerprintBefore());
        m.put("fingerprint_after", e.fingerprintAfter());
        m.put("request_ip", e.requestIp());
        return m;
    }
}

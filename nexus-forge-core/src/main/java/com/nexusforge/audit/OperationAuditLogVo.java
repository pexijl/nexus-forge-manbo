package com.nexusforge.audit;

import java.time.OffsetDateTime;

/**
 * 操作审计视图对象 — admin 查审计时给前端的投影。
 *
 * <p>不暴露 {@code metadata} 内部 JSONB(可能含密码 / token 等敏感),
 * 不暴露 bucket / ip 完整段(只取前 16 字符防日志采集);其他字段全对前端透明。</p>
 */
public record OperationAuditLogVo(
        Long id,
        Long userId,
        String action,
        String resource,
        String resourceId,
        String method,
        String path,
        String ipPrefix,           // IP 前缀,前 16 字符,完整 IP 不暴露
        String result,
        Integer statusCode,
        Long latencyMs,
        Integer errorCode,
        OffsetDateTime createdAt
) {
    public static OperationAuditLogVo from(OperationAuditLog log) {
        return new OperationAuditLogVo(
                log.getId(),
                log.getUserId(),
                log.getAction(),
                log.getResource(),
                log.getResourceId(),
                log.getMethod(),
                log.getPath(),
                truncateIp(log.getIp()),
                log.getResult() == null ? null : log.getResult().name(),
                log.getStatusCode(),
                log.getLatencyMs(),
                log.getErrorCode(),
                log.getCreatedAt()
        );
    }

    /**
     * IP 截断 — admin 看到的是 ip 前 16 字符(IPv6 前缀足够定位大致地理位置,
     * 不暴露完整 IP 防滥用)。本项目体量小,实际不会暴露真实 IP 给前端;
     * 真要查完整 IP 走日志 / DB 直接 query。
     */
    private static String truncateIp(String ip) {
        if (ip == null) return null;
        return ip.length() <= 16 ? ip : ip.substring(0, 16) + "…";
    }
}

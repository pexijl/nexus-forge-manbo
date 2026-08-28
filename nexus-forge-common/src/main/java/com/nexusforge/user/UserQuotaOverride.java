package com.nexusforge.user;

/**
 * P5 Step 6 — 用户级配额覆盖 DTO(跨模块传输对象)。
 *
 * <p>从 {@code users.plan_quota_override} JSON 列解析而来。
 * 与 {@code AiProperties.QuotaTier} 字段对齐,但不依赖 nexus-forge-ai 模块。
 *
 * @param dailyTokenLimit 24h token 上限,null 表示不限
 * @param requestLimit    24h 请求数上限,null 表示不限
 */
public record UserQuotaOverride(Long dailyTokenLimit, Long requestLimit) {
}

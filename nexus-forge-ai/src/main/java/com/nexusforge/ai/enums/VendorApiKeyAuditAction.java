package com.nexusforge.ai.enums;

import lombok.Getter;

/**
 * AI vendor 系统 API Key 轮换审计动作枚举(Phase 8)。
 *
 * <p>写入 {@code ai_api_key_audit_log.action} 列。
 * 业务侧 {@code VendorConfigService.setApiKey / clearApiKey} 各自选对应 action;
 * 审计层 {@code VendorApiKeyAuditLogger} 不重复声明。
 *
 * <p><b>为什么不复用 {@code com.nexusforge.user.enums.AccountLifecycleAction}</b>:
 * 审计主体不同 — 账号生命周期是"被操作的用户",apiKey 轮换是"被操作的 vendor";
 * Phase 8 在 ai 模块下,跨模块复用枚举会污染 enum 边界,且新场景也容易加新值
 * (比如 Phase 9 扩展 {@code DECRYPT_FAILED} 标记主密钥轮换事件),用自己 enum 更干净。
 *
 * <h3>当前支持的动作</h3>
 * <ul>
 *   <li>{@link #SET} — 设置/轮换 vendor system apiKey(走 {@code PUT /api/admin/ai/vendors/{v}/api-key})</li>
 *   <li>{@link #CLEAR} — 清空 DB 密文,回退 yaml 兜底(走 {@code DELETE /api/admin/ai/vendors/{v}/api-key})</li>
 * </ul>
 */
@Getter
public enum VendorApiKeyAuditAction {
    /** 设置/轮换 system apiKey — DB 写入新密文 + 新 fingerprint */
    SET("设置/轮换系统 API Key"),
    /** 清空 system apiKey — DB 密文 + fingerprint 置 NULL,回退 yaml */
    CLEAR("清空系统 API Key(回退 yaml)");

    private final String description;

    VendorApiKeyAuditAction(String description) {
        this.description = description;
    }
}

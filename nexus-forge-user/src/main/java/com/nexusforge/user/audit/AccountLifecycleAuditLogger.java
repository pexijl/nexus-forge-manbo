package com.nexusforge.user.audit;

import com.nexusforge.audit.AuditEvent;
import com.nexusforge.audit.AuditLogger;
import com.nexusforge.user.entity.AccountLifecycleLog;
import com.nexusforge.user.enums.AccountActorRole;
import com.nexusforge.user.enums.AccountLifecycleAction;
import com.nexusforge.user.repository.AccountLifecycleLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 账号生命周期审计实现 —— 写 {@code account_lifecycle_log} 表。
 *
 * <p>与通用 {@link AuditLogger} 契约一致:</p>
 * <ul>
 *   <li>不抛异常 —— 失败 log warn,不影响主业务</li>
 *   <li>线程安全 —— 单例 Bean</li>
 *   <li>尽量同步 —— 在调用方事务内完成,保证审计与主操作原子性</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountLifecycleAuditLogger implements AuditLogger<AccountLifecycleAction> {

    private final AccountLifecycleLogRepository repo;

    @Override
    public void log(AuditEvent<AccountLifecycleAction> event) {
        // 基本参数校验 —— 缺关键字段视为"调用方错误",log error 即可
        if (event.userId() == null) {
            log.error("[audit] AccountLifecycleAuditLogger.log called with null userId, action={}",
                    event.action());
            return;
        }
        if (event.action() == null) {
            log.error("[audit] AccountLifecycleAuditLogger.log called with null action, userId={}",
                    event.userId());
            return;
        }
        AccountActorRole role = parseRole(event.actorRole());

        try {
            AccountLifecycleLog row = new AccountLifecycleLog();
            row.setUserId(event.userId());
            row.setAction(event.action());
            row.setActorId(event.actorId());
            row.setActorRole(role);
            row.setReason(event.reason());
            row.setMetadata(event.metadata());
            repo.save(row);
        } catch (Exception e) {
            // 不抛 —— 审计失败不影响主业务
            log.warn("[audit] failed to log account_lifecycle event userId={} action={}: {}",
                    event.userId(), event.action(), e.getMessage());
        }
    }

    private static AccountActorRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return AccountActorRole.SYSTEM;
        }
        try {
            return AccountActorRole.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[audit] unknown actorRole={}, fallback to SYSTEM", raw);
            return AccountActorRole.SYSTEM;
        }
    }
}

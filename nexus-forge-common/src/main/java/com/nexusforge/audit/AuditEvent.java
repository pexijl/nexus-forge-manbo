package com.nexusforge.audit;

import lombok.Builder;

import java.util.Map;

/**
 * 审计事件 —— 通用领域对象,所有模块共用。
 *
 * <p>由 {@link AuditLogger} 持久化到各自的存储后端(账号生命周期
 * 用 {@code account_lifecycle_log};后续模块可加 {@code biz_audit_log}
 * 等)。{@code metadata} 用 map 表达上下文,避免每加一个字段就改表结构。</p>
 *
 * <p>字段语义:</p>
 * <ul>
 *   <li>{@code userId} —— 事件主体(谁被操作了),必填</li>
 *   <li>{@code action} —— 业务动作字符串(自由格式,如 {@code BAN} / {@code DELETE_CONFIRM} /
 *       {@code PASSWORD_RESET_CONFIRM});AuditLogger 实现可以做合法性校验</li>
 *   <li>{@code actorId} —— 操作人 id;空时表示 SYSTEM(定时任务 / 内部事件)</li>
 *   <li>{@code actorRole} —— 操作人角色;{@code SELF} / {@code ADMIN} / {@code SYSTEM} 等</li>
 *   <li>{@code reason} —— 人类可读的原因(封禁理由 / 注销备注)</li>
 *   <li>{@code metadata} —— 上下文(IP / user-agent / 原 status / 改前改后值等)</li>
 * </ul>
 *
 * @param <A> action 类型,模块可用枚举约束(默认 String)
 */
@Builder
public record AuditEvent<A>(
        Long userId,
        A action,
        Long actorId,
        String actorRole,
        String reason,
        Map<String, Object> metadata
) {
}

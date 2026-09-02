package com.nexusforge.user.enums;

import lombok.Getter;

/**
 * 账号生命周期操作人角色 —— 写入 {@code account_lifecycle_log.actor_role} 列。
 *
 * <p>决定日志归属 / 后续合规审计口径。</p>
 */
@Getter
public enum AccountActorRole {
    /** 用户本人(自助操作) */
    SELF,
    /** 管理员 */
    ADMIN,
    /** 系统 / 定时任务 */
    SYSTEM
}

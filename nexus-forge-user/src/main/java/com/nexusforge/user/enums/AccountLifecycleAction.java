package com.nexusforge.user.enums;

import lombok.Getter;

/**
 * 账号生命周期动作枚举 —— 写入 {@code account_lifecycle_log.action} 列。
 *
 * <p>业务侧调用 {@code AccountLifecycleService} 各方法时会自动选对应 action;
 * 审计层不需要重复声明。</p>
 */
@Getter
public enum AccountLifecycleAction {
    /** 管理员封禁 */
    BAN("封禁"),
    /** 管理员解封 */
    UNBAN("解封"),
    /** 用户申请注销(发确认邮件) */
    DELETE_REQUEST("注销申请"),
    /** 用户确认注销(已执行) */
    DELETE_CONFIRM("注销确认"),
    /** 用户撤销注销(冷却期内恢复) */
    RESTORE("恢复账号"),
    /** Grace period 过期定时真删 */
    HARD_DELETE("过期真删");

    private final String description;

    AccountLifecycleAction(String description) {
        this.description = description;
    }
}

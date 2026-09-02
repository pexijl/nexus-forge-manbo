package com.nexusforge.enums;

import lombok.Getter;

/**
 * 用户状态枚举。
 *
 * <p>状态语义:
 * <ul>
 *   <li>{@code ACTIVE}     (1)   正常,所有功能可用</li>
 *   <li>{@code INACTIVE}   (0)   未激活/暂时停用,可由管理员恢复</li>
 *   <li>{@code BANNED}     (-1)  已封禁,触发 {@code UserBannedEvent} 踢下线;
 *       业务上"软禁用",通常用于违规处理</li>
 *   <li>{@code DELETED}    (-2)  <b>已废弃</b>,见下</li>
 * </ul>
 *
 * <h3>{@code DELETED} 废弃说明(2026-08-28)</h3>
 * <p>早期"软删除"通过 {@code status = DELETED} 实现,与 {@code BANNED}
 * 语义重叠且容易混淆。自 {@code BaseEntity} 引入 {@code deleted_at}
 * 字段后,所有"软删除"统一由 {@code @SQLDelete} + {@code @SQLRestriction}
 * 处理,业务侧无需再读写 {@code DELETED} 状态。
 *
 * <p>保留枚举值以兼容已存在的数据(可能有存量用户处于 DELETED 状态),
 * 但<b>不要再写</b> {@code user.setStatus(UserStatus.DELETED)} 这种代码;
 * 软删走 {@code repo.delete(user)} 即可。如果当前发现 {@code DELETED} 状态
 * 仍有写入路径,建议改为"双写":同时设置 {@code status = BANNED} 并触发
 * {@code repo.delete},让后续 cleanup 任务统一把存量 {@code DELETED}
 * 数据迁移到 {@code deleted_at IS NOT NULL}。
 *
 * @deprecated {@code DELETED} 状态已废弃,统一通过 {@link com.nexusforge.base.BaseEntity#isDeleted()} 表达
 */
@Getter
public enum UserStatus {
    ACTIVE(1, "正常"),
    INACTIVE(0, "未激活"),
    BANNED(-1, "已封禁"),
    /** @deprecated 改用 BaseEntity.deleted_at;保留兼容存量数据 */
    @Deprecated
    DELETED(-2, "已注销");

    private final Integer value;
    private final String description;

    UserStatus(Integer value, String description) {
        this.value = value;
        this.description = description;
    }
}

package com.nexusforge.file.entity;

/**
 * 文件元数据状态机。
 *
 * <pre>
 *   PENDING  ──confirm──▶  ACTIVE  ──softDelete──▶  DELETED
 *      │                                                  ▲
 *      └────── 直接 hardDelete (GDPR 真删路径) ────────────┘
 * </pre>
 *
 * <ul>
 *   <li>{@link #PENDING}  凭证已发,前端尚未 PUT 确认入库</li>
 *   <li>{@link #ACTIVE}   已确认,文件可访问</li>
 *   <li>{@link #DELETED}  软删(deleted_at 非空);{@code @SQLRestriction} 自动从查询过滤</li>
 * </ul>
 *
 * <p>状态翻转仅允许 PENDING→ACTIVE 和 ACTIVE→DELETED;PENDING→DELETED
 * 走「凭证过期回收」路径(本轮 TODO,后续可加 {@code @Scheduled})。
 * GDPR 真删路径走 {@code EntityManager.createNativeQuery} 物理删除,
 * 不会经过本状态机。</p>
 */
public enum FileStatus {
    PENDING,
    ACTIVE,
    DELETED
}

package com.nexusforge.event;

/**
 * 用户数据删除事件 —— 由 {@code AccountLifecycleService} 发出,
 * 监听方各自清理自己模块的数据(ai 模块删 conversations / messages / usage;
 * 后续 file 模块如果要加用户级文件统计也可监听)。
 *
 * <p>设计动机:账号生命周期属于 {@code nexus-forge-user},但用户数据散落在
 * 各业务模块(ai / file / ...)。user 模块不直接 import 业务模块的 entity,
 * 改用事件让各业务模块自主清理,降低耦合。</p>
 *
 * <p><b>触发时机</b>:</p>
 * <ul>
 *   <li>用户自助注销 confirm 成功后</li>
 *   <li>管理员真删用户(HARD_DELETE 审计后)</li>
 *   <li>Grace period 过期定时任务真删时</li>
 * </ul>
 *
 * <p><b>监听约束</b>:监听方应:</p>
 * <ul>
 *   <li>真删 / 软删业务数据(不依赖 user 记录存在)</li>
 *   <li>幂等(同一 userId 多次触发结果一致)</li>
 *   <li>不抛异常(失败 log,不影响主流程)</li>
 * </ul>
 */
public record UserDataDeletionEvent(Long userId) {
}

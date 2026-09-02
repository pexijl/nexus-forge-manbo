package com.nexusforge.audit;

/**
 * 审计日志接口 —— 通用模块(账号生命周期 / 后续权限管理 / 订单等)都依赖此接口,
 * 不直接耦合具体的审计表。
 *
 * <p>实现可以写到不同存储:</p>
 * <ul>
 *   <li>{@code account_lifecycle_log}(账号生命周期专用,本期实现)</li>
 *   <li>{@code file_audit_log}(文件上传/下载审计,后续模块)</li>
 *   <li>{@code admin_audit_log}(管理员操作审计,后续模块)</li>
 * </ul>
 *
 * <p>实现必须:</p>
 * <ul>
 *   <li>线程安全(单例 Bean 注入)</li>
 *   <li>不抛异常 —— 审计失败不影响主业务;异常 log warn 即可</li>
 *   <li>尽量在事务内同步写,便于审计与主操作原子性</li>
 * </ul>
 *
 * @param <A> action 类型
 */
public interface AuditLogger<A> {

    /**
     * 记录一条审计事件
     *
     * @param event 审计事件,userId + action + actorRole 必填
     */
    void log(AuditEvent<A> event);
}

package com.nexusforge.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解 —— 加在 controller 方法上,AOP 切面自动记录 HTTP 调用。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * @PutMapping("/api/users/me")
 * @Audited(value = "user.update", resource = "user", resourceId = "#userId")
 * public Result<UserVo> updateUser(@PathVariable Long userId, ...) { ... }
 * }</pre>
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li>{@link #value()} —— action 名称,存 {@code operation_audit_log.action}
 *       列。必填,业务侧命名(点分命名,例 {@code "user.update"} /
 *       {@code "file.upload"})</li>
 *   <li>{@link #resource()} —— 资源类型,例 {@code "user"} / {@code "file"}。
 *       留空表示不记资源维度</li>
 *   <li>{@link #resourceId()} —— SpEL 表达式,从方法参数 / 上下文取值。
 *       例 {@code "#userId"} 引用 @PathVariable 注入的 userId;复杂场景
 *       可以写 {@code "#req.userId"} 取对象字段</li>
 *   <li>{@link #recordArgs()} —— 是否把入参序列化到 metadata JSONB。
 *       默认 false(隐私默认安全,密码 / 凭据不落盘);开启后只记
 *       基本类型 / String / 数字 / 布尔,复杂对象不记</li>
 *   <li>{@link #recordResult()} —— 是否记返回值到 metadata。默认 false
 *       (返回值可能含敏感数据或大文件);开启后同样只记基本类型</li>
 * </ul>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>只用在 controller 注解方法(返回值,带 HTTP request / response 上下文)
 *       上;不要用在 service 内部方法(避免与 {@code AccountLifecycleAuditLogger}
 *       业务事件层重复)</li>
 *   <li>切面走 {@code @Around},方法抛异常也记 FAILURE(不漏审计)</li>
 *   <li>审计写库失败不抛(同 {@code AccountLifecycleAuditLogger} 容错策略)
 *       —— 主链路不能因审计挂</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 必填:action 名称(点分命名,如 "user.update" / "file.upload") */
    String value();

    /** 可选:资源类型(如 "user" / "file");空表示不记资源维度 */
    String resource() default "";

    /**
     * 可选:SpEL 表达式取资源 ID(如 "#userId");空表示不记。
     * 表达式由 Spring SpEL 解析,支持参数引用 + 简单属性访问。
     */
    String resourceId() default "";

    /** 是否把入参序列化到 metadata JSONB(默认 false,隐私默认安全) */
    boolean recordArgs() default false;

    /** 是否把返回值序列化到 metadata(默认 false,可能含敏感数据) */
    boolean recordResult() default false;
}

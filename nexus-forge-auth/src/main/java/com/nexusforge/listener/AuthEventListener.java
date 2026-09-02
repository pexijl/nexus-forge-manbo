package com.nexusforge.listener;

import com.nexusforge.event.UserBannedEvent;
import com.nexusforge.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 消费 {@link UserBannedEvent} → 踢该用户所有 refresh token。
 *
 * <p><b>模块边界</b>:user 模块发布 {@code UserBannedEvent}(在
 * {@code AdminUserLifecycleController.ban} / {@code AccountLifecycleService} 注销流程触发),
 * auth 模块本类订阅并执行踢 token ——事件通讯是反向依赖(user → auth)的标准解法,
 * 避免 user 模块直接依赖 auth 模块(否则依赖图成环)。
 *
 * <p><b>踢 refresh 不踢 access 的设计</b>:
 * <ul>
 *   <li>refresh 是<b>有状态</b>的(Redis key {@code auth:refresh:{userId}} 存活跃 JTI),可精准踢</li>
 *   <li>access 是<b>无状态</b> JWT,签发后无法在 token 层面撤销;依赖 ≤15min 自然过期</li>
 *   <li>用户被封禁后,旧 access 仍 ≤15min 有效,但无法用 refresh 换新 access(因为 refresh 已被踢)</li>
 * </ul>
 *
 * <p><b>同步语义</b>:默认 {@link EventListener} 在发布方线程同步执行;
 * 失败应吞掉异常不冒泡(避免影响 user 模块的 ban 流程)——当前实现未加 try/catch,
 * 见后续可改进点(AGENTS.md 经验法则 5:监听方约束 = 幂等 + 不抛异常)。
 *
 * @see com.nexusforge.event.UserBannedEvent 事件定义
 * @see com.nexusforge.service.AuthService#logoutAllRefreshTokens 实际踢 token
 * @see com.nexusforge.user.controller.AdminUserLifecycleController 事件发布方(ban)
 * @see com.nexusforge.user.service.AccountLifecycleService 事件发布方(注销)
 */
@Component
@RequiredArgsConstructor
public class AuthEventListener {

    /** 注入 AuthService 用于调 {@code logoutAllRefreshTokens} 踢该用户所有 refresh token;
     *  与单 token 的 {@code logout(access, refresh)} 不同,本方法按 userId 全量清理
     *  Redis 上 {@code auth:refresh:{userId}} 全部活跃 JTI */
    private final AuthService authService;

    /**
     * 处理 UserBannedEvent:踢掉该用户所有 refresh token(强制下次 access 过期后无法换新)。
     *
     * <p><b>执行语义</b>:默认同步(在事件发布方线程内执行,常见于
     * {@code AdminUserLifecycleController.ban} 的 HTTP 请求线程);
     * 监听方失败应吞掉异常而非冒泡——防止封禁操作被下游 listener 异常影响(AGENTS.md 5)。
     *
     * <p><b>幂等性</b>:重复触发是安全的(踢 refresh 是覆盖式写入);
     * 同一事件被发多次也只产生一次最终状态。
     *
     * @param event 来自 user 模块的封禁事件,含被封禁用户的 userId
     */
    @EventListener
    public void onUserBanned(UserBannedEvent event) {
        // 1 行调用,逻辑在 AuthService.logoutAllRefreshTokens:
        // 清空 auth:refresh:{userId} 上所有活跃 JTI → 用户无法再用 refresh 换新 access
        // (旧 access 仍 ≤15min 有效,但被封禁用户实际不会再用,可容忍)
        authService.logoutAllRefreshTokens(event.userId());
    }
}
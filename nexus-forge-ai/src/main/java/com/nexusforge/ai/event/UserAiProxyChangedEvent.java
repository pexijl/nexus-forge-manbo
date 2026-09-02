package com.nexusforge.ai.event;

import com.nexusforge.ai.entity.UserAiProxy;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用户 AI 代理变更事件(Phase 3 BYOK)。
 *
 * <p>由 {@code UserAiProxyService} 在用户 CRUD 代理后发布;
 * {@code UserAiProxyChangeListener} 订阅后清 Caffeine 缓存,
 * 实现"用户改完秒级生效" — 改完下次 {@code PreferenceResolver} 解析
 * 立即拿到新值,不需要重启,也不需要等 5 min TTL 过期。
 *
 * <p>事件携带 {@code userId} 让 listener 精准失效对应用户的缓存条目;
 * 不影响其他用户的代理缓存(用户 A 改自己的代理不会让用户 B 的
 * {@code /api/ai/proxies} 列表被清)。
 *
 * <p>多实例部署下:本地 Caffeine 实例间不会自动同步,每个实例的 5 min
 * TTL 兜底(单实例 OK;Phase 4 视情况加 Redis pub/sub 广播)。
 */
@Getter
public class UserAiProxyChangedEvent extends ApplicationEvent {

    /** 变更类型 */
    public enum ChangeType {
        CREATED,    // 新建
        UPDATED,    // 字段修改(enabled / defaultModel / baseUrl / apiKey / name 等)
        DELETED,    // 硬删除
        DEFAULT_CHANGED  // is_default 切换(单独 type 便于审计 + 触发 ChatModel cache 清)
    }

    /** 事件主语 user(不存整个 entity 引用避免 stale) */
    private final Long userId;
    /** 代理 ID(DELETED 时仍携带,便于审计) */
    private final Long proxyId;
    /** 变更类型 */
    private final ChangeType changeType;

    public UserAiProxyChangedEvent(Object source, UserAiProxy proxy, ChangeType changeType) {
        super(source);
        this.userId = proxy != null ? proxy.getUserId() : null;
        this.proxyId = proxy != null ? proxy.getId() : null;
        this.changeType = changeType;
    }

    /**
     * DELETED 事件专用构造器(实体已删,只传 userId + proxyId)。
     */
    public static UserAiProxyChangedEvent deleted(Object source, Long userId, Long proxyId) {
        UserAiProxyChangedEvent ev = new UserAiProxyChangedEvent(source, (UserAiProxy) null, ChangeType.DELETED);
        return new UserAiProxyChangedEvent(source, userId, proxyId, ChangeType.DELETED);
    }

    // 私有全参 ctor,只给 deleted() 工厂方法用
    private UserAiProxyChangedEvent(Object source, Long userId, Long proxyId, ChangeType changeType) {
        super(source);
        this.userId = userId;
        this.proxyId = proxyId;
        this.changeType = changeType;
    }
}

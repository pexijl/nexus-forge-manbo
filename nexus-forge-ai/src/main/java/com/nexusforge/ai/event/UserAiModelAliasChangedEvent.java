package com.nexusforge.ai.event;

import com.nexusforge.ai.entity.UserAiModelAlias;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用户 model alias 变更事件(Phase 4 模型别名)。
 *
 * <p>由 {@code UserAiModelAliasService} 在 CRUD 后发布;
 * {@code UserAiModelAliasChangeListener} 订阅后清 Caffeine 缓存,
 * 实现"用户改完秒级生效" — 改完下次 {@code PreferenceResolver} 解析立即拿到新值。
 *
 * <p>事件携带 {@code userId} 让 listener 精准失效该用户的 alias cache;
 * 不影响其他用户。{@code oldAlias} 字段在 alias 名变更时携带旧名,
 * 让 listener 能精准失效"旧 key" + "新 key"两条缓存(避免改名后旧 key 还在)。
 */
@Getter
public class UserAiModelAliasChangedEvent extends ApplicationEvent {

    /** 变更类型 */
    public enum ChangeType {
        CREATED,   // 新建
        UPDATED,   // 字段修改(包括 alias 改名 / target 改 / enabled 切)
        DELETED    // 硬删除
    }

    private final Long userId;
    private final Long aliasId;
    /** alias 名(DELETED 时仍携带,用于清 cache key);改名时存的是新名,旧名通过 {@link #oldAlias} 携带 */
    private final String alias;
    /** 改名时存旧名(让 listener 清旧 key);其他情况为 null */
    private final String oldAlias;
    private final ChangeType changeType;

    public UserAiModelAliasChangedEvent(Object source, UserAiModelAlias alias, ChangeType changeType) {
        super(source);
        this.userId = alias != null ? alias.getUserId() : null;
        this.aliasId = alias != null ? alias.getId() : null;
        this.alias = alias != null ? alias.getAlias() : null;
        this.oldAlias = null;
        this.changeType = changeType;
    }

    /**
     * rename 专用 ctor — 同时携带旧名 + 新名,listener 清两条 cache key。
     */
    public static UserAiModelAliasChangedEvent renamed(Object source, UserAiModelAlias updated, String oldAliasName) {
        UserAiModelAliasChangedEvent ev = new UserAiModelAliasChangedEvent(source, updated, ChangeType.UPDATED);
        return new UserAiModelAliasChangedEvent(source, updated.getUserId(), updated.getId(),
                updated.getAlias(), oldAliasName, ChangeType.UPDATED);
    }

    /**
     * DELETED 专用 ctor(实体已删,只传 userId + aliasId + alias 名)。
     */
    public static UserAiModelAliasChangedEvent deleted(Object source, Long userId, Long aliasId, String alias) {
        return new UserAiModelAliasChangedEvent(source, userId, aliasId, alias, null, ChangeType.DELETED);
    }

    // 私有全参 ctor,只给工厂方法用
    private UserAiModelAliasChangedEvent(Object source, Long userId, Long aliasId,
                                        String alias, String oldAlias, ChangeType changeType) {
        super(source);
        this.userId = userId;
        this.aliasId = aliasId;
        this.alias = alias;
        this.oldAlias = oldAlias;
        this.changeType = changeType;
    }
}

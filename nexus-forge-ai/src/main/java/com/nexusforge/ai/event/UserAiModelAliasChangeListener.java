package com.nexusforge.ai.event;

import com.nexusforge.ai.service.UserAiModelAliasService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 用户 model alias 变更事件监听器(Phase 4 模型别名)。
 *
 * <p>订阅 {@link UserAiModelAliasChangedEvent} 精准失效 Caffeine 缓存:
 * <ul>
 *   <li>CREATED / DELETED:清该 alias 名对应的 cache key</li>
 *   <li>UPDATED(改名):同时清旧 key + 新 key(避免改名后旧 cache 还在,导致 alias 命中失败)</li>
 *   <li>UPDATED(其他字段):清该 alias 名对应的 cache key(target 改了也要让下次解析拿新值)</li>
 * </ul>
 *
 * <p>不走 {@code @Async}:失效操作 ~ 微秒,异步开销不划算;同步保证"事件
 * publish 后 listener 跑完,下一次查询就拿到新数据"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAiModelAliasChangeListener {

    private final UserAiModelAliasService service;

    @EventListener
    public void onChange(UserAiModelAliasChangedEvent ev) {
        log.info("[ModelAlias cache] invalidate userId={} aliasId={} alias='{}' oldAlias='{}' type={}",
                ev.getUserId(), ev.getAliasId(), ev.getAlias(), ev.getOldAlias(), ev.getChangeType());
        // 改名:同时清旧 + 新 key;其他:只清 alias 当前 key
        service.invalidateCache(ev.getUserId(), ev.getOldAlias(), ev.getAlias());
    }
}

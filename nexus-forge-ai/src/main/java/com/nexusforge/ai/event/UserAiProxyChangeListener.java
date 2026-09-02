package com.nexusforge.ai.event;

import com.nexusforge.ai.service.UserAiProxyService;
import com.nexusforge.ai.provider.VendorChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 用户 AI 代理变更事件监听器。
 *
 * <p>订阅 {@link UserAiProxyChangedEvent},在 CRUD / setDefault 后:
 * <ol>
 *   <li>精准失效 {@code UserAiProxyService} 的 Caffeine 缓存(对应 userId 的
 *       default + list entries);下次 {@code PreferenceResolver} 立即拿到新值</li>
 *   <li>同步清掉 {@code VendorChatModelFactory} 的 ChatModel 缓存 — 改 apiKey
 *       后旧 ChatModel 持有旧 key,留着只会占内存 + 误调用</li>
 * </ol>
 *
 * <p>不走 {@code @Async}:失效操作 ~ 微秒,异步开销不划算;同步保证"事件
 * publish 后 listener 跑完,下一次查询就拿到新数据"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserAiProxyChangeListener {

    private final UserAiProxyService proxyService;
    private final VendorChatModelFactory chatModelFactory;

    @EventListener
    public void onChange(UserAiProxyChangedEvent ev) {
        log.info("[UserAiProxy cache] invalidate userId={} proxyId={} type={}",
                ev.getUserId(), ev.getProxyId(), ev.getChangeType());
        // 1. service 自有缓存(用户的 list + default)
        if (ev.getUserId() != null) {
            proxyService.invalidateCacheForUser(ev.getUserId());
        } else {
            // 极端兜底(理论上不会发生)
            proxyService.invalidateAllCaches();
        }
        // 2. VendorChatModelFactory 的 ChatModel cache — 改 apiKey / baseUrl 后旧模型作废
        // 简化策略:任意 proxy 变更 → 清全部 cache(private key 缓存规模小,几用户 × 几 model,清空性能可接受)
        chatModelFactory.invalidateAll();
    }
}

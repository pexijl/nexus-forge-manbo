package com.nexusforge.ai.event;

import com.nexusforge.ai.service.FallbackChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 降级链变更事件监听器(Phase 7)。
 *
 * <p>订阅 {@link FallbackChainChangedEvent} 失效 {@link FallbackChainService} 的
 * DB 路径 Caffeine 缓存(单 entry,失效就清空整个 cache)。下一次
 * {@code ChatModelRouter.resolveWithFallback} 调 {@code service.findEffective()}
 * 时会重新查 DB(或 yaml 兜底),拿到新链。
 *
 * <p>为什么"清空整个 cache"而不是精准按 vendor 失效:DB 路径只存 1 个 entry
 * (单行表),清 1 个等于清所有;不像 vendor config 是多 vendor 共享 cache
 * (用精准失效减冲突)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FallbackChainChangeListener {

    private final FallbackChainService service;

    @EventListener
    public void onChange(FallbackChainChangedEvent ev) {
        log.info("[FallbackChain cache] invalidate type={} vendors={}",
                ev.getChangeType(), ev.getVendors());
        service.invalidate();
    }
}

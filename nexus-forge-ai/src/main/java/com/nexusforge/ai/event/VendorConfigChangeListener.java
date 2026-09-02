package com.nexusforge.ai.event;

import com.nexusforge.ai.service.VendorConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Vendor 配置变更事件监听器。
 *
 * <p>订阅 {@link VendorConfigChangedEvent} 精准失效 Caffeine 缓存对应 key。
 * 私 Key 路径(VendorChatModelFactory)下次调用前查 DB 拿到新值,系统 Key
 * 路径(OpenAI starter bean)启动期已固定,不在本监听器职责范围(Phase 4 hot
 * reload 处理)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendorConfigChangeListener {

    private final VendorConfigService service;

    @EventListener
    public void onChange(VendorConfigChangedEvent ev) {
        log.info("[VendorConfig cache] invalidate vendor={} type={}",
                ev.getVendor(), ev.getChangeType());
        service.invalidateCache(ev.getVendor());
    }
}

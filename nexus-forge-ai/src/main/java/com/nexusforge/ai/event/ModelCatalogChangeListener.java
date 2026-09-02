package com.nexusforge.ai.event;

import com.nexusforge.ai.service.ModelCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 模型目录变更事件监听器。
 *
 * <p>订阅 {@link ModelCatalogChangedEvent},在 model CRUD 后精准失效
 * {@code ModelCatalogService} 的 Caffeine 缓存对应 key。
 * 不走 {@code @Async}:失效操作 ~ 微秒,异步开销不划算;同步保证"事件
 * publish 后 listener 跑完,下一次查询就拿到新数据"。
 *
 * <p>多实例下:每个实例独立监听本地事件(单实例 OK);跨实例的失效靠
 * 5 min TTL 兜底(Phase 4 视情况加 Redis pub/sub 广播)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCatalogChangeListener {

    private final ModelCatalogService service;

    @EventListener
    public void onChange(ModelCatalogChangedEvent ev) {
        log.info("[ModelCatalog cache] invalidate vendor={} model={} type={} modelId={}",
                ev.getVendor(), ev.getModelName(), ev.getChangeType(), ev.getModelId());
        // 精准失效:DELETED 事件也带 vendor+modelName(VO 字段已从 ctor 提取);
        // vendor/modelName 为 null 的极端情况(理论上不会发生)走 invalidateAll 兜底
        service.invalidateCache(ev.getVendor(), ev.getModelName());
    }
}

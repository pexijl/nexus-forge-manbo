package com.nexusforge.ai.event;

import com.nexusforge.ai.entity.AiModelCatalog;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 模型目录变更事件。
 *
 * <p>由 {@code ModelCatalogService} 在 CRUD 后通过 {@code ApplicationEventPublisher}
 * 发布;{@code ModelCatalogChangeListener} 订阅后清 Caffeine 缓存,
 * 实现"admin 改完秒级生效"(无需重启,也无需等 5 min TTL 过期)。
 *
 * <p>同时携带 {@code vendor} + {@code modelName} 让 listener 精确失效
 * 对应 key(避免无差别清全表),改完其他条目不受影响。
 *
 * <p>多实例部署下:本地 Caffeine 实例间不会自动同步,每个实例的 5 min TTL
 * 兜底(单实例下完全 OK;Phase 4 视情况加 Redis pub/sub 广播)。
 */
@Getter
public class ModelCatalogChangedEvent extends ApplicationEvent {

    /** 变更类型 */
    public enum ChangeType {
        CREATED,   // 新建
        UPDATED,   // 字段修改(enabled 之外的任何字段)
        ENABLED_TOGGLED,  // 单独切 enabled
        DELETED    // 硬删除
    }

    private final Long modelId;
    private final String vendor;
    private final String modelName;
    private final ChangeType changeType;

    public ModelCatalogChangedEvent(Object source, AiModelCatalog model, ChangeType changeType) {
        super(source);
        this.modelId = model != null ? model.getId() : null;
        this.vendor = model != null ? model.getVendor() : null;
        this.modelName = model != null ? model.getModelName() : null;
        this.changeType = changeType;
    }

    /**
     * DELETED 事件专用构造器(实体已删,只传关键字段)。
     */
    public static ModelCatalogChangedEvent deleted(Object source, Long modelId, String vendor, String modelName) {
        ModelCatalogChangedEvent ev = new ModelCatalogChangedEvent(source, null, ChangeType.DELETED);
        // DELETED 没法走 ctor 注入 id/vendor/modelName,直接覆盖
        return new ModelCatalogChangedEvent(source, modelId, vendor, modelName, ChangeType.DELETED);
    }

    // 私有全参 ctor,只给 deleted() 工厂方法用
    private ModelCatalogChangedEvent(Object source, Long modelId, String vendor, String modelName, ChangeType changeType) {
        super(source);
        this.modelId = modelId;
        this.vendor = vendor;
        this.modelName = modelName;
        this.changeType = changeType;
    }
}

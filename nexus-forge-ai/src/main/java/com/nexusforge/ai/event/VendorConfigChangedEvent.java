package com.nexusforge.ai.event;

import com.nexusforge.ai.entity.AiVendorConfig;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Vendor 配置变更事件。
 *
 * <p>由 {@code VendorConfigService} 在 admin 改 base_url / enabled 后发布;
 * {@code VendorConfigChangeListener} 订阅后清 Caffeine 缓存对应 key,
 * 实现"admin 改完秒级生效"——私 Key 路径(VendorChatModelFactory)下次构造
 * 立即拿到新值。
 *
 * <p>系统 Key 路径(OpenAI starter bean)启动期已固定,改 vendor config 不
 * 重建 bean;admin 改完系统 Key 仍走旧 base_url,直到重启(Phase 4 hot reload
 * 再处理)。
 */
@Getter
public class VendorConfigChangedEvent extends ApplicationEvent {

    /** 变更类型 */
    public enum ChangeType {
        UPDATED,  // 任意字段修改
        ENABLED_TOGGLED,  // 单独切 enabled
        DELETED   // 预留,Phase 2 暂不暴露删除端点
    }

    private final String vendor;
    private final ChangeType changeType;

    public VendorConfigChangedEvent(Object source, AiVendorConfig config, ChangeType changeType) {
        super(source);
        this.vendor = config != null ? config.getVendor() : null;
        this.changeType = changeType;
    }
}

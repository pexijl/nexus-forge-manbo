package com.nexusforge.ai.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 降级链变更事件(Phase 7 — fallback-chain 策略 DB 化)。
 *
 * <p>由 {@code FallbackChainService} 在 admin 改完降级链后发布;
 * {@code FallbackChainChangeListener} 订阅后清 {@code FallbackChainService} 自身
 * 的 DB 路径 Caffeine 缓存,实现"admin 改完秒级生效"——下次
 * {@code ChatModelRouter.resolveWithFallback} 调 {@code service.findEffective()}
 * 时拿新链。
 *
 * <p>不携带 DB 实体(单行表,没必要),只带 changeType + 新链(便于日志 / 审计);
 * listener 只需要 invalidate cache,不需要重新读 DB(下一次 call 自然读)。
 */
@Getter
public class FallbackChainChangedEvent extends ApplicationEvent {

    /** 变更类型 */
    public enum ChangeType {
        /** 全量替换降级链(PUT /api/admin/ai/fallback-chain,vendors 任意) */
        REPLACED,
        /** 物理删除 DB 行,回退 yaml 兜底(DELETE /api/admin/ai/fallback-chain) */
        RESET
    }

    private final ChangeType changeType;
    /** 变更后的 vendor 列表(REPLACED 时是 PUT 进来的新链;RESET 时为 []) */
    private final List<String> vendors;

    public FallbackChainChangedEvent(Object source, ChangeType changeType, List<String> vendors) {
        super(source);
        this.changeType = changeType;
        this.vendors = vendors == null ? List.of() : List.copyOf(vendors);
    }
}

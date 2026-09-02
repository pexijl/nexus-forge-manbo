package com.nexusforge.ai.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 降级链(Phase 7 — fallback-chain 策略 DB 化)。
 *
 * <p>全局唯一一行表(单行表,id 永远 = 1,CHECK 约束);
 * 持 {@code vendors} 列表,元素是 vendor 名字符串(对应 {@code ai_vendor_config.vendor})。
 * 由 {@code FallbackChainService} 读 + Caffeine 缓存 + 事件失效,
 * 由 {@code ChatModelRouter.resolveWithFallback} 消费构造降级链。
 *
 * <h3>读路径语义</h3>
 * <ol>
 *   <li>DB 有行(无论 vendors 空不空)→ 优先用 DB</li>
 *   <li>DB 无行 → 走 yaml 兜底({@code spring.ai.fallback-chain}),无 yaml 则空降级链</li>
 * </ol>
 *
 * <h3>写路径</h3>
 * <p>启动期 <b>不 seed</b>(跟 {@code AiVendorConfig} 行为不同):
 * fallback chain 是"运营控制面",不是"出厂镜像"。DB 没配就用 yaml,
 * 运营第一次 PUT 才入 DB,生产部署不会"启动期覆盖运营决策"。
 *
 * <h3>为什么 JSONB</h3>
 * <p>元素是 vendor 名字符串,简单数组(无元数据);JSONB 跟
 * {@code account_lifecycle_log.metadata} 用同一 {@code @JdbcTypeCode(SqlTypes.JSON)}
 * 模式(已有测试覆盖),写入时由 Hibernate 序列化为 JSON 字符串,
 * 读出时反序列化为 {@code List<String>}。DB 层有 CHECK 约束
 * {@code jsonb_typeof(vendors) = 'array'} 防御 psql 手动塞标量。
 *
 * <h3>设计取舍</h3>
 * <ul>
 *   <li><b>不继承 BaseEntity</b>:本表无 deletedAt,跟 {@code AiGlobalDefault} 风格一致</li>
 *   <li><b>不暴露给 {@code @SQLDelete} / {@code @SQLRestriction}</b>:配置数据,不走软删</li>
 *   <li><b>CHECK id=1 哨兵</b>:防止误插多行(应用层 service 永远 upsert id=1)</li>
 *   <li><b>vendors 默认 []</b>:DB 层兜底,空 list 不需要 NULL 区分语义</li>
 * </ul>
 *
 * <p>对应表 {@code ai_fallback_chain},迁移 V20260902_007。
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode(of = "id")
@Entity
@Table(name = "ai_fallback_chain")
public class AiFallbackChain {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    /**
     * 降级链 vendor 列表(有序,DB JSONB 数组)。
     * <p>空 list 跟"DB 无行"语义由 service 区分:无行 → 走 yaml;空 list → DB 显式配置为空降级链。
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} 走 Hibernate 6 JSON 序列化,
     * 跟 {@code AccountLifecycleLog.metadata} 同一模式。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vendors", columnDefinition = "jsonb", nullable = false)
    private List<String> vendors = new ArrayList<>();

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}

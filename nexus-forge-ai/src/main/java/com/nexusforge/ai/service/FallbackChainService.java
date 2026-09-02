package com.nexusforge.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusforge.ai.entity.AiFallbackChain;
import com.nexusforge.ai.event.FallbackChainChangedEvent;
import com.nexusforge.ai.repository.AiFallbackChainRepository;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 降级链 Service(Phase 7 — fallback-chain 策略 DB 化)。
 *
 * <h3>读路径语义(DB → yaml → empty)</h3>
 * <ol>
 *   <li>DB 有行(无论 {@code vendors} 空不空)→ 用 DB 覆盖 yaml,
 *       {@code source = DB}</li>
 *   <li>DB 无行 + yaml 有 {@code spring.ai.fallback-chain} → 走 yaml 兜底,
 *       {@code source = YAML_FALLBACK}</li>
 *   <li>DB 无行 + yaml 也空 → 空降级链,无降级行为,
 *       {@code source = EMPTY}</li>
 * </ol>
 *
 * <h3>启动期不 seed(关键差异)</h3>
 * 跟 {@link VendorConfigService#seedFromYamlIfEmpty} 不同 — fallback chain
 * 是"运营控制面",不是"出厂镜像"。DB 没配就用 yaml,运营第一次 PUT 才入 DB,
 * 生产部署不会"启动期覆盖运营决策"。{@code spring.ai.fallback-chain} 是
 * dev 环境的测试配置,prod 不应被它"污染"。
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>key:固定 1 个(单行表),实际不靠 key 索引,只用 TTL 控"DB 改了不重启立刻生效"</li>
 *   <li>value:DB 命中时的 {@code FallbackChainView}(source=DB);其他路径
 *       <b>不缓存</b>(避免 yaml 改 / DB 行被删后服务不知道)</li>
 *   <li>TTL 5 min(异常路径兜底):正常路径靠事件 {@link FallbackChainChangedEvent}
 *       精准失效</li>
 *   <li>maximumSize(1) — 实际就一个 entry,但 Caffeine 要求设</li>
 * </ul>
 *
 * <h3>写路径</h3>
 * <ul>
 *   <li>{@link #replace(List)}:全量替换(空 list = 显式禁用降级),
 *       upsert id=1;校验每个 vendor 名存在于 yaml {@code providers.*}</li>
 *   <li>{@link #reset()}:物理删除 id=1 行(回退 yaml 兜底),
 *       admin 显式语义"重置"</li>
 *   <li>两个写方法都发 {@link FallbackChainChangedEvent},
 *       {@code FallbackChainChangeListener} 收到后清本类 cache(下次 call 即生效)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FallbackChainService {

    private final AiFallbackChainRepository repo;
    private final AiProperties yamlProps;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * DB 命中路径的缓存;key 固定 "chain-db",实际不靠 key 索引。
     * 写操作 invalidate;TTL 5 min 是异常路径兜底(比如 listener 失败 / 重启后冷启动)。
     */
    private final Cache<String, FallbackChainView> dbCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1)
            .build();

    // ─────────────────────── 读 ───────────────────────

    /**
     * 当前生效的降级链(网关热路径:ChatModelRouter.resolveWithFallback 每次 call 前查一次)。
     * <p>DB → yaml → empty 三段语义见类 Javadoc。
     * <p>DB 路径走 Caffeine 缓存(5min);其他路径不缓存(避免 yaml 改 / DB 删行后不一致)。
     */
    public FallbackChainView findEffective() {
        FallbackChainView cached = dbCache.getIfPresent("chain-db");
        if (cached != null) {
            return cached;
        }
        var opt = repo.findById(1);
        if (opt.isPresent()) {
            AiFallbackChain row = opt.get();
            FallbackChainView v = new FallbackChainView(
                    new ArrayList<>(row.getVendors() == null ? List.of() : row.getVendors()),
                    FallbackChainSource.DB,
                    row.getUpdatedAt());
            dbCache.put("chain-db", v);
            return v;
        }
        // DB 无行 → yaml 兜底
        List<String> yamlChain = yamlProps.getFallbackChain() == null
                ? List.of() : yamlProps.getFallbackChain();
        if (yamlChain.isEmpty()) {
            return new FallbackChainView(List.of(), FallbackChainSource.EMPTY, null);
        }
        return new FallbackChainView(new ArrayList<>(yamlChain),
                FallbackChainSource.YAML_FALLBACK, null);
    }

    // ─────────────────────── 写 ───────────────────────

    /**
     * 全量替换降级链(空 list = 显式禁用降级,DB 仍有行但 vendors=[])。
     * <ul>
     *   <li>校验每个 vendor 名小写化后存在于 yaml {@code spring.ai.providers.*};
     *       不存在抛 {@code LLM_INVALID_REQUEST}(400)</li>
     *   <li>去重(LinkedHashSet 保序);空 list 合法</li>
     *   <li>upsert id=1;存在则 update,不存在则 insert(行不存在时
     *       {@code createdAt} 由 DB DEFAULT now() 填,JPA {@code @Column(insertable=false)}
     *       跳过)</li>
     *   <li>发 {@link FallbackChainChangedEvent};{@code FallbackChainChangeListener}
     *       清本类 cache,下次 call 走新链</li>
     * </ul>
     */
    @Transactional
    public FallbackChainView replace(List<String> rawVendors) {
        if (rawVendors == null) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "vendors 不能为 null");
        }
        // 1. 归一化 + 去重 + 校验存在
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String v : rawVendors) {
            if (v == null || v.isBlank()) {
                throw new BusinessException(ResultCode.LLM_INVALID_REQUEST,
                        "降级链不能含空 vendor 项");
            }
            String k = v.trim().toLowerCase(Locale.ROOT);
            if (!vendorExistsInYaml(k)) {
                throw new BusinessException(ResultCode.LLM_INVALID_REQUEST,
                        "vendor=" + k + " 不在 spring.ai.providers.* 中;先在 yaml 配启用后再加进降级链");
            }
            normalized.add(k);
        }
        List<String> deduped = new ArrayList<>(normalized);

        // 2. upsert id=1
        AiFallbackChain row = repo.findById(1).orElseGet(() -> {
            AiFallbackChain fresh = new AiFallbackChain();
            fresh.setId(1);
            return fresh;
        });
        row.setVendors(new ArrayList<>(deduped));
        AiFallbackChain saved = repo.save(row);

        log.info("[FallbackChain] REPLACE vendors={} (size={}, source=DB)",
                deduped, deduped.size());

        // 3. 失效 cache + 发事件
        invalidate();
        FallbackChainView view = new FallbackChainView(deduped, FallbackChainSource.DB,
                saved.getUpdatedAt() == null ? OffsetDateTime.now() : saved.getUpdatedAt());
        eventPublisher.publishEvent(new FallbackChainChangedEvent(this,
                FallbackChainChangedEvent.ChangeType.REPLACED, deduped));
        return view;
    }

    /**
     * 物理删除 DB 行 id=1,回退 yaml 兜底。
     * <p>admin 显式语义"重置到 yaml 默认" — 跟 {@link #replace(List)} 接空 list
     * 不同(后者仍保留 DB 行,source 仍是 DB)。
     * <p>DB 没行时幂等返 {@link #findEffective()} 当前值(不是异常)。
     */
    @Transactional
    public FallbackChainView reset() {
        if (!repo.existsById(1)) {
            log.info("[FallbackChain] RESET 幂等(本来就没 DB 行),直接返 yaml 兜底视图");
            invalidate();
            return findEffective();
        }
        repo.deleteById(1);
        log.info("[FallbackChain] RESET 物理删除 id=1 行,回退 yaml 兜底");

        invalidate();
        eventPublisher.publishEvent(new FallbackChainChangedEvent(this,
                FallbackChainChangedEvent.ChangeType.RESET, List.of()));
        return findEffective();
    }

    // ─────────────────────── 内部 ───────────────────────

    /**
     * 校验 vendor 名存在于 yaml {@code spring.ai.providers.*}。
     * 不管 enabled 状态(enabled 由 ChatModelRouter 运行时跳过,DB 写入时只校验"是否注册过")。
     */
    private boolean vendorExistsInYaml(String vendorLower) {
        Map<String, AiProperties.Provider> providers = yamlProps.getProviders();
        if (providers == null) return false;
        for (String key : providers.keySet()) {
            if (key != null && key.equalsIgnoreCase(vendorLower)) return true;
        }
        return false;
    }

    /**
     * 清 DB 路径 cache。Listener 跟 Service 自身写路径都调。
     */
    public void invalidate() {
        dbCache.invalidateAll();
    }

    // ─────────────────────── 视图 / 来源枚举 ───────────────────────

    /**
     * 读路径返回值包装。{@code source} 字段让 VO/admin 一眼看出当前生效值来自哪。
     */
    public record FallbackChainView(List<String> vendors, FallbackChainSource source,
                                    OffsetDateTime updatedAt) {
    }

    /**
     * 降级链生效值来源 — 见 VO 注释。
     */
    public enum FallbackChainSource {
        /** DB 有行(可能 vendors 为空) */
        DB,
        /** DB 无行,走 yaml 兜底 */
        YAML_FALLBACK,
        /** DB 无行,yaml 也空,空降级链 */
        EMPTY
    }
}

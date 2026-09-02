package com.nexusforge.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusforge.ai.controller.dto.ModelCatalogDto;
import com.nexusforge.ai.entity.AiModelCatalog;
import com.nexusforge.ai.event.ModelCatalogChangedEvent;
import com.nexusforge.ai.repository.AiModelCatalogRepository;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 模型目录 Service。Phase 1 多模型管理核心。
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>key = {@code vendor + ":" + modelName} (全小写,避免大小写碎片)</li>
 *   <li>value = {@link AiModelCatalog} 实体;命中后 ~ 微秒级返回</li>
 *   <li>TTL 5 min(异常路径兜底:事件监听漏掉也能自动过期)</li>
 *   <li>写操作发 {@link ModelCatalogChangedEvent} → listener 精准失效</li>
 *   <li>多实例:每个实例的本地 Caffeine 独立;5 min TTL 兜底跨实例不一致
 *       (Phase 4 视情况加 Redis pub/sub 广播)</li>
 * </ul>
 *
 * <h3>为何不缓存 "findAllByEnabledTrue"</h3>
 * 这条查询是 admin 列表 / public 列表用,JPA 直接走索引扫表,~ 毫秒级;
 * 缓存收益小,失效复杂度大(任何 model 改动都要清),不值得。
 *
 * <h3>seed 策略</h3>
 * {@link #seedFromYamlIfEmpty} 启动期跑一次:catalog 完全空时把 yaml
 * {@code spring.ai.providers.<v>.default-model} 拷进 DB;之后 DB 是
 * 唯一 source of truth,yaml 改不动 catalog(避免"忘了同步两边"的坑)。
 */
@Slf4j
@Service
public class ModelCatalogService {

    /** 缓存 key 前缀(防命名冲突;虽然 caffeine 内部隔离,但调试时易辨识) */
    private static final String CACHE_KEY_PREFIX = "mc:";

    private final AiModelCatalogRepository repo;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 网关运行时校验缓存:key=vendor:model,value=AiModelCatalog。
     * 5 min TTL + 显式 invalidate(写操作发事件后 listener 调 invalidate)。
     */
    private final Cache<String, AiModelCatalog> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1024)
            .build();

    public ModelCatalogService(AiModelCatalogRepository repo,
                               ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
    }

    // ─────────────────────── 读 ───────────────────────

    /**
     * admin 详情页用。
     */
    public AiModelCatalog findById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                        "model catalog id=" + id + " 不存在"));
    }

    /**
     * 网关运行时热路径:被 {@code LlmClient} 每次调用前调用。
     * 先查缓存(命中即返回),未命中查 DB 并回填。
     *
     * <p>注意:这里不做 enabled 校验,留给 caller 判断 —
     * LlmClient 需要区分"不存在"和"被禁用"两种错误码。
     */
    public AiModelCatalog findByVendorModel(String vendor, String modelName) {
        if (vendor == null || modelName == null) return null;
        String key = cacheKey(vendor, modelName);
        AiModelCatalog cached = cache.getIfPresent(key);
        if (cached != null) return cached;
        Optional<AiModelCatalog> opt = repo.findByVendorAndModelName(
                vendor.toLowerCase(Locale.ROOT),
                modelName.trim());
        AiModelCatalog found = opt.orElse(null);
        if (found != null) {
            cache.put(key, found);
        }
        return found;
    }

    /**
     * 公共可用模型列表(给 {@code /api/ai/models/available})。
     * 不过缓存(列表相对小,扫表即可,失效复杂度不值)。
     */
    public List<AiModelCatalog> listEnabled() {
        return repo.findAllByEnabledTrue();
    }

    /**
     * admin 分页 + 过滤。
     */
    public Page<AiModelCatalog> findPage(String vendor, Boolean enabled, Pageable pageable) {
        String v = (vendor == null || vendor.isBlank()) ? null : vendor.toLowerCase(Locale.ROOT);
        return repo.findPage(v, enabled, pageable);
    }

    // ─────────────────────── 写 ───────────────────────

    /**
     * admin 新建 model。
     * <p>vendor + modelName 唯一约束:existsBy 预检后插,避免抛
     * {@code DataIntegrityViolationException}。
     */
    @Transactional
    public AiModelCatalog create(ModelCatalogDto dto) {
        String vendor = normalizeVendor(dto.getVendor());
        String modelName = dto.getModelName().trim();
        if (repo.existsByVendorAndModelName(vendor, modelName)) {
            throw new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                    "已存在 vendor=" + vendor + ", model=" + modelName + " 的记录");
        }
        AiModelCatalog m = new AiModelCatalog();
        applyDto(m, dto);
        m.setVendor(vendor);
        m.setModelName(modelName);
        // 显式设默认值(实体字段 default 不会被 JPA 用到 insertable=false 之后的字段,
        // 但 DTO 没传时也保证非 null)
        if (m.getEnabled() == null) m.setEnabled(Boolean.TRUE);
        if (m.getSupportsVision() == null) m.setSupportsVision(Boolean.FALSE);
        if (m.getSupportsTools() == null) m.setSupportsTools(Boolean.TRUE);
        if (m.getSupportsStreaming() == null) m.setSupportsStreaming(Boolean.TRUE);
        if (m.getTier() == null || m.getTier().isBlank()) m.setTier("STANDARD");
        AiModelCatalog saved = repo.save(m);
        log.info("[ModelCatalog] CREATE id={} vendor={} model={}", saved.getId(), vendor, modelName);
        eventPublisher.publishEvent(new ModelCatalogChangedEvent(this, saved, ModelCatalogChangedEvent.ChangeType.CREATED));
        return saved;
    }

    /**
     * admin 修改 model(全字段覆盖语义;null 字段保留旧值 — 由 DTO 控制,
     * DTO 用包装类型,前端不传即 null,service 跳过赋值)。
     */
    @Transactional
    public AiModelCatalog update(Long id, ModelCatalogDto dto) {
        AiModelCatalog m = findById(id);
        applyDto(m, dto);
        log.info("[ModelCatalog] UPDATE id={} vendor={} model={}", m.getId(), m.getVendor(), m.getModelName());
        // save 不必要(managed entity dirty checking 会自动 flush),但显式 save 语义清晰
        AiModelCatalog saved = repo.save(m);
        eventPublisher.publishEvent(new ModelCatalogChangedEvent(this, saved, ModelCatalogChangedEvent.ChangeType.UPDATED));
        return saved;
    }

    /**
     * admin 单独切 enabled(PATCH 端点专用,触发独立事件类型便于日志区分)。
     */
    @Transactional
    public AiModelCatalog setEnabled(Long id, boolean enabled) {
        AiModelCatalog m = findById(id);
        m.setEnabled(enabled);
        AiModelCatalog saved = repo.save(m);
        log.info("[ModelCatalog] ENABLED_TOGGLED id={} vendor={} model={} enabled={}",
                saved.getId(), saved.getVendor(), saved.getModelName(), enabled);
        eventPublisher.publishEvent(new ModelCatalogChangedEvent(this, saved, ModelCatalogChangedEvent.ChangeType.ENABLED_TOGGLED));
        return saved;
    }

    /**
     * admin 硬删除(配合 audit log,model catalog 是配置数据不走软删)。
     */
    @Transactional
    public void delete(Long id) {
        AiModelCatalog m = findById(id);
        String v = m.getVendor();
        String mn = m.getModelName();
        repo.delete(m);
        log.info("[ModelCatalog] DELETE id={} vendor={} model={}", id, v, mn);
        eventPublisher.publishEvent(ModelCatalogChangedEvent.deleted(this, id, v, mn));
    }

    // ─────────────────────── seed ───────────────────────

    /**
     * 启动期 seed:catalog 完全空时把 yaml 已知 default-model 拷进来。
     * 只在第一次启动跑(之后 catalog 有数据就不再插,避免"yaml 改 default 但
     * 用户的 catalog 已经有别的 default"被覆盖的脏场景)。
     *
     * <p>yaml 的 providers.*.default-model 在 phase 5 是 source of truth,
     * Phase 1 起 DB 才是;seed 只搬首次需要的"种子",不持续同步。
     */
    @Transactional
    public int seedFromYamlIfEmpty(java.util.Map<String, String> yamlDefaults) {
        if (repo.count() > 0) {
            log.info("[ModelCatalog] seed 跳过:catalog 已有 {} 条记录", repo.count());
            return 0;
        }
        if (yamlDefaults == null || yamlDefaults.isEmpty()) {
            log.info("[ModelCatalog] seed 跳过:yaml 无 default-model 配置");
            return 0;
        }
        int created = 0;
        for (var entry : yamlDefaults.entrySet()) {
            String vendor = normalizeVendor(entry.getKey());
            String modelName = entry.getValue();
            if (modelName == null || modelName.isBlank()) continue;
            if (repo.existsByVendorAndModelName(vendor, modelName)) continue;
            AiModelCatalog m = new AiModelCatalog();
            m.setVendor(vendor);
            m.setModelName(modelName);
            m.setEnabled(Boolean.TRUE);
            m.setSupportsTools(Boolean.TRUE);
            m.setSupportsStreaming(Boolean.TRUE);
            m.setTier("STANDARD");
            m.setDescription("Auto-seeded from application.yaml (Phase 1 migration)");
            repo.save(m);
            created++;
            log.info("[ModelCatalog] seed 插入: vendor={} model={}", vendor, modelName);
        }
        if (created > 0) {
            log.info("[ModelCatalog] seed 完成:共插入 {} 条", created);
        }
        return created;
    }

    // ─────────────────────── 内部 helper ───────────────────────

    /**
     * DTO 字段"非 null 时"才覆盖 entity(支持 partial update)。
     * 注意 Boolean / Integer / BigDecimal / String 都是包装类型,天然能表达"未传"。
     */
    private static void applyDto(AiModelCatalog m, ModelCatalogDto dto) {
        if (dto.getDisplayName() != null) m.setDisplayName(dto.getDisplayName().trim());
        if (dto.getEnabled() != null) m.setEnabled(dto.getEnabled());
        if (dto.getContextWindow() != null) m.setContextWindow(dto.getContextWindow());
        if (dto.getMaxOutputTokens() != null) m.setMaxOutputTokens(dto.getMaxOutputTokens());
        if (dto.getSupportsVision() != null) m.setSupportsVision(dto.getSupportsVision());
        if (dto.getSupportsTools() != null) m.setSupportsTools(dto.getSupportsTools());
        if (dto.getSupportsStreaming() != null) m.setSupportsStreaming(dto.getSupportsStreaming());
        if (dto.getCostInputPer1k() != null) m.setCostInputPer1k(dto.getCostInputPer1k());
        if (dto.getCostOutputPer1k() != null) m.setCostOutputPer1k(dto.getCostOutputPer1k());
        if (dto.getTier() != null) m.setTier(dto.getTier().toUpperCase(Locale.ROOT));
        if (dto.getDescription() != null) m.setDescription(dto.getDescription());
    }

    private static String normalizeVendor(String v) {
        if (v == null) throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "vendor 不能为空");
        return v.trim().toLowerCase(Locale.ROOT);
    }

    private static String cacheKey(String vendor, String modelName) {
        return CACHE_KEY_PREFIX + vendor.toLowerCase(Locale.ROOT) + ":" + modelName.trim();
    }

    /**
     * 给 listener 用的精准失效(避免 service 暴露整个 cache)。
     * public 因为 listener 在 {@code com.nexusforge.ai.event} 包,跨包调用必须公开。
     */
    public void invalidateCache(String vendor, String modelName) {
        if (vendor == null || modelName == null) {
            cache.invalidateAll();
            return;
        }
        cache.invalidate(cacheKey(vendor, modelName));
    }
}

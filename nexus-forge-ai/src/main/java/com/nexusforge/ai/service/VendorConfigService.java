package com.nexusforge.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusforge.ai.audit.VendorApiKeyAuditEvent;
import com.nexusforge.ai.audit.VendorApiKeyAuditLogger;
import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.controller.dto.VendorConfigDto;
import com.nexusforge.ai.entity.AiVendorConfig;
import com.nexusforge.ai.enums.VendorApiKeyAuditAction;
import com.nexusforge.ai.event.VendorConfigChangedEvent;
import com.nexusforge.ai.repository.AiVendorConfigRepository;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * AI vendor 配置 Service。Phase 2 多模型管理核心,Phase 6 起含 system api_key
 * 持久化 + 热轮换。
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>key = vendor 名(小写)</li>
 *   <li>value = {@link AiVendorConfig} 实体</li>
 *   <li>TTL 5 min(异常路径兜底)</li>
 *   <li>写操作发 {@link VendorConfigChangedEvent} → listener 精准失效</li>
 * </ul>
 *
 * <h3>DB 优先 + yaml 兜底</h3>
 * {@link #findByVendor} 先查 DB:命中则用 DB;未命中回退到 yaml {@code providers.<v>.*}。
 * 这样保证:
 * <ul>
 *   <li>DB 有配置:admin 改完立即生效(DB 是 source of truth)</li>
 *   <li>DB 无配置(首次启动 seed 还没跑):fallback 到 yaml,旧行为不变</li>
 *   <li>删除 DB 行不会让系统崩(回退 yaml),但用户得显式操作才能恢复</li>
 * </ul>
 *
 * <h3>Phase 6 — system api_key 流程</h3>
 * <ul>
 *   <li>{@link #setApiKey(String, String)}:加密入库 + 发 {@code VendorConfigChangedEvent};
 *       {@code SystemKeyChatModelFactory} 监听事件后清 cache,下次 call 自动用新 key</li>
 *   <li>{@link #clearApiKey(String)}:把两列置 NULL,回退 yaml;同样发事件</li>
 *   <li>{@link #getEffectiveApiKey(String)}:DB 解密 → yaml;{@code SystemKeyChatModelFactory}
 *       每次 resolveOrCreate 都调一次(不缓存明文,解密微秒级;不缓存避免内存泄露明文)</li>
 *   <li>api_key 字段不进 {@code VendorConfigDto}(partial update 容易歧义),
 *       走独立端点 PUT/DELETE {@code /api/admin/ai/vendors/{vendor}/api-key}</li>
 * </ul>
 *
 * <h3>写语义</h3>
 * Phase 2 范围:admin 改已存在的 vendor(base_url / enabled / description)。
 * 新建 vendor 走 yaml + seed runner,不在 Phase 2 范围(避免 api-key 不在 yaml
 * 时新增 vendor 涉及更多边界)。
 */
@Slf4j
@Service
public class VendorConfigService {

    private final AiVendorConfigRepository repo;
    private final AiProperties yamlProps;
    private final ApplicationEventPublisher eventPublisher;
    private final ApiKeyCipher cipher;
    private final VendorApiKeyAuditLogger auditLogger;

    /**
     * 缓存:key=vendor(小写),value=AiVendorConfig 实体。
     */
    private final Cache<String, AiVendorConfig> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(128)
            .build();

    public VendorConfigService(AiVendorConfigRepository repo,
                               AiProperties yamlProps,
                               ApplicationEventPublisher eventPublisher,
                               ApiKeyCipher cipher,
                               VendorApiKeyAuditLogger auditLogger) {
        this.repo = repo;
        this.yamlProps = yamlProps;
        this.eventPublisher = eventPublisher;
        this.cipher = cipher;
        this.auditLogger = auditLogger;
    }

    // ─────────────────────── 读 ───────────────────────

    /**
     * 网关运行时热路径:VendorChatModelFactory 每次私 Key 调用前查一次。
     * <ul>
     *   <li>DB 命中 → 返回 AiVendorConfig 实体(可能 enabled=false,调用方判断)</li>
     *   <li>DB 未命中 → 从 yaml 兜底构造一个"逻辑等价"的虚拟 entity
     *       (只读 baseUrl / enabled / apiKey,id 留空),不写 DB(避免污染 catalog)</li>
     * </ul>
     */
    public VendorConfigView findByVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) return null;
        String key = normalizeVendor(vendor);
        AiVendorConfig cached = cache.getIfPresent(key);
        if (cached != null) {
            return VendorConfigView.db(cached);
        }
        Optional<AiVendorConfig> opt = repo.findByVendor(key);
        if (opt.isPresent()) {
            cache.put(key, opt.get());
            return VendorConfigView.db(opt.get());
        }
        // DB 没有 → fallback yaml
        AiProperties.Provider p = yamlProps.getProviders() == null ? null
                : yamlProps.getProviders().get(key);
        if (p == null) {
            return null;   // yaml 也没有 → vendor 不存在
        }
        return VendorConfigView.yamlFallback(p);
    }

    /**
     * admin 列表 — 全量,按 vendor 排序。
     */
    public List<AiVendorConfig> listAll() {
        return repo.findAllByOrderByVendorAsc();
    }

    /**
     * admin 详情页用 — 不存在抛异常。
     */
    public AiVendorConfig findOrThrow(String vendor) {
        AiVendorConfig m = repo.findByVendor(normalizeVendor(vendor))
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                        "vendor=" + vendor + " 在 ai_vendor_config 不存在"));
        return m;
    }

    /**
     * Phase 6 — 拿 vendor 当前实际生效的 system apiKey(明文)。
     * <p>优先级:DB 密文解密 → yaml {@code providers.<v>.api-key} → null。
     * <p><b>不缓存明文</b>(AES-GCM 解密本身是微秒级,缓存明文会带来内存泄露风险;
     * cache 复用是 {@code SystemKeyChatModelFactory} 自身的 (vendor, baseUrl, apiKey 哈希)
     * 三元组,改 key 后事件清 cache,新 key 自然走新 ChatModel 实例)。
     * <p>明文用途:构造 OpenAIChatModel 时的 {@code options.apiKey} —
     * {@code SystemKeyChatModelFactory} 唯一调用方。
     *
     * @return 明文 key;DB 和 yaml 都没有时返 null(调用方应注入占位符)
     */
    public String getEffectiveApiKey(String vendor) {
        if (vendor == null || vendor.isBlank()) return null;
        String key = normalizeVendor(vendor);
        // 1. DB 优先
        VendorConfigView view = findByVendor(key);
        if (view != null && view.entity().getEncryptedApiKey() != null) {
            try {
                return cipher.decrypt(view.entity().getEncryptedApiKey());
            } catch (Exception e) {
                // 解密失败(主密钥已轮换 / 密文被篡改)→ log warn,降级到 yaml
                // 严格场景应抛错,但保持调用方热路径的"降级可用"语义
                log.warn("[VendorConfig] 解密 vendor={} 的 apiKey 失败,降级用 yaml: {}",
                        key, e.getMessage());
            }
        }
        // 2. yaml 兜底
        if (yamlProps.getProviders() != null) {
            AiProperties.Provider p = yamlProps.getProviders().get(key);
            if (p != null && p.getApiKey() != null && !p.getApiKey().isBlank()) {
                return p.getApiKey();
            }
        }
        return null;
    }

    // ─────────────────────── 写(基础)───────────────────────

    /**
     * admin 改 vendor 配置(partial update — null 字段保留旧值)。
     * Phase 2 限定改已存在的 vendor 行(seed runner 已经把 yaml 里的 vendor
     * 拷到 DB);新建 vendor 不在 Phase 2 范围(避免 api-key 不在 yaml 时的
     * "半残"状态)。
     * <p>Phase 6 起本方法<b>不接受</b> apiKey 字段(走独立端点):
     * partial update 语义下,"没传 apiKey" 跟 "传 null" 容易歧义。
     */
    @Transactional
    public AiVendorConfig update(String vendor, VendorConfigDto dto) {
        String key = normalizeVendor(vendor);
        AiVendorConfig m = repo.findByVendor(key)
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                        "vendor=" + vendor + " 在 ai_vendor_config 不存在;先在 yaml 配 spring.ai.providers."
                                + key + ".enabled=true 再启动,或等下次启动 seed 自动创建"));

        boolean enabledChanged = false;
        if (dto.getBaseUrl() != null) {
            String newUrl = dto.getBaseUrl().trim();
            if (newUrl.isEmpty()) {
                throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "base_url 不能为空字符串");
            }
            m.setBaseUrl(newUrl);
        }
        if (dto.getEnabled() != null && !dto.getEnabled().equals(m.getEnabled())) {
            m.setEnabled(dto.getEnabled());
            enabledChanged = true;
        }
        if (dto.getDescription() != null) m.setDescription(dto.getDescription());

        AiVendorConfig saved = repo.save(m);
        log.info("[VendorConfig] UPDATE vendor={} baseUrl={} enabled={}",
                key, saved.getBaseUrl(), saved.getEnabled());
        eventPublisher.publishEvent(new VendorConfigChangedEvent(this, saved,
                enabledChanged ? VendorConfigChangedEvent.ChangeType.ENABLED_TOGGLED
                        : VendorConfigChangedEvent.ChangeType.UPDATED));
        return saved;
    }

    // ─────────────────────── 写(Phase 6 apiKey)───────────────────────

    /**
     * Phase 6 — 设置/轮换 vendor 的 system apiKey(Phase 8 起加审计)。
     * <ul>
     *   <li>vendor 必须在 DB 存在(走启动期 seed 从 yaml 拷过来);不存在抛
     *       {@code LLM_MODEL_NOT_FOUND}</li>
     *   <li>用 {@code ApiKeyCipher} 加密 + 算 fingerprint,原子写两列</li>
     *   <li>发 {@code VendorConfigChangedEvent},{@code SystemKeyChatModelFactory}
     *       监听后清自身 cache,下次 call 走新 key</li>
     *   <li><b>Phase 8</b>:同步写 {@code ai_api_key_audit_log} 审计行
     *       ({@code VendorApiKeyAuditLogger} 失败 log warn 不阻塞主业务)</li>
     * </ul>
     * <p>明文不入库,不入 VO,不入审计(只在请求体里出现一次)。
     *
     * @param vendor       vendor 名(走小写归一化)
     * @param plaintextApiKey 新明文 Key
     * @param actorId      操作人 id(从 SecurityContext 拿;null 视为 SYSTEM)
     * @param requestIp    客户端 IP(从 HttpServletRequest 拿;可空)
     */
    @Transactional
    public AiVendorConfig setApiKey(String vendor, String plaintextApiKey,
                                    Long actorId, String requestIp) {
        if (plaintextApiKey == null || plaintextApiKey.isBlank()) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "apiKey 不能为空字符串");
        }
        String key = normalizeVendor(vendor);
        AiVendorConfig m = repo.findByVendor(key)
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                        "vendor=" + vendor + " 在 ai_vendor_config 不存在;先在 yaml 配 spring.ai.providers."
                                + key + ".enabled=true 再启动,或等下次启动 seed 自动创建"));

        // Phase 8 — 改前 fingerprint(可能 null,表示"第一次装 key"),审计 metadata 用
        String fingerprintBefore = m.getApiKeyFingerprint();

        m.setEncryptedApiKey(cipher.encrypt(plaintextApiKey));
        m.setApiKeyFingerprint(cipher.fingerprint(plaintextApiKey));
        AiVendorConfig saved = repo.save(m);

        log.info("[VendorConfig] SET_API_KEY vendor={} fingerprint={}", key, saved.getApiKeyFingerprint());
        // 事件 type 用 UPDATED 即可(apiKey 改完也是热更新,SystemKeyChatModelFactory 全部清 cache)
        eventPublisher.publishEvent(new VendorConfigChangedEvent(this, saved,
                VendorConfigChangedEvent.ChangeType.UPDATED));

        // Phase 8 — 写审计(同事务,失败 log warn 不阻塞)
        auditLogger.log(new VendorApiKeyAuditEvent(
                VendorApiKeyAuditAction.SET,
                key,
                actorId,
                "ADMIN",
                null,
                fingerprintBefore,
                saved.getApiKeyFingerprint(),
                requestIp));
        return saved;
    }

    /**
     * Phase 6 — 清空 vendor 的 system apiKey,回退 yaml 兜底(Phase 8 起加审计)。
     * <p>DB 两列置 NULL(yaml 兜底语义重新生效),发事件清
     * {@code SystemKeyChatModelFactory} cache(下次 call 重建时用 yaml 拿 key)。
     * <p>不允许清完变成"无 key 可用" — yaml 没配该 vendor 就让上游 401 报,
     * 而不是这里报"配置缺失",因为 admin 显式动作 = 期望恢复 yaml 行为。
     *
     * @param vendor    vendor 名
     * @param actorId   操作人 id(null 视为 SYSTEM)
     * @param requestIp 客户端 IP(可空)
     */
    @Transactional
    public AiVendorConfig clearApiKey(String vendor, Long actorId, String requestIp) {
        String key = normalizeVendor(vendor);
        AiVendorConfig m = repo.findByVendor(key)
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                        "vendor=" + vendor + " 在 ai_vendor_config 不存在"));

        // Phase 8 — 改前 fingerprint(肯定有 — clear 前提是有密文可清;但容错 null)
        String fingerprintBefore = m.getApiKeyFingerprint();

        m.setEncryptedApiKey(null);
        m.setApiKeyFingerprint(null);
        AiVendorConfig saved = repo.save(m);

        log.info("[VendorConfig] CLEAR_API_KEY vendor={} (回退 yaml)", key);
        eventPublisher.publishEvent(new VendorConfigChangedEvent(this, saved,
                VendorConfigChangedEvent.ChangeType.UPDATED));

        // Phase 8 — 写审计
        auditLogger.log(new VendorApiKeyAuditEvent(
                VendorApiKeyAuditAction.CLEAR,
                key,
                actorId,
                "ADMIN",
                null,
                fingerprintBefore,
                null,
                requestIp));
        return saved;
    }

    // ─────────────────────── seed ───────────────────────

    /**
     * 启动期 seed:vendor config 完全空时把 yaml {@code providers.*.{base-url, enabled}}
     * 拷到 DB。后续 DB 有数据就不再 seed(同 ai_model_catalog 策略)。
     *
     * <p>Phase 6 起不再 seed apiKey — {@code ApiKeyCipher} 是运行时 bean,seed
     * 阶段不一定就绪(虽然实际启动期是先 bean 再 CommandLineRunner);为避免耦合,
     * apiKey 永远从 yaml 起步,admin 想覆盖再走 PUT 端点。
     *
     * @return 实际新增条数
     */
    @Transactional
    public int seedFromYamlIfEmpty() {
        if (repo.count() > 0) {
            log.info("[VendorConfig] seed 跳过:表已有 {} 条", repo.count());
            return 0;
        }
        Map<String, AiProperties.Provider> yaml = yamlProps.getProviders();
        if (yaml == null || yaml.isEmpty()) {
            log.info("[VendorConfig] seed 跳过:yaml 无 providers 配置");
            return 0;
        }
        int created = 0;
        for (var entry : yaml.entrySet()) {
            String v = entry.getKey();
            AiProperties.Provider p = entry.getValue();
            if (p == null) continue;
            // base-url 必填(vendor 没 base-url 的话私 Key 模式就废了,seed 跳过)
            String baseUrl = p.getBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                log.info("[VendorConfig] seed 跳过 vendor={}:yaml 无 base-url", v);
                continue;
            }
            AiVendorConfig cfg = new AiVendorConfig();
            cfg.setVendor(v);
            cfg.setBaseUrl(baseUrl.trim());
            cfg.setEnabled(p.isEnabled());
            cfg.setDescription("Auto-seeded from application.yaml (Phase 2)");
            // apiKey 不 seed:Phase 6 起 admin 显式覆盖才进 DB
            repo.save(cfg);
            created++;
            log.info("[VendorConfig] seed 插入: vendor={} baseUrl={} enabled={}",
                    v, cfg.getBaseUrl(), cfg.getEnabled());
        }
        if (created > 0) {
            log.info("[VendorConfig] seed 完成:共插入 {} 条", created);
        }
        return created;
    }

    // ─────────────────────── 内部 helper ───────────────────────

    private static String normalizeVendor(String v) {
        return v.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 给 listener 用的精准失效。public 因为 listener 在 {@code com.nexusforge.ai.event} 包。
     */
    public void invalidateCache(String vendor) {
        if (vendor == null) {
            cache.invalidateAll();
            return;
        }
        cache.invalidate(normalizeVendor(vendor));
    }

    /**
     * 读路径返回值包装。区分"DB 命中"和"yaml 兜底"两种来源,
     * 写路径(更新时只接受 DB 命中)用它来判断。
     */
    public record VendorConfigView(AiVendorConfig entity, boolean fromYaml) {
        public static VendorConfigView db(AiVendorConfig m) {
            return new VendorConfigView(m, false);
        }
        public static VendorConfigView yamlFallback(AiProperties.Provider p) {
            AiVendorConfig m = new AiVendorConfig();
            m.setVendor("__yaml__");   // 标记"非 DB",不会被写路径误用
            m.setBaseUrl(p.getBaseUrl() == null ? "" : p.getBaseUrl());
            m.setEnabled(p.isEnabled());
            // apiKey 字段留 null — yaml 兜底 view 不含密文;SystemKeyChatModelFactory
            // 调 getEffectiveApiKey 时,view.entity().getEncryptedApiKey() == null
            // 会走 yaml 兜底分支,从 yamlProps 直接拿明文
            return new VendorConfigView(m, true);
        }
    }
}

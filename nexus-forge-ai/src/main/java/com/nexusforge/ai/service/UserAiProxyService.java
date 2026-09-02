package com.nexusforge.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.ai.controller.dto.UserAiProxyDto;
import com.nexusforge.ai.entity.UserAiProxy;
import com.nexusforge.ai.event.UserAiProxyChangedEvent;
import com.nexusforge.ai.repository.UserAiProxyRepository;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户 AI 代理 Service(Phase 3 用户级 BYOK 多端点)。
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>两个 Caffeine cache:
 *       <ul>
 *         <li>{@code listCache:userId} → List&lt;UserAiProxy&gt;(用户所有代理,is_default 优先)</li>
 *         <li>{@code defaultCache:userId} → UserAiProxy(用户当前活跃代理)</li>
 *       </ul>
 *   </li>
 *   <li>key = userId(单用户规模小,分两个 cache 便于精准失效 + 类型安全)</li>
 *   <li>TTL 5 min(异常路径兜底:事件监听漏掉也能自动过期)</li>
 *   <li>写操作发 {@link UserAiProxyChangedEvent} → listener 精准失效该 userId 的两条 cache</li>
 *   <li>多实例:每个实例本地 Caffeine 独立;5 min TTL 兜底跨实例不一致
 *       (Phase 4 视情况加 Redis pub/sub 广播)</li>
 * </ul>
 *
 * <h3>与 {@code AiPreferenceService} 共存</h3>
 * 本 service 是 Phase 3 新"多代理"机制;{@code UserAiPreference} 是 Phase 1-2
 * 旧"单偏好行"机制。Phase 3 不迁移旧数据,两套并存 — 用户的 BYOK 体验由本 service
 * 提供,旧 {@code UserAiPreference} 仍可用(没设默认代理时回退到旧逻辑)。
 *
 * <h3>Vendor 校验</h3>
 * 创建/更新时 {@code dto.vendor} 必须属于 {@code AiVendorRegistry.OPENAI_COMPATIBLE_VENDORS}
 * (anthropic 暂不支持私 Key);否则抛 {@code LLM_MODEL_NOT_FOUND}。
 *
 * <h3>is_default 唯一性</h3>
 * <ul>
 *   <li>DB 层:partial unique index {@code uq_user_ai_proxy_one_default} 兜底</li>
 *   <li>app 层:{@link #setDefault} 事务里先 unmark 同 user 已有 default,再 mark 新 default</li>
 *   <li>普通 {@code update} 时若 dto.isDefault=true,同样走 {@code setDefault} 路径(避免逻辑分裂)</li>
 * </ul>
 */
@Slf4j
@Service
public class UserAiProxyService {

    /** Cache key 前缀(防命名冲突 + 调试可辨识) */
    private static final String LIST_KEY_PREFIX = "upl:";
    private static final String DEFAULT_KEY_PREFIX = "upd:";

    private final UserAiProxyRepository repo;
    private final ApiKeyCipher cipher;
    private final AiVendorRegistry vendorRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /** 用户的代理列表缓存(用于 /api/ai/proxies 与 PreferenceResolver) */
    private final Cache<String, List<UserAiProxy>> listCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1024)   // 用户数上限 ~ 1k
            .build();

    /** 用户的当前活跃代理缓存(用于 PreferenceResolver 解析热点) */
    private final Cache<String, Optional<UserAiProxy>> defaultCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(1024)
            .build();

    public UserAiProxyService(UserAiProxyRepository repo,
                              ApiKeyCipher cipher,
                              AiVendorRegistry vendorRegistry,
                              ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.cipher = cipher;
        this.vendorRegistry = vendorRegistry;
        this.eventPublisher = eventPublisher;
    }

    // ─────────────────────── 读 ───────────────────────

    /**
     * 单条详情 + 所有权校验。代理不存在或不属于当前 user 抛 404(防越权)。
     */
    public UserAiProxy findById(Long userId, Long id) {
        return repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_PROXY_NOT_FOUND,
                        "代理 id=" + id + " 不存在或不属于当前 user"));
    }

    /**
     * 用户的代理列表(is_default 优先,alias 字典序)。优先走缓存。
     */
    public List<UserAiProxy> listByUserId(Long userId) {
        if (userId == null) return List.of();
        String key = listKey(userId);
        List<UserAiProxy> cached = listCache.getIfPresent(key);
        if (cached != null) return cached;
        List<UserAiProxy> rows = repo.findByUserIdOrderByIsDefaultDescNameAsc(userId);
        listCache.put(key, rows);
        return rows;
    }

    /**
     * 用户的当前活跃代理(可能没有 — 用户没标默认)。优先走缓存。
     * <p>这是 {@code PreferenceResolver} 解析无 explicit proxy/model 时的热路径,
     * 缓存命中 ~ 微秒级返回,避免每请求都查 DB。
     */
    public Optional<UserAiProxy> findDefaultByUserId(Long userId) {
        if (userId == null) return Optional.empty();
        String key = defaultKey(userId);
        Optional<UserAiProxy> cached = defaultCache.getIfPresent(key);
        if (cached != null) return cached;
        Optional<UserAiProxy> row = repo.findByUserIdAndIsDefaultTrue(userId);
        defaultCache.put(key, row);
        return row;
    }

    // ─────────────────────── 写 ───────────────────────

    /**
     * 创建代理。
     * <ul>
     *   <li>name 唯一性预检(同 user 内 alias 不重复)</li>
     *   <li>vendor 必须在 OpenAI 兼容集合里</li>
     *   <li>apiKey 必填(BYOK 场景下"建代理不带 Key"无意义)</li>
     *   <li>若 {@code dto.isDefault=true},事务里 unmark 同 user 已有 default,再 mark 新代理为 default</li>
     * </ul>
     */
    @Transactional
    public UserAiProxy create(Long userId, UserAiProxyDto dto) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        String name = dto.getName().trim();
        String vendor = normalizeVendor(dto.getVendor());
        if (!vendorRegistry.supportsPrivateKey(vendor)) {
            throw new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                    "vendor=" + vendor + " 不支持用户级代理(仅 OpenAI 协议家族 vendor 可用)");
        }
        String baseUrl = dto.getBaseUrl().trim();
        if (baseUrl.isEmpty()) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "baseUrl 不能为空");
        }
        String apiKey = dto.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST,
                    "apiKey 不能为空(BYOK 场景必填)");
        }
        if (repo.existsByUserIdAndName(userId, name)) {
            throw new BusinessException(ResultCode.LLM_PROXY_NOT_FOUND,
                    "已存在同名代理: name=" + name);
        }

        UserAiProxy p = new UserAiProxy();
        p.setUserId(userId);
        p.setName(name);
        p.setVendor(vendor);
        p.setBaseUrl(baseUrl);
        p.setEncryptedApiKey(cipher.encrypt(apiKey));
        p.setApiKeyFingerprint(cipher.fingerprint(apiKey));
        if (dto.getDefaultModel() != null && !dto.getDefaultModel().isBlank()) {
            p.setDefaultModel(dto.getDefaultModel().trim());
        }
        p.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
        p.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()));
        p.setDescription(dto.getDescription());

        UserAiProxy saved = repo.save(p);
        log.info("[UserAiProxy] CREATE userId={} id={} name={} vendor={} isDefault={}",
                userId, saved.getId(), name, vendor, saved.getIsDefault());

        // 若标了 is_default → 事务里 unmark 其他
        if (Boolean.TRUE.equals(saved.getIsDefault())) {
            unmarkOtherDefaults(userId, saved.getId());
            eventPublisher.publishEvent(new UserAiProxyChangedEvent(this, saved,
                    UserAiProxyChangedEvent.ChangeType.DEFAULT_CHANGED));
        } else {
            eventPublisher.publishEvent(new UserAiProxyChangedEvent(this, saved,
                    UserAiProxyChangedEvent.ChangeType.CREATED));
        }
        return saved;
    }

    /**
     * partial update — DTO 字段非 null 时才覆盖 entity。
     * <p>vendor 禁止改(改了会跟 cache key 失配,走 delete + create 更安全)。
     * <p>name 允许改 — 改完仅 5 min TTL 兜底,缓存键基于 id 不基于 name,所以不需要额外失效。
     */
    @Transactional
    public UserAiProxy update(Long userId, Long id, UserAiProxyDto dto) {
        UserAiProxy p = findById(userId, id);

        // name 唯一性预检(改了才检)
        if (dto.getName() != null) {
            String newName = dto.getName().trim();
            if (!newName.equals(p.getName()) && repo.existsByUserIdAndName(userId, newName)) {
                throw new BusinessException(ResultCode.LLM_PROXY_NOT_FOUND,
                        "已存在同名代理: name=" + newName);
            }
            p.setName(newName);
        }
        if (dto.getBaseUrl() != null) {
            String newUrl = dto.getBaseUrl().trim();
            if (newUrl.isEmpty()) {
                throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "baseUrl 不能为空");
            }
            p.setBaseUrl(newUrl);
        }
        if (dto.getDefaultModel() != null) {
            String m = dto.getDefaultModel().trim();
            p.setDefaultModel(m.isEmpty() ? null : m);
        }
        if (dto.getEnabled() != null) p.setEnabled(dto.getEnabled());
        if (dto.getDescription() != null) p.setDescription(dto.getDescription());

        // Key 处理(跟 UpdatePreferenceDto 保持一致的三态语义)
        boolean wantClear = Boolean.TRUE.equals(dto.getClearApiKey());
        boolean hasNewKey = dto.getApiKey() != null && !dto.getApiKey().isBlank();
        if (wantClear) {
            // BYOK 场景下"清 Key"等价于禁用代理 — 不允许代理存在但无 Key
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST,
                    "BYOK 代理不支持清除 Key:请改用 enabled=false 禁用,或填新 Key 覆盖");
        } else if (hasNewKey) {
            p.setEncryptedApiKey(cipher.encrypt(dto.getApiKey()));
            p.setApiKeyFingerprint(cipher.fingerprint(dto.getApiKey()));
            log.info("[UserAiProxy] 覆盖 Key: userId={} id={} fp={}", userId, id, p.getApiKeyFingerprint());
        }

        UserAiProxy saved = repo.save(p);

        // isDefault 处理(若 dto.isDefault 非 null,走 setDefault 路径以复用 unmark 逻辑)
        if (dto.getIsDefault() != null) {
            boolean wantDefault = Boolean.TRUE.equals(dto.getIsDefault());
            if (wantDefault && !Boolean.TRUE.equals(saved.getIsDefault())) {
                unmarkOtherDefaults(userId, saved.getId());
                saved.setIsDefault(true);
                saved = repo.save(saved);
                log.info("[UserAiProxy] SET_DEFAULT userId={} id={}", userId, id);
                eventPublisher.publishEvent(new UserAiProxyChangedEvent(this, saved,
                        UserAiProxyChangedEvent.ChangeType.DEFAULT_CHANGED));
                return saved;
            } else if (!wantDefault && Boolean.TRUE.equals(saved.getIsDefault())) {
                // unmark 当前 default
                saved.setIsDefault(false);
                saved = repo.save(saved);
                log.info("[UserAiProxy] UNMARK_DEFAULT userId={} id={}", userId, id);
                eventPublisher.publishEvent(new UserAiProxyChangedEvent(this, saved,
                        UserAiProxyChangedEvent.ChangeType.DEFAULT_CHANGED));
                return saved;
            }
            // isDefault 没变 → 走普通 UPDATED 事件
        }

        log.info("[UserAiProxy] UPDATE userId={} id={} name={} vendor={}",
                userId, saved.getId(), saved.getName(), saved.getVendor());
        eventPublisher.publishEvent(new UserAiProxyChangedEvent(this, saved,
                UserAiProxyChangedEvent.ChangeType.UPDATED));
        return saved;
    }

    /**
     * 标记代理为用户的"当前活跃代理"。事务里 unmark 同 user 其他 default 再 mark 新 default。
     */
    @Transactional
    public UserAiProxy setDefault(Long userId, Long id) {
        UserAiProxy p = findById(userId, id);
        if (Boolean.TRUE.equals(p.getIsDefault())) {
            // 已经是 default,幂等返回
            return p;
        }
        unmarkOtherDefaults(userId, p.getId());
        p.setIsDefault(true);
        UserAiProxy saved = repo.save(p);
        log.info("[UserAiProxy] SET_DEFAULT userId={} id={} name={}", userId, id, saved.getName());
        eventPublisher.publishEvent(new UserAiProxyChangedEvent(this, saved,
                UserAiProxyChangedEvent.ChangeType.DEFAULT_CHANGED));
        return saved;
    }

    /**
     * 硬删除代理。若被删的是当前 default,DB partial unique 允许 0 个 default(不报错),
     * 用户的偏好解析会回退到旧 {@code UserAiPreference} / global default。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        UserAiProxy p = findById(userId, id);
        repo.delete(p);
        log.info("[UserAiProxy] DELETE userId={} id={} name={} vendor={} wasDefault={}",
                userId, id, p.getName(), p.getVendor(), p.getIsDefault());
        eventPublisher.publishEvent(UserAiProxyChangedEvent.deleted(this, userId, id));
    }

    // ─────────────────────── 内部 helper ───────────────────────

    /**
     * 事务内:把同 user 下的其他 is_default=true 代理 unmark 掉(允许 0 个 default)。
     * 不会影响当前正在被 mark 的新 default 本身(用 {@code excludeId} 跳过)。
     */
    private void unmarkOtherDefaults(Long userId, Long excludeId) {
        List<UserAiProxy> rows = repo.findByUserIdOrderByIsDefaultDescNameAsc(userId);
        for (UserAiProxy r : rows) {
            if (Objects.equals(r.getId(), excludeId)) continue;
            if (Boolean.TRUE.equals(r.getIsDefault())) {
                r.setIsDefault(false);
                repo.save(r);
            }
        }
    }

    private static String normalizeVendor(String v) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "vendor 不能为空");
        }
        return v.trim().toLowerCase(Locale.ROOT);
    }

    private static String listKey(Long userId) {
        return LIST_KEY_PREFIX + userId;
    }

    private static String defaultKey(Long userId) {
        return DEFAULT_KEY_PREFIX + userId;
    }

    // ─────────────────────── 缓存失效入口(给 listener) ───────────────────────

    /**
     * 给 {@code UserAiProxyChangeListener} 用的精准失效:清该 userId 的 list + default cache。
     */
    public void invalidateCacheForUser(Long userId) {
        if (userId == null) {
            invalidateAllCaches();
            return;
        }
        listCache.invalidate(listKey(userId));
        defaultCache.invalidate(defaultKey(userId));
    }

    /**
     * 极端兜底:全部 cache 清空(理论不会触发,留给 listener 的 userId 为 null 分支)。
     */
    public void invalidateAllCaches() {
        listCache.invalidateAll();
        defaultCache.invalidateAll();
    }
}

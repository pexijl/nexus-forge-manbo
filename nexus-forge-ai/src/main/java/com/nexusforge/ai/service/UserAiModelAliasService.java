package com.nexusforge.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexusforge.ai.controller.dto.UserAiModelAliasDto;
import com.nexusforge.ai.entity.UserAiModelAlias;
import com.nexusforge.ai.event.UserAiModelAliasChangedEvent;
import com.nexusforge.ai.repository.UserAiModelAliasRepository;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 用户 model alias Service(Phase 4 模型别名)。
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>key = {@code "alias:{userId}:{aliasLower}"} (小写 + 去前后空格)</li>
 *   <li>value = {@code Optional<UserAiModelAlias>}(空 Optional 也缓存,避免无别名用户每请求穿透 DB)</li>
 *   <li>TTL 5 min(异常路径兜底:事件监听漏掉也能自动过期)</li>
 *   <li>写操作发 {@link UserAiModelAliasChangedEvent} → listener 精准失效
 *       (含 alias 改名时清旧 key + 新 key 两条)</li>
 *   <li>多实例:本地 Caffeine + 5 min TTL 兜底(Phase 5 视情况加 Redis pub/sub)</li>
 * </ul>
 *
 * <h3>大小写策略</h3>
 * 全部归一化为小写(存 + 查都用 lowercase)避免大小写碎片;
 * 原始大小写只在响应 VO 里回显给用户。
 *
 * <h3>target 校验策略</h3>
 * target_vendor / target_model 在 create / update 时只校验非空 + 长度,
 * 不校验 catalog 存在性 —— 用户可以提前建好 alias,等 admin 把 model 加到
 * catalog 后自动生效。运行时 resolver 改写 alias 后会走 catalog 校验,
 * target 不存在或 disabled 抛现有 {@code LLM_MODEL_NOT_FOUND} /
 * {@code LLM_MODEL_DISABLED},不引入新 ResultCode。
 */
@Slf4j
@Service
public class UserAiModelAliasService {

    /** Cache key 前缀 */
    private static final String CACHE_KEY_PREFIX = "alias:";

    private final UserAiModelAliasRepository repo;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * alias 解析热路径缓存(被 {@code PreferenceResolver} 解析时调用)。
     * key = "alias:{userId}:{aliasLower}";value = Optional(空也缓存)。
     */
    private final Cache<String, Optional<UserAiModelAlias>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(2048)   // 用户数 × 用户平均 alias 数 ~ 2k
            .build();

    public UserAiModelAliasService(UserAiModelAliasRepository repo,
                                   ApplicationEventPublisher eventPublisher) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
    }

    // ─────────────────────── 读 ───────────────────────

    /**
     * 单条详情 + 所有权校验。alias 不存在或不属于当前 user 抛 404(防越权)。
     */
    public UserAiModelAlias findById(Long userId, Long id) {
        return repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_PROXY_NOT_FOUND,
                        "alias id=" + id + " 不存在或不属于当前 user"));
    }

    /**
     * 用户的 alias 列表(按 alias 字典序)。不走缓存(列表相对小,扫表即可)。
     */
    public List<UserAiModelAlias> listByUserId(Long userId) {
        return repo.findByUserIdOrderByAliasAsc(userId);
    }

    /**
     * alias 解析热路径(被 {@code PreferenceResolver} 调用)。优先走缓存。
     * <p>大小写不敏感:用户在 chat 请求里填"我的 GPT" / "我的 gpt" / "我的 GPT " 都能命中。
     *
     * @return 命中的 alias 实体(可能 enabled=false);未命中或用户为空 → Optional.empty()
     */
    public Optional<UserAiModelAlias> findByUserIdAndAlias(Long userId, String alias) {
        if (userId == null || alias == null || alias.isBlank()) return Optional.empty();
        String normalizedAlias = alias.trim();
        if (normalizedAlias.isEmpty()) return Optional.empty();
        // 关键:含冒号的不查 alias(那是 "vendor:model" 格式,走 vendor:model 路径)
        if (normalizedAlias.contains(":")) return Optional.empty();

        String key = cacheKey(userId, normalizedAlias);
        Optional<UserAiModelAlias> cached = cache.getIfPresent(key);
        if (cached != null) return cached;
        Optional<UserAiModelAlias> row = repo.findByUserIdAndAliasIgnoreCase(userId, normalizedAlias);
        cache.put(key, row);
        return row;
    }

    // ─────────────────────── 写 ───────────────────────

    /**
     * 创建 alias。
     * <ul>
     *   <li>alias 唯一性预检(同 user 内大小写不敏感唯一)</li>
     *   <li>alias 名 trim + 不含冒号(由 DTO 的 @Pattern 校验,这里再保险一次)</li>
     *   <li>target_vendor / target_model 非空 + trim</li>
     *   <li>不发事件 ???? — create 跟 update 走不同事件类型,便于审计</li>
     * </ul>
     */
    @Transactional
    public UserAiModelAlias create(Long userId, UserAiModelAliasDto dto) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        String alias = normalizeAlias(dto.getAlias());
        if (alias.contains(":")) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "alias 名不能含冒号");
        }
        String targetVendor = dto.getTargetVendor().trim().toLowerCase(Locale.ROOT);
        String targetModel = dto.getTargetModel().trim();
        if (targetVendor.isEmpty()) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "targetVendor 不能为空");
        }
        if (targetModel.isEmpty()) {
            throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "targetModel 不能为空");
        }
        if (repo.existsByUserIdAndAliasIgnoreCase(userId, alias)) {
            throw new BusinessException(ResultCode.LLM_PROXY_NOT_FOUND,
                    "已存在同名 alias: " + alias);
        }

        UserAiModelAlias a = new UserAiModelAlias();
        a.setUserId(userId);
        a.setAlias(alias);
        a.setTargetVendor(targetVendor);
        a.setTargetModel(targetModel);
        a.setEnabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled());
        a.setDescription(dto.getDescription());

        UserAiModelAlias saved = repo.save(a);
        log.info("[ModelAlias] CREATE userId={} id={} alias='{}' → {}:{}",
                userId, saved.getId(), alias, targetVendor, targetModel);
        eventPublisher.publishEvent(new UserAiModelAliasChangedEvent(this, saved,
                UserAiModelAliasChangedEvent.ChangeType.CREATED));
        return saved;
    }

    /**
     * partial update — DTO 字段非 null 时才覆盖 entity。
     * <p>alias 改名时发 {@code UserAiModelAliasChangedEvent.renamed(...)} 让 listener
     * 失效旧 key + 新 key 两条缓存(避免改名后旧 cache key 还在导致命中"alias 不存在")。
     */
    @Transactional
    public UserAiModelAlias update(Long userId, Long id, UserAiModelAliasDto dto) {
        UserAiModelAlias a = findById(userId, id);
        String oldAliasName = a.getAlias();

        // alias 改名 — 唯一性预检
        if (dto.getAlias() != null) {
            String newAlias = normalizeAlias(dto.getAlias());
            if (newAlias.contains(":")) {
                throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "alias 名不能含冒号");
            }
            if (!newAlias.equalsIgnoreCase(a.getAlias()) && repo.existsByUserIdAndAliasIgnoreCase(userId, newAlias)) {
                throw new BusinessException(ResultCode.LLM_PROXY_NOT_FOUND,
                        "已存在同名 alias: " + newAlias);
            }
            a.setAlias(newAlias);
        }
        if (dto.getTargetVendor() != null) {
            String v = dto.getTargetVendor().trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "targetVendor 不能为空");
            }
            a.setTargetVendor(v);
        }
        if (dto.getTargetModel() != null) {
            String m = dto.getTargetModel().trim();
            if (m.isEmpty()) {
                throw new BusinessException(ResultCode.LLM_INVALID_REQUEST, "targetModel 不能为空");
            }
            a.setTargetModel(m);
        }
        if (dto.getEnabled() != null) a.setEnabled(dto.getEnabled());
        if (dto.getDescription() != null) a.setDescription(dto.getDescription());

        UserAiModelAlias saved = repo.save(a);
        log.info("[ModelAlias] UPDATE userId={} id={} alias='{}' → {}:{}",
                userId, saved.getId(), saved.getAlias(), saved.getTargetVendor(), saved.getTargetModel());

        // 改名 → 发 renamed 事件携带旧名;否则发普通 UPDATED
        if (dto.getAlias() != null && !saved.getAlias().equalsIgnoreCase(oldAliasName)) {
            eventPublisher.publishEvent(UserAiModelAliasChangedEvent.renamed(this, saved, oldAliasName));
        } else {
            eventPublisher.publishEvent(new UserAiModelAliasChangedEvent(this, saved,
                    UserAiModelAliasChangedEvent.ChangeType.UPDATED));
        }
        return saved;
    }

    /**
     * 硬删除 alias。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        UserAiModelAlias a = findById(userId, id);
        repo.delete(a);
        log.info("[ModelAlias] DELETE userId={} id={} alias='{}'",
                userId, id, a.getAlias());
        eventPublisher.publishEvent(UserAiModelAliasChangedEvent.deleted(
                this, userId, id, a.getAlias()));
    }

    // ─────────────────────── 内部 helper ───────────────────────

    private static String normalizeAlias(String s) {
        return s == null ? "" : s.trim();
    }

    private static String cacheKey(Long userId, String alias) {
        // 小写归一化:DB 查用 IgnoreCase,cache key 也用小写避免 "GPT" / "gpt" 碎片
        return CACHE_KEY_PREFIX + userId + ":" + alias.toLowerCase(Locale.ROOT);
    }

    // ─────────────────────── 缓存失效入口(给 listener) ───────────────────────

    /**
     * 给 {@code UserAiModelAliasChangeListener} 用的精准失效:
     * 清该 userId 的指定 alias cache key(改名时同时传 oldAlias + newAlias)。
     * <p>oldAlias / newAlias 任一为 null 时只清另一条;都为 null 时清空全部 cache(兜底)。
     */
    public void invalidateCache(Long userId, String oldAlias, String newAlias) {
        if (userId == null) {
            cache.invalidateAll();
            return;
        }
        if (oldAlias != null) cache.invalidate(cacheKey(userId, oldAlias));
        if (newAlias != null) cache.invalidate(cacheKey(userId, newAlias));
        // 兜底:如果都 null(理论上不应发生),清空全部
        if (oldAlias == null && newAlias == null) {
            cache.invalidateAll();
        }
    }
}

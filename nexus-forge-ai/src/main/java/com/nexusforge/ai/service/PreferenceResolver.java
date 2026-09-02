package com.nexusforge.ai.service;

import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.ai.entity.AiGlobalDefault;
import com.nexusforge.ai.entity.UserAiModelAlias;
import com.nexusforge.ai.entity.UserAiPreference;
import com.nexusforge.ai.entity.UserAiProxy;
import com.nexusforge.ai.repository.AiGlobalDefaultRepository;
import com.nexusforge.ai.repository.UserAiPreferenceRepository;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.provider.VendorChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * AI 用户个性化偏好解析器。
 *
 * <p>职责:在请求进入 LLM 之前,根据当前 userId + 请求体里可选的 model / proxyId 字段,
 * 解析出最终生效的 (vendor, model, apiKey, baseUrl) 与三态 KeySource。
 *
 * <h3>解析优先级(从高到低,Phase 4 更新)</h3>
 * <ol>
 *   <li>请求体显式 {@code proxyId} → 加载该 proxy;USER_PRIVATE_KEY 模式</li>
 *   <li>请求体显式 {@code model = "vendor:model"}(legacy,临时一脚)
 *       <ul>
 *         <li>若用户有同 vendor 的私 Key 代理 → USER_PRIVATE_KEY(用代理)</li>
 *         <li>否则 → SYSTEM(走系统共享 Key)</li>
 *       </ul>
 *   </li>
 *   <li>请求体 {@code model} 是用户的 alias?(Phase 4 新) → 改写为
 *       {@code targetVendor:targetModel} 后按上一条 "vendor:model" 路径解析
 *       <ul>
 *         <li>alias 命中且 enabled → 改写 model,继续</li>
 *         <li>alias 不存在 / enabled=false → 静默 fall through(原 model 字符串继续原路径)</li>
 *       </ul>
 *   </li>
 *   <li>用户标了 {@code is_default=true} 的代理(Phase 3 新) → USER_PRIVATE_KEY</li>
 *   <li>{@code user_ai_preference} 行存在?(Phase 1-2 legacy 单行 BYOK)</li>
 *   <li>{@code ai_global_default}(单行表) + yaml 系统共享 Key</li>
 *   <li>{@code application.yaml} 的 {@code spring.ai.default-vendor/default-model}</li>
 * </ol>
 *
 * <h3>三态分流</h3>
 * <ul>
 *   <li>{@link KeySource#SYSTEM} — 走默认 vendor/model,用 yaml 系统 Key;走降级链;计入平台 quota</li>
 *   <li>{@link KeySource#USER_OVERRIDE_SYSTEM_KEY} — 用户设了 vendor/model 但 Key 仍用系统;走降级链;计入平台 quota</li>
 *   <li>{@link KeySource#USER_PRIVATE_KEY} — 用户填了私 Key(走 {@code user_ai_preference} 或 {@code user_ai_proxy});
 *       不走降级链;跳过平台 quota</li>
 * </ul>
 *
 * <h3>Phase 3 vs Phase 1-2 兼容</h3>
 * {@code user_ai_preference} 旧机制仍受支持(优先级 4)。Phase 3 是叠加:用户
 * 可选继续用旧 preference(单 vendor + 1 model + 1 key),也可切到新 proxy 多端点。
 * 业务面无感知,只看 Resolved。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreferenceResolver {

    private final UserAiPreferenceRepository userPrefRepo;
    private final AiGlobalDefaultRepository globalRepo;
    private final ApiKeyCipher cipher;
    private final VendorChatModelFactory factory;
    private final AiProperties props;
    private final AiVendorRegistry vendorRegistry;
    private final UserAiProxyService userProxyService;
    private final UserAiModelAliasService userAliasService;

    // ─────────────────────── 主入口 ───────────────────────

    /**
     * Phase 4 入口(带 alias 解析 + proxyId 优先级)。
     *
     * @param userId        当前用户 ID(可为 null 表示未登录,匿名走全局默认)
     * @param explicitModel 请求体里的 model 字段(可为 null / blank 表示未指定)
     * @param proxyId       请求体里的 proxyId 字段(可为 null)
     * @return 解析结果;永远不会为 null
     */
    public Resolved resolve(Long userId, String explicitModel, Long proxyId) {
        // 1. 优先级最高:请求体显式 proxyId
        if (proxyId != null) {
            return resolveByProxyId(userId, proxyId, explicitModel);
        }
        // 2. 显式 model = "vendor:model"(legacy) — 含 Phase 4 alias 改写后的形式
        if (explicitModel != null && !explicitModel.isBlank()) {
            String modelField = explicitModel.trim();
            // Phase 4 — alias 解析:不含冒号的 model 字符串先查 alias,命中就改写
            String rewritten = tryRewriteFromAlias(userId, modelField);
            if (rewritten != null) {
                modelField = rewritten;
            }
            Resolved fromRequest = resolveFromRequest(modelField, userId);
            if (fromRequest != null) return fromRequest;
            // 解析失败 → 报错(用户显式指定,不该悄悄降级)
        }
        // 3. 用户的默认代理(Phase 3 新)
        if (userId != null) {
            Optional<UserAiProxy> defaultProxy = userProxyService.findDefaultByUserId(userId);
            if (defaultProxy.isPresent()) {
                return resolveFromProxy(defaultProxy.get(), explicitModel);
            }
        }
        // 4. user_ai_preference(Phase 1-2 legacy)
        if (userId != null) {
            Optional<UserAiPreference> prefOpt = userPrefRepo.findById(userId);
            if (prefOpt.isPresent() && Boolean.TRUE.equals(prefOpt.get().getEnabled())) {
                return resolveFromPreference(prefOpt.get());
            }
        }
        // 5. 全局默认
        return resolveFromGlobal();
    }

    /**
     * 兼容旧调用方(无 proxyId 字段)。等价于 {@code resolve(userId, explicitModel, null)}。
     */
    public Resolved resolve(Long userId, String explicitModel) {
        return resolve(userId, explicitModel, null);
    }

    /**
     * 把解析结果转换为可调用的 ChatModel。
     * 私 Key 模式走 {@link VendorChatModelFactory} 动态构造;
     * 系统模式让调用方继续走 {@code ChatModelRouter} 解析。
     */
    public ChatModel resolveChatModel(Resolved r) {
        if (r.source() == KeySource.USER_PRIVATE_KEY) {
            return factory.resolveOrCreate(r.vendor(), r.baseUrl(), r.apiKey());
        }
        // SYSTEM / USER_OVERRIDE_SYSTEM_KEY 走原有 ChatModelRouter
        return null;   // 调用方按 null 走 router 路径
    }

    // ─────────────────────── 内部解析分支 ───────────────────────

    /**
     * 优先级 1:用户显式指定 proxyId。直接从代理拿 vendor + baseUrl + apiKey。
     */
    private Resolved resolveByProxyId(Long userId, Long proxyId, String explicitModel) {
        if (userId == null) {
            throw new LlmException(ResultCode.UNAUTHORIZED, "使用代理需要先登录");
        }
        UserAiProxy p = userProxyService.findById(userId, proxyId);   // 404 抛 LLM_PROXY_NOT_FOUND
        if (Boolean.FALSE.equals(p.getEnabled())) {
            throw new LlmException(ResultCode.LLM_PROXY_DISABLED,
                    "代理 id=" + proxyId + " 已被禁用");
        }
        // 决定最终 model:request override > proxy.defaultModel > vendor yaml default
        String model = pickModel(explicitModel, p.getDefaultModel(), p.getVendor());
        String apiKey = cipher.decrypt(p.getEncryptedApiKey());
        log.debug("[PrefResolver] proxyId={} vendor={} model={} (USER_PRIVATE_KEY)", proxyId, p.getVendor(), model);
        return new Resolved(p.getVendor(), model, apiKey, p.getApiKeyFingerprint(),
                p.getBaseUrl(), KeySource.USER_PRIVATE_KEY);
    }

    /**
     * 优先级 2:显式 model = "vendor:model"(legacy)。若用户有同 vendor 的代理 → 用代理 Key;
     * 否则 → 系统 Key。
     */
    private Resolved resolveFromRequest(String modelField, Long userId) {
        String v;
        String m;
        int idx = modelField.indexOf(':');
        if (idx > 0) {
            v = modelField.substring(0, idx).trim();
            m = modelField.substring(idx + 1).trim();
        } else {
            // 没 vendor 前缀 → 走"请求带 model 但没指定 vendor" = 走用户偏好或全局
            return null;
        }
        String vendor = v.toLowerCase(Locale.ROOT);
        if (!vendorRegistry.supportsPrivateKey(v) && !isVendorEnabledInYaml(v)) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND,
                    "请求指定 vendor=" + v + " 不支持或未启用");
        }
        // 显式 vendor:用户是否有同 vendor 的代理(任意 enabled)?
        if (userId != null) {
            Optional<UserAiProxy> match = userProxyService.listByUserId(userId).stream()
                    .filter(x -> vendor.equalsIgnoreCase(x.getVendor()))
                    .filter(x -> Boolean.TRUE.equals(x.getEnabled()))
                    .findFirst();
            if (match.isPresent()) {
                UserAiProxy p = match.get();
                String apiKey = cipher.decrypt(p.getEncryptedApiKey());
                log.debug("[PrefResolver] request vendor:model + 代理 vendor={} model={} (USER_PRIVATE_KEY)",
                        vendor, m);
                return new Resolved(vendor, m, apiKey, p.getApiKeyFingerprint(),
                        p.getBaseUrl(), KeySource.USER_PRIVATE_KEY);
            }
        }
        // 否则走系统 Key
        log.debug("[PrefResolver] request vendor:model + 系统 Key vendor={} model={}", vendor, m);
        return new Resolved(vendor, m, null, null, null, KeySource.SYSTEM);
    }

    /**
     * 优先级 3:用户默认代理。model 决定:request override > proxy.defaultModel > vendor yaml。
     */
    private Resolved resolveFromProxy(UserAiProxy p, String explicitModel) {
        if (Boolean.FALSE.equals(p.getEnabled())) {
            // 默认代理被禁:不该发生(用户在 UI 应该 unmark default),但兜底 fall through
            log.warn("[PrefResolver] 用户 {} 的默认代理 id={} 已禁用,fall through", p.getUserId(), p.getId());
            return resolveFromGlobal();
        }
        String model = pickModel(explicitModel, p.getDefaultModel(), p.getVendor());
        String apiKey = cipher.decrypt(p.getEncryptedApiKey());
        log.debug("[PrefResolver] 默认代理 id={} vendor={} model={} (USER_PRIVATE_KEY)",
                p.getId(), p.getVendor(), model);
        return new Resolved(p.getVendor(), model, apiKey, p.getApiKeyFingerprint(),
                p.getBaseUrl(), KeySource.USER_PRIVATE_KEY);
    }

    /**
     * 优先级 4:user_ai_preference 旧行(legacy)。
     */
    private Resolved resolveFromPreference(UserAiPreference p) {
        String v = p.getVendor().toLowerCase(Locale.ROOT);
        String m = p.getModel();
        if (p.getEncryptedApiKey() != null && p.getEncryptedApiKey().length > 0) {
            String apiKey = cipher.decrypt(p.getEncryptedApiKey());
            log.debug("[PrefResolver] 旧 preference + 私 Key vendor={} model={}", v, m);
            return new Resolved(v, m, apiKey, p.getApiKeyFingerprint(), null, KeySource.USER_PRIVATE_KEY);
        }
        log.debug("[PrefResolver] 旧 preference + 系统 Key vendor={} model={}", v, m);
        return new Resolved(v, m, null, null, null, KeySource.USER_OVERRIDE_SYSTEM_KEY);
    }

    /**
     * 优先级 5:全局默认 + yaml 系统 Key。
     */
    private Resolved resolveFromGlobal() {
        AiGlobalDefault g = globalRepo.findById(1)
                .orElseThrow(() -> new LlmException(ResultCode.LLM_CONFIG_MISSING, "ai_global_default 行缺失"));
        if (Boolean.FALSE.equals(g.getEnabled())) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND, "AI 全局默认已禁用");
        }
        String v = g.getVendor().toLowerCase(Locale.ROOT);
        String m = g.getModel();
        // Sentinel 校验:管理员未通过 PUT /api/admin/ai/global-default 设过真实 model,
        // 直接拒绝(让 UI 引导管理员去设置;不要静默降级到 yaml 兜底)
        if (m == null || m.isBlank() || "__UNSET__".equals(m)) {
            throw new LlmException(ResultCode.LLM_GLOBAL_DEFAULT_NOT_CONFIGURED,
                    "ai_global_default.model 未设置:管理员必须先调用 PUT /api/admin/ai/global-default");
        }
        log.debug("[PrefResolver] 全局默认 vendor={} model={}", v, m);
        return new Resolved(v, m, null, null, null, KeySource.SYSTEM);
    }

    // ─────────────────────── helpers ───────────────────────

    /**
     * Phase 4 — alias 改写:用户 model 字段不含冒号时,先查 alias;
     * 命中且 enabled → 返回改写后的 "targetVendor:targetModel" 字符串;
     * 未命中 / 禁用 / 含冒号 / 匿名用户 → 返回 null(原字符串继续原路径)。
     *
     * <p>不直接返回 Resolved:改写成 "vendor:model" 后还可能命中用户的同 vendor 代理
     * (走 USER_PRIVATE_KEY),所以交给 {@link #resolveFromRequest} 统一处理。
     */
    private String tryRewriteFromAlias(Long userId, String modelField) {
        // 含冒号:已经是 "vendor:model" 形式,直接走 vendor:model 路径
        if (modelField.contains(":")) return null;
        // 匿名用户:没 alias 可查
        if (userId == null) return null;
        Optional<UserAiModelAlias> aliasOpt = userAliasService.findByUserIdAndAlias(userId, modelField);
        if (aliasOpt.isEmpty()) return null;   // 未命中:静默 fall through
        UserAiModelAlias a = aliasOpt.get();
        if (Boolean.FALSE.equals(a.getEnabled())) {
            log.debug("[PrefResolver] alias '{}' 已禁用,fall through 到原优先级", modelField);
            return null;
        }
        String rewritten = a.getTargetVendor() + ":" + a.getTargetModel();
        log.debug("[PrefResolver] alias '{}' 命中 → 改写为 '{}'", modelField, rewritten);
        return rewritten;
    }

    /**
     * 决定最终 model:request override > proxy.defaultModel > vendor yaml default。
     */
    private String pickModel(String explicitModel, String proxyDefaultModel, String vendor) {
        if (explicitModel != null && !explicitModel.isBlank()) {
            String s = explicitModel.trim();
            // 支持 "vendor:model" 形式(请求显式带 vendor 时只取后半段)
            int idx = s.indexOf(':');
            return idx > 0 ? s.substring(idx + 1).trim() : s;
        }
        if (proxyDefaultModel != null && !proxyDefaultModel.isBlank()) {
            return proxyDefaultModel;
        }
        AiProperties.Provider p = props.getProviders() == null ? null : props.getProviders().get(vendor);
        String yamlDefault = p == null ? null : p.getDefaultModel();
        if (yamlDefault != null && !yamlDefault.isBlank()) {
            return yamlDefault;
        }
        throw new LlmException(ResultCode.LLM_CONFIG_MISSING,
                "vendor=" + vendor + " 既没代理 defaultModel,yaml 也没 default-model,无法决定 model");
    }

    private boolean isVendorEnabledInYaml(String vendor) {
        AiProperties.Provider p = props.getProviders() == null ? null : props.getProviders().get(vendor);
        return p != null && p.isEnabled();
    }

    // ─────────────────────── 解析结果 ───────────────────────

    /**
     * 解析结果不可变记录。
     *
     * @param vendor         最终生效的 vendor(小写)
     * @param model          最终生效的模型名(纯名,不带 vendor: 前缀)
     * @param apiKey         私 Key 明文;null = 走系统共享 Key
     * @param keyFingerprint apiKey 指纹(展示用)
     * @param baseUrl        私 Key 模式下的 baseUrl 覆写;null = 沿用 vendor 默认
     * @param source         三态之一
     */
    public record Resolved(
            String vendor,
            String model,
            String apiKey,
            String keyFingerprint,
            String baseUrl,
            KeySource source
    ) {}

    /**
     * 三态枚举。决定 quota / ratelimit / 降级链 / UsageRecorder 的行为分支。
     */
    public enum KeySource {
        /** 默认 + 系统 Key(走 yaml 的 spring.ai.providers.<vendor>.api-key) */
        SYSTEM,
        /** 用户配了 vendor/model 但仍用系统 Key(走 yaml 共享 Key) */
        USER_OVERRIDE_SYSTEM_KEY,
        /** 用户填了私 Key(完全用自己的 Key,不计平台 quota,不参与降级链) */
        USER_PRIVATE_KEY
    }
}

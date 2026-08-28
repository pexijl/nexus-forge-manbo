package com.nexusforge.ai.service;

import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.ai.entity.AiGlobalDefault;
import com.nexusforge.ai.entity.UserAiPreference;
import com.nexusforge.ai.repository.AiGlobalDefaultRepository;
import com.nexusforge.ai.repository.UserAiPreferenceRepository;
import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import com.nexusforge.model.ChatModel;
import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.provider.VendorChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * AI 用户个性化偏好解析器。
 *
 * <p>职责:在请求进入 LLM 之前,根据当前 userId + 请求体里可选的 model 字段,
 * 解析出最终生效的 (vendor, model, apiKey) 与三态 (SYSTEM / USER_OVERRIDE_SYSTEM_KEY / USER_PRIVATE_KEY)。
 *
 * <p>解析优先级(从高到低):
 * <ol>
 *   <li>请求体显式 {@code model = "vendor:model"}(临门一脚,UI 默认不暴露)</li>
 *   <li>{@code user_ai_preference} 行存在?
 *       <ul>
 *         <li>vendor/model 用 pref;apiKey 由 {@code encryptedApiKey} 是否非空决定</li>
 *       </ul>
 *   </li>
 *   <li>{@code ai_global_default}(单行表) + yaml 系统共享 Key</li>
 *   <li>{@code application.yaml} 的 {@code spring.ai.default-vendor/default-model}</li>
 * </ol>
 *
 * <p>三态分流:
 * <ul>
 *   <li>{@link KeySource#SYSTEM} — 走默认 vendor/model,用 yaml 系统 Key;走降级链;计入平台 quota</li>
 *   <li>{@link KeySource#USER_OVERRIDE_SYSTEM_KEY} — 用户设了 vendor/model 但 Key 仍用系统;走降级链;计入平台 quota</li>
 *   <li>{@link KeySource#USER_PRIVATE_KEY} — 用户填了私 Key;不走降级链;跳过平台 quota</li>
 * </ul>
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

    /**
     * 主入口。
     *
     * @param userId 当前用户 ID(可为 null 表示未登录,匿名走全局默认)
     * @param explicitModel 请求体里的 model 字段(可为 null / blank 表示未指定)
     * @return 解析结果;永远不会为 null
     */
    public Resolved resolve(Long userId, String explicitModel) {
        // 1. 优先级最高:请求体显式 model
        if (explicitModel != null && !explicitModel.isBlank()) {
            Resolved fromRequest = resolveFromRequest(explicitModel.trim(), userId);
            if (fromRequest != null) return fromRequest;
            // 请求里 vendor:model 解析失败 → 报错(用户显式指定,不该悄悄降级)
        }
        // 2. 用户偏好(per-user)
        if (userId != null) {
            Optional<UserAiPreference> prefOpt = userPrefRepo.findById(userId);
            if (prefOpt.isPresent() && Boolean.TRUE.equals(prefOpt.get().getEnabled())) {
                return resolveFromPreference(prefOpt.get());
            }
        }
        // 3. 全局默认
        return resolveFromGlobal();
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

    // ── 内部 ──────────────────────────────────────────────

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
        // 显式 vendor:检查 yaml 已启用
        if (!vendorRegistry.supportsPrivateKey(v) && !isVendorEnabledInYaml(v)) {
            throw new LlmException(ResultCode.LLM_MODEL_NOT_FOUND,
                    "请求指定 vendor=" + v + " 不支持或未启用");
        }
        // 显式 vendor:用户是否配了对应私 Key?
        if (userId != null) {
            Optional<UserAiPreference> prefOpt = userPrefRepo.findById(userId);
            if (prefOpt.isPresent() && Boolean.TRUE.equals(prefOpt.get().getEnabled())
                    && v.equalsIgnoreCase(prefOpt.get().getVendor())
                    && prefOpt.get().getEncryptedApiKey() != null) {
                UserAiPreference p = prefOpt.get();
                String apiKey = cipher.decrypt(p.getEncryptedApiKey());
                log.debug("[PrefResolver] 请求 + 私 Key vendor={} model={}", v, m);
                return new Resolved(v.toLowerCase(Locale.ROOT), m,
                        apiKey, p.getApiKeyFingerprint(), null, KeySource.USER_PRIVATE_KEY);
            }
        }
        // 否则走系统 Key
        log.debug("[PrefResolver] 请求 + 系统 Key vendor={} model={}", v, m);
        return new Resolved(v.toLowerCase(Locale.ROOT), m, null, null, null, KeySource.SYSTEM);
    }

    private Resolved resolveFromPreference(UserAiPreference p) {
        String v = p.getVendor().toLowerCase(Locale.ROOT);
        String m = p.getModel();
        if (p.getEncryptedApiKey() != null && p.getEncryptedApiKey().length > 0) {
            String apiKey = cipher.decrypt(p.getEncryptedApiKey());
            log.debug("[PrefResolver] 偏好 + 私 Key vendor={} model={}", v, m);
            return new Resolved(v, m, apiKey, p.getApiKeyFingerprint(), null, KeySource.USER_PRIVATE_KEY);
        }
        log.debug("[PrefResolver] 偏好 + 系统 Key vendor={} model={}", v, m);
        return new Resolved(v, m, null, null, null, KeySource.USER_OVERRIDE_SYSTEM_KEY);
    }

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

    private boolean isVendorEnabledInYaml(String vendor) {
        AiProperties.Provider p = props.getProviders().get(vendor);
        return p != null && p.isEnabled();
    }

    /**
     * 解析结果不可变记录。
     *
     * @param vendor       最终生效的 vendor(小写)
     * @param model        最终生效的模型名
     * @param apiKey       私 Key 明文;null = 走系统共享 Key
     * @param keyFingerprint apiKey 指纹(展示用)
     * @param baseUrl      私 Key 模式下的 baseUrl 覆写;null = 沿用 vendor 默认
     * @param source       三态之一
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
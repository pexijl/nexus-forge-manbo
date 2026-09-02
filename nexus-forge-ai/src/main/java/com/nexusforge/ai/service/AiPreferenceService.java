package com.nexusforge.ai.service;

import com.nexusforge.ai.client.ApiKeyCipher;
import com.nexusforge.ai.config.AiVendorRegistry;
import com.nexusforge.ai.controller.dto.PreferenceVo;
import com.nexusforge.ai.controller.dto.UpdateGlobalDefaultDto;
import com.nexusforge.ai.controller.dto.UpdatePreferenceDto;
import com.nexusforge.ai.entity.AiGlobalDefault;
import com.nexusforge.ai.entity.UserAiPreference;
import com.nexusforge.ai.repository.AiGlobalDefaultRepository;
import com.nexusforge.ai.repository.UserAiPreferenceRepository;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * AI 个性化偏好 Service。提供:
 * <ul>
 *   <li>{@link #getPreference(Long)} — 查询当前用户偏好(实际生效值)</li>
 *   <li>{@link #updatePreference(Long, UpdatePreferenceDto)} — upsert 偏好</li>
 *   <li>{@link #deletePreference(Long)} — 清除偏好,回退到全局默认</li>
 *   <li>{@link #getGlobalDefault()} — 管理员查询全局默认</li>
 *   <li>{@link #updateGlobalDefault(UpdateGlobalDefaultDto)} — 管理员修改全局默认</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPreferenceService {

    private final UserAiPreferenceRepository userPrefRepo;
    private final AiGlobalDefaultRepository globalRepo;
    private final ApiKeyCipher cipher;
    private final AiVendorRegistry vendorRegistry;

    /**
     * 查询当前用户的"实际生效"偏好。
     * <p>如果用户没有偏好行,返回全局默认(而非 404)。
     */
    public PreferenceVo getPreference(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        Optional<UserAiPreference> opt = userPrefRepo.findById(userId);
        if (opt.isPresent() && Boolean.TRUE.equals(opt.get().getEnabled())) {
            UserAiPreference p = opt.get();
            boolean hasKey = p.getEncryptedApiKey() != null && p.getEncryptedApiKey().length > 0;
            String mode = hasKey ? "PRIVATE" : "OVERRIDE_SYSTEM";
            return PreferenceVo.builder()
                    .customized(true)
                    .vendor(p.getVendor())
                    .model(p.getModel())
                    .mode(mode)
                    .hasApiKey(hasKey)
                    .apiKeyFingerprint(p.getApiKeyFingerprint())
                    .updatedAt(p.getUpdatedAt())
                    .build();
        }
        // 走全局默认
        AiGlobalDefault g = loadGlobal();
        return PreferenceVo.builder()
                .customized(false)
                .vendor(g.getVendor())
                .model(g.getModel())
                .mode("GLOBAL")
                .hasApiKey(false)
                .updatedAt(g.getUpdatedAt())
                .build();
    }

    /**
     * upsert 用户偏好。
     *
     * <p>Key 处理:
     * <ul>
     *   <li>{@code dto.clearApiKey=true} → 清除已有私 Key</li>
     *   <li>{@code dto.apiKey} 非空 → 用该明文覆盖(留空 = 不改 Key)</li>
     *   <li>两者都未传 → 沿用现有 Key(若有)</li>
     * </ul>
     */
    @Transactional
    public PreferenceVo updatePreference(Long userId, UpdatePreferenceDto dto) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        String vendor = dto.getVendor().trim().toLowerCase(Locale.ROOT);
        if (!vendorRegistry.supportsPrivateKey(vendor)) {
            throw new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                    "vendor=" + vendor + " 不支持个性化配置(仅 openai/deepseek/ollama 可用户级配置)");
        }
        UserAiPreference p = userPrefRepo.findById(userId).orElseGet(() -> {
            UserAiPreference np = new UserAiPreference();
            np.setUserId(userId);
            return np;
        });
        p.setVendor(vendor);
        p.setModel(dto.getModel().trim());
        boolean wantClear = Boolean.TRUE.equals(dto.getClearApiKey());
        boolean hasNewKey = dto.getApiKey() != null && !dto.getApiKey().isBlank();
        if (wantClear) {
            p.setEncryptedApiKey(null);
            p.setApiKeyFingerprint(null);
            log.info("[Pref] 清除私 Key: userId={}", userId);
        } else if (hasNewKey) {
            byte[] packed = cipher.encrypt(dto.getApiKey());
            p.setEncryptedApiKey(packed);
            p.setApiKeyFingerprint(cipher.fingerprint(dto.getApiKey()));
            log.info("[Pref] 更新私 Key: userId={}, vendor={}, fp={}", userId, vendor, p.getApiKeyFingerprint());
        }
        userPrefRepo.save(p);
        return getPreference(userId);
    }

    /**
     * 删除用户偏好,回退到全局默认。
     */
    @Transactional
    public void deletePreference(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        if (userPrefRepo.existsById(userId)) {
            userPrefRepo.deleteById(userId);
            log.info("[Pref] 删除用户偏好: userId={}", userId);
        }
    }

    public AiGlobalDefault getGlobalDefault() {
        return loadGlobal();
    }

    /**
     * 管理员修改全局默认(vendor / model / enabled)。id 永远 = 1。
     */
    @Transactional
    public AiGlobalDefault updateGlobalDefault(UpdateGlobalDefaultDto dto) {
        String vendor = dto.getVendor().trim().toLowerCase(Locale.ROOT);
        if (!vendorRegistry.supportsPrivateKey(vendor)) {
            throw new BusinessException(ResultCode.LLM_MODEL_NOT_FOUND,
                    "vendor=" + vendor + " 不支持作为全局默认");
        }
        AiGlobalDefault g = loadGlobal();
        g.setVendor(vendor);
        g.setModel(dto.getModel().trim());
        if (dto.getEnabled() != null) {
            g.setEnabled(dto.getEnabled());
        }
        globalRepo.save(g);
        log.info("[Pref] 管理员更新全局默认: vendor={}, model={}, enabled={}", vendor, g.getModel(), g.getEnabled());
        return g;
    }

    private AiGlobalDefault loadGlobal() {
        return globalRepo.findById(1)
                .orElseThrow(() -> new BusinessException(ResultCode.LLM_CONFIG_MISSING,
                        "ai_global_default 行缺失(数据库初始化失败?)"));
    }
}
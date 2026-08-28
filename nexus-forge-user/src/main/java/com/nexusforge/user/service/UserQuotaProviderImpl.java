package com.nexusforge.user.service;

import com.nexusforge.user.UserQuotaOverride;
import com.nexusforge.user.UserQuotaProvider;
import com.nexusforge.user.entity.User;
import com.nexusforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * P5 Step 6 — {@link UserQuotaProvider} 实现。
 *
 * <p>从 {@code users.plan_quota_override} JSON 列解析配额覆盖。
 * 解析失败(格式错误 / 非 JSON)降级为 {@link Optional#empty()},由 QuotaService 走 role 默认 tier。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserQuotaProviderImpl implements UserQuotaProvider {

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<UserQuotaOverride> getPlanQuotaOverride(Long userId) {
        return userRepository.findById(userId)
                .map(User::getPlanQuotaOverride)
                .filter(json -> json != null && !json.isBlank())
                .flatMap(json -> parseOverride(json, userId));
    }

    private Optional<UserQuotaOverride> parseOverride(String json, Long userId) {
        try {
            var node = objectMapper.readTree(json);
            Long tokenLimit = node.has("dailyTokenLimit")
                    ? node.get("dailyTokenLimit").asLong(-1) : null;
            Long reqLimit = node.has("requestLimit")
                    ? node.get("requestLimit").asLong(-1) : null;
            // JSON 中 null 值 → asLong(-1) → 映射为 Java null(不限)
            if (tokenLimit != null && tokenLimit < 0) tokenLimit = null;
            if (reqLimit != null && reqLimit < 0) reqLimit = null;
            return Optional.of(new UserQuotaOverride(tokenLimit, reqLimit));
        } catch (JacksonException e) {
            log.warn("[Quota] plan_quota_override JSON 解析失败,降级到 role tier: userId={}, err={}",
                    userId, e.getMessage());
            return Optional.empty();
        }
    }
}

package com.nexusforge.service;

import com.nexusforge.config.AiProperties;
import com.nexusforge.config.AiProperties.QuotaConfig;
import com.nexusforge.config.AiProperties.QuotaTier;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.repository.AiMessageUsageRepository;
import com.nexusforge.user.UserQuotaOverride;
import com.nexusforge.user.UserQuotaProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * P5 Step 5/6 — 用量配额校验服务。
 *
 * <p>职责:在 {@link ConversationService#sendMessage} 调用 LLM 之前,检查该用户
 * 24h 累计 token / 请求次数是否超出配额。超出则抛
 * {@link BusinessException#BusinessException(ResultCode, String)}
 * ({@link ResultCode#LLM_QUOTA_EXCEEDED}),GlobalExceptionHandler 映射为 HTTP 429。
 *
 * <p>配额解析优先级(P5 Step 6):
 * <ol>
 *   <li>用户级覆盖 {@code users.plan_quota_override}(JSON,管理员可设)</li>
 *   <li>角色级 tier {@link QuotaConfig#getTiers()}(按 SecurityContextHolder 角色匹配)</li>
 *   <li>默认 tier {@link QuotaConfig#getDefaultUserTier()}</li>
 * </ol>
 *
 * <p>配额开关:{@link QuotaConfig#isEnabled()} 为 false 时,check 直接放行,
 * 仅记录用量不做拦截(适合开发/测试环境)。
 *
 * <p>与 {@link com.nexusforge.client.UsageRecorder} 的区别:
 * QuotaService 是请求前拦截(阻止),UsageRecorder 是请求后计量(记录)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final AiMessageUsageRepository usageRepo;
    private final AiProperties aiProperties;
    private final UserQuotaProvider userQuotaProvider;

    /**
     * 校验 userId 的 24h 配额(含粗估 token 预检)。
     *
     * <p>P5 Step 8 扩展:在 LLM 调用之前,先用 {@code estimatedTokens}(用户输入粗估)
     * 做一次"若放行此请求,是否会超限"的预检。若已超限则直接拒绝,省去无意义的 LLM 调用。
     *
     * @param userId          当前用户 ID
     * @param estimatedTokens 本次请求粗估 token 数(输入内容 / 2 + 16)
     */
    public void check(Long userId, long estimatedTokens) {
        QuotaConfig quota = aiProperties.getQuota();
        if (!quota.isEnabled()) {
            log.debug("[Quota] 配额检查已关闭,放行 userId={}", userId);
            return;
        }

        QuotaTier tier = resolveTier(quota, userId);
        if (tier.getDailyTokenLimit() == null && tier.getRequestLimit() == null) {
            log.debug("[Quota] tier 不限,放行 userId={}", userId);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = now.minusHours(24);
        UsageAggregateRow agg = usageRepo.sumByUserAndWindow(userId, from, now);

        // P5 Step 8:粗估预检——若当前累计 + 粗估已超限,提前拒绝
        if (tier.getDailyTokenLimit() != null
                && agg.totalTokens() + estimatedTokens > tier.getDailyTokenLimit()) {
            log.warn("[Quota] token 预检超限: userId={}, used={}, estimated={}, limit={}",
                    userId, agg.totalTokens(), estimatedTokens, tier.getDailyTokenLimit());
            throw new BusinessException(ResultCode.LLM_QUOTA_EXCEEDED,
                    "24h token 配额即将用尽(已用 %d,预估需 %d,上限 %d)"
                            .formatted(agg.totalTokens(), estimatedTokens, tier.getDailyTokenLimit()));
        }

        // 请求次数上限(无需预估,每次 +1)
        if (tier.getRequestLimit() != null && agg.requestCount() >= tier.getRequestLimit()) {
            log.warn("[Quota] 请求次数超限: userId={}, used={}, limit={}",
                    userId, agg.requestCount(), tier.getRequestLimit());
            throw new BusinessException(ResultCode.LLM_QUOTA_EXCEEDED,
                    "24h 请求次数配额已用尽(已用 %d,上限 %d)"
                            .formatted(agg.requestCount(), tier.getRequestLimit()));
        }

        log.debug("[Quota] 校验通过: userId={}, tokens={}/{}(+est {}), requests={}/{}",
                userId, agg.totalTokens(), tier.getDailyTokenLimit(), estimatedTokens,
                agg.requestCount(), tier.getRequestLimit());
    }

    /**
     * 解析配额 tier。优先级:用户级覆盖 → 角色级 tier → 默认 tier。
     */
    private QuotaTier resolveTier(QuotaConfig quota, Long userId) {
        // 1. 用户级覆盖(P5 Step 6)
        var userOverride = userQuotaProvider.getPlanQuotaOverride(userId);
        if (userOverride.isPresent()) {
            QuotaTier tier = toTier(userOverride.get());
            log.debug("[Quota] 使用用户级覆盖: userId={}, tokenLimit={}, reqLimit={}",
                    userId, tier.getDailyTokenLimit(), tier.getRequestLimit());
            return tier;
        }

        // 2. 角色级 tier(从 SecurityContextHolder 读角色)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String role = ga.getAuthority().toUpperCase();
                QuotaTier tier = quota.getTiers().get(role);
                if (tier != null) {
                    return tier;
                }
            }
        }

        // 3. 默认 tier fallback
        QuotaTier fallback = quota.getTiers().get(quota.getDefaultUserTier().toUpperCase());
        if (fallback == null) {
            log.warn("[Quota] defaultUserTier '{}' 未在 tiers 中配置,放行", quota.getDefaultUserTier());
            QuotaTier open = new QuotaTier();
            open.setDailyTokenLimit(null);
            open.setRequestLimit(null);
            return open;
        }
        return fallback;
    }

    private QuotaTier toTier(UserQuotaOverride override) {
        QuotaTier tier = new QuotaTier();
        tier.setDailyTokenLimit(override.dailyTokenLimit());
        tier.setRequestLimit(override.requestLimit());
        return tier;
    }
}

package com.nexusforge.service;

import com.nexusforge.config.AiProperties;
import com.nexusforge.config.AiProperties.QuotaConfig;
import com.nexusforge.config.AiProperties.QuotaTier;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;
import com.nexusforge.repository.AiMessageUsageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * QuotaService 单元测试。覆盖 P5 Step 5 关键不变量:
 * <ul>
 *   <li>token 超限 → 抛 LLM_QUOTA_EXCEEDED</li>
 *   <li>请求次数超限 → 抛 LLM_QUOTA_EXCEEDED</li>
 *   <li>配额内 → 放行</li>
 *   <li>配额关闭 → 放行</li>
 *   <li>tier limit 为 null → 该维度不限</li>
 *   <li>defaultUserTier fallback</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QuotaService")
class QuotaServiceTest {

    private static final Long USER_ID = 1L;

    @Mock AiMessageUsageRepository usageRepo;
    @Mock com.nexusforge.user.UserQuotaProvider userQuotaProvider;

    private QuotaService service;
    private AiProperties props;

    @BeforeEach
    void setUp() {
        props = new AiProperties();
        service = new QuotaService(usageRepo, props, userQuotaProvider);
        // 模拟 JwtAuthenticationFilter: authorities = ["USER"] (无 ROLE_ 前缀)
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("testuser", null,
                        List.of(new SimpleGrantedAuthority("USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────
    // 超限
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("超限路径")
    class Exceeded {

        @Test
        @DisplayName("token 超限 → 抛 LLM_QUOTA_EXCEEDED")
        void token_exceeded_throws() {
            QuotaTier tier = new QuotaTier();
            tier.setDailyTokenLimit(1000L);
            tier.setRequestLimit(5000L);
            props.getQuota().setTiers(Map.of("USER", tier));

            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(500, 600, 1100, 10));

            assertThatThrownBy(() -> service.check(USER_ID, 0))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException bex = (BusinessException) ex;
                        assertThat(bex.getCode()).isEqualTo(ResultCode.LLM_QUOTA_EXCEEDED.getCode());
                        assertThat(bex.getMessage()).contains("token");
                    });
        }

        @Test
        @DisplayName("请求次数超限 → 抛 LLM_QUOTA_EXCEEDED")
        void request_exceeded_throws() {
            QuotaTier tier = new QuotaTier();
            tier.setDailyTokenLimit(1_000_000L);
            tier.setRequestLimit(5L);
            props.getQuota().setTiers(Map.of("USER", tier));

            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(100, 200, 300, 10));

            assertThatThrownBy(() -> service.check(USER_ID, 0))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException bex = (BusinessException) ex;
                        assertThat(bex.getCode()).isEqualTo(ResultCode.LLM_QUOTA_EXCEEDED.getCode());
                        assertThat(bex.getMessage()).contains("请求次数");
                    });
        }
    }

    // ──────────────────────────────────────────────
    // 放行
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("放行路径")
    class Allowed {

        @Test
        @DisplayName("配额内 → 放行不抛")
        void within_quota_passes() {
            QuotaTier tier = new QuotaTier();
            tier.setDailyTokenLimit(1_000_000L);
            tier.setRequestLimit(5000L);
            props.getQuota().setTiers(Map.of("USER", tier));

            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(500, 300, 800, 10));

            service.check(USER_ID, 0);
            // 不抛即通过
        }

        @Test
        @DisplayName("配额关闭 → 放行,不查 DB")
        void disabled_quota_passes() {
            props.getQuota().setEnabled(false);

            service.check(USER_ID, 0);
            // 不抛,不查 DB
        }

        @Test
        @DisplayName("ADMIN tier 两个 null → 放行,不查 DB")
        void unlimited_tier_passes_without_db() {
            // SecurityContextHolder 设 ADMIN
            SecurityContextHolder.clearContext();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("admin", null,
                            List.of(new SimpleGrantedAuthority("ADMIN"))));

            QuotaTier adminTier = new QuotaTier();
            adminTier.setDailyTokenLimit(null);
            adminTier.setRequestLimit(null);
            props.getQuota().setTiers(Map.of("ADMIN", adminTier));

            service.check(USER_ID, 0);
            // 不抛,不查 DB(dailyTokenLimit == null && requestLimit == null 快速放行)
        }

        @Test
        @DisplayName("dailyTokenLimit=null 但 requestLimit 非 null → 只检查请求次数")
        void null_token_limit_skips_token_check() {
            QuotaTier tier = new QuotaTier();
            tier.setDailyTokenLimit(null);
            tier.setRequestLimit(5000L);
            props.getQuota().setTiers(Map.of("USER", tier));

            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 999_999, 10));

            // token=999_999 超过任何合理 limit,但 dailyTokenLimit=null → 跳过
            service.check(USER_ID, 0);
        }

        @Test
        @DisplayName("defaultUserTier fallback: tiers 无 USER key → 走 defaultUserTier")
        void fallback_to_default_tier() {
            // tiers 里只有 ADMIN,没有 USER
            QuotaTier adminTier = new QuotaTier();
            adminTier.setDailyTokenLimit(null);
            adminTier.setRequestLimit(null);
            props.getQuota().setTiers(Map.of("ADMIN", adminTier));
            props.getQuota().setDefaultUserTier("ADMIN");

            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 0, 0));

            service.check(USER_ID, 0);
            // USER 角色 → 走 defaultUserTier=ADMIN → 不限 → 放行
        }

        @Test
        @DisplayName("tiers 全空 + defaultUserTier 也不存在 → 放行(防 NPE)")
        void empty_tiers_passes() {
            props.getQuota().setTiers(Map.of());
            props.getQuota().setDefaultUserTier("NONEXISTENT");

            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 0, 0));

            service.check(USER_ID, 0);
            // 不抛(NPE 防御)
        }
    }

    // ──────────────────────────────────────────────
    // 用户级覆盖(P5 Step 6)
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("用户级覆盖(plan_quota_override)")
    class UserOverride {

        @Test
        @DisplayName("用户级覆盖优先于角色 tier")
        void user_override_takes_precedence() {
            // 角色 tier: 不限
            QuotaTier roleTier = new QuotaTier();
            roleTier.setDailyTokenLimit(null);
            roleTier.setRequestLimit(null);
            props.getQuota().setTiers(Map.of("USER", roleTier));

            // 用户覆盖: 严格限制
            when(userQuotaProvider.getPlanQuotaOverride(USER_ID))
                    .thenReturn(java.util.Optional.of(
                            new com.nexusforge.user.UserQuotaOverride(1000L, 10L)));
            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 500, 5));

            service.check(USER_ID, 0);
            // 走用户覆盖(1000/10),不走角色不限 tier。用量在限额内 → 放行
        }

        @Test
        @DisplayName("用户级覆盖 token 超限 → 抛 LLM_QUOTA_EXCEEDED")
        void user_override_token_exceeded() {
            when(userQuotaProvider.getPlanQuotaOverride(USER_ID))
                    .thenReturn(java.util.Optional.of(
                            new com.nexusforge.user.UserQuotaOverride(1000L, 100L)));
            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 1500, 5));

            assertThatThrownBy(() -> service.check(USER_ID, 0))
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ResultCode.LLM_QUOTA_EXCEEDED.getCode());
        }

        @Test
        @DisplayName("用户级覆盖请求超限 → 抛 LLM_QUOTA_EXCEEDED")
        void user_override_request_exceeded() {
            when(userQuotaProvider.getPlanQuotaOverride(USER_ID))
                    .thenReturn(java.util.Optional.of(
                            new com.nexusforge.user.UserQuotaOverride(10000L, 10L)));
            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 500, 15));

            assertThatThrownBy(() -> service.check(USER_ID, 0))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getCode())
                    .isEqualTo(ResultCode.LLM_QUOTA_EXCEEDED.getCode());
        }

        @Test
        @DisplayName("用户级覆盖两 null → 不限,不查 DB")
        void user_override_unlimited() {
            when(userQuotaProvider.getPlanQuotaOverride(USER_ID))
                    .thenReturn(java.util.Optional.of(
                            new com.nexusforge.user.UserQuotaOverride(null, null)));

            service.check(USER_ID, 0);
            // 不抛,不查 DB
        }

        // ──────────────────────────────────────────────
        // 粗估 token 预检(P5 Step 8)
        // ──────────────────────────────────────────────

        @Test
        @DisplayName("粗估 token 导致预检超限 → 抛 LLM_QUOTA_EXCEEDED(即使累计未超)")
        void estimated_token_triggers_precheck() {
            QuotaTier tier = new QuotaTier();
            tier.setDailyTokenLimit(1000L);
            tier.setRequestLimit(5000L);
            props.getQuota().setTiers(Map.of("USER", tier));

            // 当前累计 900 token,limit=1000。传 estimatedTokens=200 → 900+200>1000
            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 900, 1));

            assertThatThrownBy(() -> service.check(USER_ID, 200))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException bex = (BusinessException) ex;
                        assertThat(bex.getCode()).isEqualTo(ResultCode.LLM_QUOTA_EXCEEDED.getCode());
                        assertThat(bex.getMessage()).contains("token");
                    });
        }

        @Test
        @DisplayName("粗估 token 在限额内 → 放行")
        void estimated_token_within_limit_passes() {
            QuotaTier tier = new QuotaTier();
            tier.setDailyTokenLimit(1000L);
            tier.setRequestLimit(5000L);
            props.getQuota().setTiers(Map.of("USER", tier));

            // 当前累计 500,limit=1000。传 estimatedTokens=200 → 500+200=700<1000
            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 500, 1));

            service.check(USER_ID, 200);
            // 不抛即通过
        }

        @Test
        @DisplayName("请求次数恰好等于 limit → 超限(>=)")
        void request_count_at_limit_throws() {
            QuotaTier tier = new QuotaTier();
            tier.setDailyTokenLimit(null);
            tier.setRequestLimit(10L);
            props.getQuota().setTiers(Map.of("USER", tier));

            // 恰好 10 次,limit=10 → >= 触发超限
            when(usageRepo.sumByUserAndWindow(eq(USER_ID), any(), any()))
                    .thenReturn(new UsageAggregateRow(0, 0, 0, 10));

            assertThatThrownBy(() -> service.check(USER_ID, 0))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(ResultCode.LLM_QUOTA_EXCEEDED.getCode());
                    });
        }
    }
}

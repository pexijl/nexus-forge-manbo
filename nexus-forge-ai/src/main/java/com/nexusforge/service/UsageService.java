package com.nexusforge.service;

import com.nexusforge.controller.vo.UsageSummaryVo;
import com.nexusforge.repository.AiMessageUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * P5 Step 5 — 用量查询服务。
 *
 * <p>职责:给 {@code /api/ai/usage} 接口提供用户的 24h 用量汇总、按模型拆分明细,
 * 以及单会话累计用量。所有查询只读,不修改数据。
 *
 * <p>与 {@link QuotaService} 的区别:
 * UsageService 是"查"(给前端展示),QuotaService 是"拦"(请求前校验)。
 * 两者都依赖 {@link AiMessageUsageRepository} 的聚合查询,但 UsageService 还做
 * VO 组装和按模型拆分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private final AiMessageUsageRepository usageRepo;

    /**
     * 用户 24h 用量汇总。含总计 + 按模型拆分明细。
     *
     * @param userId 用户 ID
     * @return 汇总 VO(空窗口返回全 0)
     */
    @Transactional(readOnly = true)
    public UsageSummaryVo getSummary(Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = now.minusHours(24);

        UsageAggregateRow agg = usageRepo.sumByUserAndWindow(userId, from, now);
        List<UsageByModelRow> byModel = usageRepo.sumByUserModelWindow(userId, from, now);

        UsageSummaryVo vo = new UsageSummaryVo();
        vo.setPromptTokens(agg.promptTokens());
        vo.setCompletionTokens(agg.completionTokens());
        vo.setTotalTokens(agg.totalTokens());
        vo.setRequestCount(agg.requestCount());
        vo.setByModel(byModel.stream().map(row -> {
            UsageSummaryVo.ModelUsage mu = new UsageSummaryVo.ModelUsage();
            mu.setModel(row.model());
            mu.setPromptTokens(row.promptTokens());
            mu.setCompletionTokens(row.completionTokens());
            mu.setTotalTokens(row.totalTokens());
            mu.setRequestCount(row.requestCount());
            return mu;
        }).toList());

        return vo;
    }

    /**
     * 单会话累计用量(无时间窗)。
     *
     * @param conversationId 会话 ID
     * @return 聚合行(空会话返回全 0)
     */
    @Transactional(readOnly = true)
    public UsageAggregateRow getConversationUsage(Long conversationId) {
        return usageRepo.sumByConversation(conversationId);
    }
}

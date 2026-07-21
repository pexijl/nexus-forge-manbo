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
 * <p>职责:给 {@code /api/ai/usage} 接口提供用户的用量汇总、按模型拆分明细,
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
     * 用户用量汇总(默认 24h 窗口)。
     *
     * @param userId 用户 ID
     * @return 汇总 VO(空窗口返回全 0)
     */
    @Transactional(readOnly = true)
    public UsageSummaryVo getSummary(Long userId) {
        return getSummary(userId, null, null);
    }

    /**
     * 用户用量汇总(自定义时间窗)。
     *
     * <p>from/to 为 null 时分别默认 24h 前 / 当前时间。
     *
     * @param userId 用户 ID
     * @param from   起始时间(含,null → 24h 前)
     * @param to     结束时间(不含,null → now)
     * @return 汇总 VO(空窗口返回全 0)
     */
    @Transactional(readOnly = true)
    public UsageSummaryVo getSummary(Long userId, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime effectiveFrom = from != null ? from : now.minusHours(24);
        OffsetDateTime effectiveTo = to != null ? to : now;

        UsageAggregateRow agg = usageRepo.sumByUserAndWindow(userId, effectiveFrom, effectiveTo);
        List<UsageByModelRow> byModel = usageRepo.sumByUserModelWindow(userId, effectiveFrom, effectiveTo);

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
